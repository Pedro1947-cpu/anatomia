package es.clases.grabadora

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            Scheduler.ACTION_START -> {
                Scheduler.reschedule(c) // deja programada la del próximo día
                val minutes = Prefs(c).durationMinutes
                try {
                    RecorderService.start(c, minutes, scheduled = true)
                } catch (e: Exception) {
                    // Android no nos ha dejado arrancar solos: pedimos un toque al usuario.
                    Notifs.askToStart(c)
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
    }
}
