package es.clases.grabadora

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            Scheduler.ACTION_START -> {
                val p = Prefs(c)
                val title = p.nextTitle
                val begin = p.nextBegin
                val end = p.nextEnd
                val now = System.currentTimeMillis()
                // Deja programada la siguiente clase (la que empiece después de esta).
                Scheduler.reschedule(c, maxOf(now, begin))
                val minutes = if (end > now) ((end - now + 59_999) / 60_000).toInt()
                else p.durationMinutes
                // 1) Arrancamos desde una pantalla invisible para que el micrófono no salga mudo.
                Notifs.startFullScreen(c, title, minutes)
                StartActivity.launch(c, title, minutes, restart = false)
                // 2) Por si Android no deja abrir la pantalla: en 20 s arrancamos igualmente.
                Scheduler.scheduleFallback(c, title, minutes)
            }
            Scheduler.ACTION_FALLBACK -> {
                if (RecorderService.isRecording) return
                val title = intent.getStringExtra("title") ?: ""
                val minutes = intent.getIntExtra("minutes", 60)
                try {
                    RecorderService.start(c, minutes, scheduled = true, title = title)
                } catch (e: Exception) {
                    // Android no nos ha dejado arrancar solos: pedimos un toque al usuario.
                    Notifs.askToStart(c, title, minutes)
                }
            }
            Scheduler.ACTION_STOP -> RecorderService.stop(c)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Scheduler.reschedule(c)
        if (!RecorderService.isRecording) Store.recoverInterrupted(c)
        UploadJob.schedule(c)
        UpdateJob.schedule(c)
    }
}
