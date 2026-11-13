package es.clases.grabadora

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifs {
    const val CH_REC = "grabando"
    const val CH_ALERT = "avisos"
    const val CH_UPLOAD = "subidas"
    const val ID_REC = 1
    const val ID_ALERT = 2

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REC, "Grabación en curso", NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null); enableVibration(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Avisos importantes", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UPLOAD, "Subidas a Drive", NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null); enableVibration(false) }
        )
    }

    fun openApp(c: Context, action: String? = null): PendingIntent = PendingIntent.getActivity(
        c, if (action == null) 0 else 3,
        Intent(c, MainActivity::class.java).setAction(action ?: Intent.ACTION_MAIN),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun alert(c: Context, title: String, text: String, action: String? = null) {
        createChannels(c)
        val n = Notification.Builder(c, CH_ALERT)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(c, action))
            .setAutoCancel(true)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java).notify(ID_ALERT, n)
        } catch (_: SecurityException) { }
    }

    /** Android no dejó empezar la grabación solo: pedimos un toque. */
    fun askToStart(c: Context) = alert(
        c, "Es la hora de clase", "Toca aquí para empezar a grabar", MainActivity.ACTION_RECORD_NOW
    )
}
