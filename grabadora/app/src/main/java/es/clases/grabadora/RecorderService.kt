package es.clases.grabadora

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Servicio que graba el audio con una notificación fija y luego lo sube a Drive. */
class RecorderService : Service() {

    companion object {
        private const val ACT_START = "start"
        private const val ACT_STOP = "stop"
        private const val ACT_ARM = "arm"
        /** Tope de seguridad para grabaciones manuales que se olvidan encendidas. */
        const val MANUAL_MAX_MINUTES = 6 * 60

        @Volatile var isRecording = false
            private set
        @Volatile var startedAt = 0L
            private set
        @Volatile var currentTitle = ""
            private set
        /** Intentos fallidos seguidos de abrir el micrófono (para no entrar en bucle). */
        @Volatile private var failedStarts = 0

        /** Escucha para que la pantalla se actualice al empezar/parar. */
        var listener: (() -> Unit)? = null

        /**
         * El servicio vivo y "en guardia": se arrancó con la app delante, así que Android
         * le deja usar el micrófono de verdad mientras siga vivo, aunque la app esté cerrada.
         */
        @Volatile var instance: RecorderService? = null
            private set
        val armed get() = instance?.armedFlag == true

        /** Actualiza el texto "Próxima: …" de la notificación en guardia. */
        fun refresh() { instance?.let { s -> s.handler.post { s.refreshIdle() } } }

        /** Llamar con la app delante: deja el servicio en guardia para las próximas clases. */
        fun arm(c: Context) {
            if (armed) { instance?.handler?.post { instance?.refreshIdle() }; return }
            try {
                c.startForegroundService(Intent(c, RecorderService::class.java).setAction(ACT_ARM))
            } catch (_: Exception) { }
        }

        fun start(c: Context, minutes: Int, scheduled: Boolean, title: String = "", restart: Boolean = false) {
            // Si ya está en guardia, se le habla directamente (sin volver a arrancarlo desde
            // segundo plano, que es lo que hace que Android silencie el micrófono).
            instance?.takeIf { it.armedFlag }?.let { s ->
                s.handler.post { s.handleStart(minutes, scheduled, title, restart) }
                return
            }
            val i = Intent(c, RecorderService::class.java).setAction(ACT_START)
                .putExtra("minutes", minutes).putExtra("scheduled", scheduled)
                .putExtra("title", title).putExtra("restart", restart)
            c.startForegroundService(i)
        }

        fun stop(c: Context) {
            instance?.let { s -> s.handler.post { s.handleStop() }; return }
            try {
                c.startService(Intent(c, RecorderService::class.java).setAction(ACT_STOP))
            } catch (_: Exception) { }
        }
    }

