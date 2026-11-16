package es.clases.grabadora

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Pantalla invisible que arranca la grabación "desde delante".
 * Android solo da el micrófono de verdad a un servicio que se arranca mientras la app
 * está visible; si se arranca desde una alarma en segundo plano, graba silencio.
 */
class StartActivity : Activity() {

    companion object {
        fun intent(c: Context, title: String, minutes: Int, restart: Boolean) =
            Intent(c, StartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra("title", title).putExtra("minutes", minutes).putExtra("restart", restart)

        fun pending(c: Context, title: String, minutes: Int, restart: Boolean): PendingIntent =
            PendingIntent.getActivity(
                c, if (restart) 21 else 20, intent(c, title, minutes, restart),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** Intenta abrir la pantalla invisible sin que el usuario toque nada. */
        fun launch(c: Context, title: String, minutes: Int, restart: Boolean): Boolean {
            if (!Settings.canDrawOverlays(c)) return false
            return try {
                c.startActivity(intent(c, title, minutes, restart)); true
            } catch (_: Exception) { false }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        getSystemService(NotificationManager::class.java).cancel(Notifs.ID_START)
        val title = intent.getStringExtra("title") ?: ""
        val minutes = intent.getIntExtra("minutes", 0)
        val restart = intent.getBooleanExtra("restart", false)
        if (restart || !RecorderService.isRecording || RecorderService.currentTitle != title) {
            try {
                RecorderService.start(this, minutes, scheduled = true, title = title, restart = restart)
            } catch (_: Exception) { }
        }
        // Seguimos "visibles" un momento para que el servicio coja el micrófono.
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2500)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
