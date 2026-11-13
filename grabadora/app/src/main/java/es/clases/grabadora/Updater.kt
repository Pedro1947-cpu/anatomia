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
    fun install(c: Context, apk: File) {
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
            val pi = PendingIntent.getBroadcast(c, 7, Intent(c, InstallReceiver::class.java), flags)
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
                try {
                    c.startActivity(confirm)
                } catch (e: Exception) {
                    Notifs.alert(c, "Actualización lista", "Abre la app y vuelve a pulsar Actualizar.")
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
