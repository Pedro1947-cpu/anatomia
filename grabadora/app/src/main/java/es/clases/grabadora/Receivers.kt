package es.clases.grabadora

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            Scheduler.ACTION_START -> {
                Log.init(c)
                val p = Prefs(c)
                Log.add("Alarma de clase: " + p.nextTitle + " · en guardia=" + RecorderService.armed +
                    " · micro=" + (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0))
                val title = p.nextTitle
                val begin = p.nextBegin
                val end = p.nextEnd
                val now = System.currentTimeMillis()
                // Deja programada la siguiente clase (la que empiece después de esta).
                Scheduler.reschedule(c, maxOf(now, begin))
                val minutes = if (end > now) ((end - now + 59_999) / 60_000).toInt()
                else p.durationMinutes
                if (RecorderService.armed) {
                    // El servicio ya estaba en guardia con el micrófono: empieza sin más.
                    RecorderService.start(c, minutes, scheduled = true, title = title)
                    return
                }
                // 1) Arrancamos desde una pantalla invisible para que el micrófono no salga mudo.
                StartActivity.launch(c, title, minutes, restart = false)
                // 2) Por si Android no deja abrir la pantalla: en 20 s arrancamos igualmente.
                Scheduler.scheduleFallback(c, title, minutes)
            }
            Scheduler.ACTION_FALLBACK -> {
                Log.add("Plan B (20 s): grabando=" + RecorderService.isRecording)
                if (RecorderService.isRecording) return
                val title = intent.getStringExtra("title") ?: ""
                val minutes = intent.getIntExtra("minutes", 60)
                try {
                    RecorderService.start(c, minutes, scheduled = true, title = title)
                } catch (e: Exception) {
                    // Android no nos ha dejado arrancar solos: pedimos un toque al usuario.
                    Log.add("Plan B falló: " + e)
                    Notifs.askToStart(c, title, minutes)
                }
            }
            Scheduler.ACTION_STOP -> {
                Log.init(c)
                val at = intent.getLongExtra("at", 0L)
                val stopAt = Prefs(c).stopAt
                if (at > 0 && stopAt > 0 && kotlin.math.abs(stopAt - at) > 60_000L) {
                    // Era la parada de la clase anterior y ya ha empezado la siguiente: no se toca.
                    Log.add("Parada de la clase anterior ignorada (sigue grabando la nueva)")
                } else {
                    Log.add("Fin de la clase: parar")
                    RecorderService.stop(c)
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Log.add("Sistema: " + intent.action?.substringAfterLast('.'))
        Scheduler.reschedule(c)
        if (!RecorderService.isRecording) Store.recoverInterrupted(c)
        UploadJob.schedule(c)
        UpdateJob.schedule(c)
    }
}
