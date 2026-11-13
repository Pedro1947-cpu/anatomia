package es.clases.grabadora

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Busca la última versión publicada en GitHub y la instala desde la propia app. */
object Updater {
    private const val API = "https://api.github.com/repos/Pedro1947-cpu/anatomia/releases/latest"

    data class Update(val code: Long, val name: String, val url: String)

    @Suppress("DEPRECATION")
    fun currentCode(c: Context): Long {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    fun currentName(c: Context): String =
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"

    /** La versión nueva si la hay; null si ya está al día. Lanza excepción si no hay internet. */
    fun check(c: Context): Update? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val json = try {
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
        val tag = json.getString("tag_name") // grabadora-v1.7
        val code = tag.substringAfterLast('.').toLongOrNull() ?: return null
        val assets = json.getJSONArray("assets")
        var url = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
        }
        if (url.isEmpty() || code <= currentCode(c)) return null
        return Update(code, tag.substringAfter("-v"), url)
    }

    fun download(c: Context, u: Update, onProgress: (Int) -> Unit = {}): File {
        val file = File(c.cacheDir, "actualizacion.apk")
        val conn = (URL(u.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** Entrega el APK al instalador de Android (que pedirá confirmación si hace falta). */
    /** Comprueba, descarga e instala sin tocar nada. Devuelve true si no hay error. */
    fun autoUpdate(c: Context, interactive: Boolean): Boolean {
        if (RecorderService.isRecording) return false
        val u = check(c) ?: return true
        install(c, download(c, u), interactive)
        return true
    }

    fun install(c: Context, apk: File, interactive: Boolean = true) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            // Si esta app ya instaló la versión anterior, Android puede actualizar sin preguntar.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("grabadora.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                c, 7, Intent(c, InstallReceiver::class.java).putExtra("interactive", interactive), flags
            )
            session.commit(pi.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val shown = intent.getBooleanExtra("interactive", false) &&
                    try { c.startActivity(confirm); true } catch (e: Exception) { false }
                if (!shown) {
                    // En segundo plano Android no deja abrir la confirmación: aviso para un toque.
                    Notifs.createChannels(c)
                    val pi = PendingIntent.getActivity(
                        c, 8, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val n = android.app.Notification.Builder(c, Notifs.CH_UPLOAD)
                        .setSmallIcon(R.drawable.ic_mic).setColor(0xFF111111.toInt())
                        .setContentTitle("Nueva versión de la Grabadora")
                        .setContentText("Toca para instalarla")
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build()
                    try {
                        c.getSystemService(android.app.NotificationManager::class.java).notify(4, n)
                    } catch (_: SecurityException) { }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "código $status"
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Notifs.alert(c, "No se pudo actualizar", msg)
                }
            }
        }
    }
}

/** Cada pocas horas, con internet, busca e instala la versión nueva. */
class UpdateJob : android.app.job.JobService() {
    override fun onStartJob(params: android.app.job.JobParameters): Boolean {
        Thread {
            try { Updater.autoUpdate(this, interactive = false) } catch (_: Exception) { }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: android.app.job.JobParameters) = false

    companion object {
        fun schedule(c: Context) {
            val js = c.getSystemService(android.app.job.JobScheduler::class.java)
            if (js.getPendingJob(44) != null) return
            val job = android.app.job.JobInfo.Builder(44, android.content.ComponentName(c, UpdateJob::class.java))
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6 * 3600 * 1000L)
                .setPersisted(true)
                .build()
            js.schedule(job)
        }
    }
}