    private var recorder: MediaRecorder? = null
    private var currentPath: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var armedFlag = false
    private val stopRunnable = Runnable { stopRecording() }
    /** Cuántas veces hemos rearrancado esta clase por micrófono mudo (para no entrar en bucle). */
    private var restarts = 0
    /**
     * Si Android nos da el micrófono "mudo", la amplitud se queda exactamente en 0
     * (con un micrófono de verdad nunca es 0 durante un minuto entero).
     * Entonces rearrancamos la grabación desde la pantalla invisible, que sí recibe audio.
     */
    private val silenceCheck: Runnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val amp = try { recorder?.maxAmplitude ?: -1 } catch (_: Exception) { -1 }
            if (amp == 0) {
                val left = Prefs(this@RecorderService).stopAt - System.currentTimeMillis()
                val minutes = if (left > 0) ((left + 59_999) / 60_000).toInt() else 0
                if (restarts < 3 && StartActivity.launch(this@RecorderService, currentTitle, minutes, restart = true)) {
                    restarts++
                } else {
                    Notifs.alert(this@RecorderService, "El micrófono está mudo",
                        "Android ha bloqueado el micrófono. Toca aquí para volver a grabar bien.",
                        tap = StartActivity.pending(this@RecorderService, currentTitle, minutes, restart = true))
                }
            }
            handler.postDelayed(this, 60_000L)
        }
    }
    private var uploading = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifs.createChannels(this)
        when (intent?.action) {
            ACT_ARM -> {
                if (isRecording || uploading || goForeground(idleNotification())) armedFlag = true
                else stopSelf()
            }
            ACT_START -> handleStart(
                intent.getIntExtra("minutes", 0), intent.getBooleanExtra("scheduled", false),
                intent.getStringExtra("title") ?: "", intent.getBooleanExtra("restart", false)
            )
            ACT_STOP -> handleStop()
            else -> if (!isRecording && !uploading) finishOrIdle()
        }
        return if (armedFlag) START_STICKY else START_NOT_STICKY
    }

    private fun handleStart(minutes: Int, scheduled: Boolean, title: String, restart: Boolean) {
        if (isRecording && restart) {
            // El micrófono estaba mudo: tiramos ese trozo y volvemos a empezar.
            goForeground(recordingNotification(Prefs(this).stopAt))
            stopRecording(continuing = true, discard = true)
            startRecording(minutes, scheduled, title)
        } else if (!isRecording) {
            restarts = 0
            startRecording(minutes, scheduled, title)
        } else if (scheduled) {
            // Empieza otra clase seguida: cerramos la anterior y abrimos la nueva.
            goForeground(recordingNotification(Prefs(this).stopAt))
            stopRecording(continuing = true)
            startRecording(minutes, scheduled, title)
        } else {
            goForeground(recordingNotification(Prefs(this).stopAt)) // ya estaba grabando
        }
    }

    private fun handleStop() {
        if (isRecording) stopRecording()
        else if (!uploading) finishOrIdle()
    }

    /** Sin nada que hacer: si está en guardia se queda esperando; si no, se cierra. */
    private fun finishOrIdle() {
        if (armedFlag && goForeground(idleNotification())) return
        armedFlag = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun refreshIdle() {
        if (armedFlag && !isRecording && !uploading) goForeground(idleNotification())
    }

    private fun idleNotification(): Notification {
        val p = Prefs(this)
        val es = Locale("es", "ES")
        val text = if (p.nextBegin > 0) {
            val day = SimpleDateFormat("EEEE", es).format(Date(p.nextBegin))
            val hour = SimpleDateFormat("HH:mm", es).format(Date(p.nextBegin))
            "Próxima: " + p.nextTitle.ifEmpty { "clase" } + " · $day $hour"
        } else "Esperando la próxima clase"
        return Notification.Builder(this, Notifs.CH_IDLE)
            .setSmallIcon(R.drawable.ic_mic).setColor(0xFF111111.toInt())
            .setContentTitle("Lista para grabar")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(Notifs.openApp(this))
            .build()
    }

    private fun goForeground(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifs.ID_REC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(Notifs.ID_REC, n)
        }
        true
    } catch (e: Exception) {
        false
    }


    private fun recordingNotification(endAt: Long): Notification {
        val stopPi = PendingIntent.getService(
            this, 10, Intent(this, RecorderService::class.java).setAction(ACT_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val until = if (endAt > 0)
            "Se parará sola a las " + SimpleDateFormat("HH:mm", Locale("es")).format(Date(endAt))
        else "Pulsa Parar cuando termine la clase"
        return Notification.Builder(this, Notifs.CH_REC)
            .setSmallIcon(R.drawable.ic_mic).setColor(0xFF111111.toInt())
            .setContentTitle(if (currentTitle.isNotEmpty()) "Grabando: $currentTitle" else "Grabando la clase")
            .setContentText(until)
            .setUsesChronometer(true)
            .setWhen(if (startedAt > 0) startedAt else System.currentTimeMillis())
            .setOngoing(true)
            .setContentIntent(Notifs.openApp(this))
            .addAction(Notification.Action.Builder(null, "Parar", stopPi).build())
            .build()
    }

    private fun startRecording(requestedMinutes: Int, scheduled: Boolean, title: String) {
        currentTitle = title
        val minutes = if (requestedMinutes > 0) requestedMinutes else MANUAL_MAX_MINUTES
        val now = System.currentTimeMillis()
        val endAt = now + minutes * 60_000L
        startedAt = now
        if (!goForeground(recordingNotification(if (requestedMinutes > 0) endAt else 0))) {
            startedAt = 0
            if (scheduled) Notifs.askToStart(this, title, requestedMinutes)
            stopSelf()
            return
        }

        val dir = File(getExternalFilesDir(null), "Grabaciones").apply { mkdirs() }
        val name = fileName(now, title)
        val file = File(dir, name)
        try {
            recorder = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(48_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
                maxAmplitude // pone a cero el medidor para la comprobación de silencio
            }
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            file.delete()
            startedAt = 0
            failedStarts++
            if (armedFlag) goForeground(idleNotification()) else stopForeground(STOP_FOREGROUND_REMOVE)
            // Casi siempre es que Android no da el micrófono a una app en segundo plano.
            // Lo reintentamos desde la pantalla invisible (que sí lo recibe), y si tampoco,
            // dejamos un aviso que con un solo toque empieza a grabar.
            val retried = failedStarts <= 2 &&
                handler.postDelayed({
                    if (!isRecording && !StartActivity.launch(this, title, requestedMinutes, restart = false)) {
                        askTap(title, requestedMinutes)
                    }
                }, if (failedStarts == 1) 1_500L else 15_000L)
            if (!retried) askTap(title, requestedMinutes)
            handler.postDelayed({ if (!isRecording && !uploading) finishOrIdle() }, 20_000L)
            return
        }
        failedStarts = 0

        currentPath = file.absolutePath
        isRecording = true
        Store.add(this, Recording(file.absolutePath, name, now, Recording.RECORDING, title = title))

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grabadora:rec")
            .apply { acquire(minutes * 60_000L + 10 * 60_000L) }

        Prefs(this).stopAt = endAt
        Scheduler.scheduleStop(this, endAt)
        handler.postDelayed(stopRunnable, minutes * 60_000L)
        handler.postDelayed(silenceCheck, 45_000L)
        listener?.invoke()
    }

    private fun askTap(title: String, minutes: Int) {
        Notifs.alert(this, "No se pudo empezar a grabar",
            "Android no dejó usar el micrófono (o lo está usando otra app). Toca aquí para grabar.",
            tap = StartActivity.pending(this, title, minutes, restart = false))
    }

    @Suppress("DEPRECATION")
    private fun newRecorder() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    private fun fileName(t: Long, title: String): String {
        val es = Locale("es", "ES")
        val day = SimpleDateFormat("EEEE", es).format(Date(t))
        val subject = title.replace(Regex("[\\\\/:*?\"<>|]"), "-").take(60).trim().ifEmpty { "Clase" }
        return subject + " " + SimpleDateFormat("yyyy-MM-dd", es).format(Date(t)) +
            " (" + day + ") " + SimpleDateFormat("HH'h'mm", es).format(Date(t)) + ".m4a"
    }

    private fun stopRecording(continuing: Boolean = false, discard: Boolean = false) {
        if (!isRecording) return
        handler.removeCallbacks(stopRunnable)
        handler.removeCallbacks(silenceCheck)
        Scheduler.cancelStop(this)
        Prefs(this).stopAt = 0
        val path = currentPath
        var ok = true
        try {
            recorder?.stop()
        } catch (e: Exception) {
            ok = false // grabación demasiado corta o vacía
        }
        recorder?.release()
        recorder = null
        isRecording = false
        startedAt = 0
        currentPath = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        if (path != null) {
            if (ok && !discard && File(path).length() > 0) {
                Store.update(this, path) { it.status = Recording.PENDING }
            } else {
                File(path).delete()
                Store.remove(this, path)
            }
        }
        listener?.invoke()
        uploadThenFinish(continuing)
    }

    /** Sube en el momento (con la notificación aún visible); si falla, se reintenta luego. */
    private fun uploadThenFinish(continuing: Boolean = false) {
        if (Store.toUpload(this).isEmpty() || !Uploader.networkOk(this)) {
            UploadJob.schedule(this)
            if (!continuing) {
                finishOrIdle()
            }
            return
        }
        uploading = true
        val nm = getSystemService(NotificationManager::class.java)
        fun show(text: String) {
            val n = Notification.Builder(this, Notifs.CH_UPLOAD)
                .setSmallIcon(R.drawable.ic_upload).setColor(0xFF111111.toInt())
                .setContentTitle("Subiendo a Google Drive")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(Notifs.openApp(this))
                .build()
            if (isRecording) return // la notificación de grabación tiene prioridad
            try { nm.notify(Notifs.ID_REC, n) } catch (_: SecurityException) { }
        }
        show("Preparando…")
        Thread {
            val ok = Uploader.uploadAll(this) { msg ->
                show(msg)
                handler.post { listener?.invoke() }
            }
            handler.post {
                uploading = false
                if (!ok) UploadJob.schedule(this)
                listener?.invoke()
                if (!isRecording) {
                    finishOrIdle()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        armedFlag = false
        if (isRecording) stopRecordingQuietly()
        super.onDestroy()
    }

    private fun stopRecordingQuietly() {
        try { recorder?.stop() } catch (_: Exception) { }
        recorder?.release()
        recorder = null
        isRecording = false
        startedAt = 0
        currentPath?.let { p -> Store.update(this, p) { it.status = Recording.PENDING } }
        wakeLock?.let { if (it.isHeld) it.release() }
        UploadJob.schedule(this)
    }
}
