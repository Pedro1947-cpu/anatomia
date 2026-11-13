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
        /** Tope de seguridad para grabaciones manuales que se olvidan encendidas. */
        const val MANUAL_MAX_MINUTES = 6 * 60

        @Volatile var isRecording = false
            private set
        @Volatile var startedAt = 0L
            private set
        @Volatile var currentTitle = ""
            private set

        /** Escucha para que la pantalla se actualice al empezar/parar. */
        var listener: (() -> Unit)? = null

        fun start(c: Context, minutes: Int, scheduled: Boolean, title: String = "") {
            val i = Intent(c, RecorderService::class.java).setAction(ACT_START)
                .putExtra("minutes", minutes).putExtra("scheduled", scheduled)
                .putExtra("title", title)
            c.startForegroundService(i)
        }

        fun stop(c: Context) {
            try {
                c.startService(Intent(c, RecorderService::class.java).setAction(ACT_STOP))
            } catch (_: Exception) { }
        }
    }

    private var recorder: MediaRecorder? = null
    private var currentPath: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopRecording() }
    /** Si Android nos da el micrófono "mudo", la amplitud se queda en 0: avisamos. */
    private val silenceCheck = Runnable {
        val amp = try { recorder?.maxAmplitude ?: -1 } catch (_: Exception) { -1 }
        if (isRecording && amp == 0) {
            Notifs.alert(this, "La grabación parece estar en silencio",
                "Puede que el móvil haya bloqueado el micrófono. Toca para abrir la app y comprobarlo.")
        }
    }
    private var uploading = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifs.createChannels(this)
        when (intent?.action) {
            ACT_START -> {
                val minutes = intent.getIntExtra("minutes", 0)
                val scheduled = intent.getBooleanExtra("scheduled", false)
                val title = intent.getStringExtra("title") ?: ""
                if (!isRecording) {
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
            ACT_STOP -> {
                if (isRecording) stopRecording()
                else if (!uploading) stopSelf()
            }
            else -> if (!isRecording && !uploading) stopSelf()
        }
        return START_NOT_STICKY
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
            Notifs.alert(this, "No se pudo grabar", "El micrófono está ocupado o falta el permiso. Abre la app para revisarlo.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

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

    private fun stopRecording(continuing: Boolean = false) {
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
            if (ok && File(path).length() > 0) {
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.start()
    }

    override fun onDestroy() {
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
