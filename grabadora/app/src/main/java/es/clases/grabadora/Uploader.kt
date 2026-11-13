package es.clases.grabadora

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sube las grabaciones a Google Drive.
 *
 * El script de Google (Apps Script) del usuario abre una "subida reanudable" en su Drive y nos
 * devuelve la dirección; el móvil manda el audio directamente a Google por trozos. Si se corta,
 * se continúa desde donde se quedó.
 */
object Uploader {
    private const val CHUNK = 4 * 1024 * 1024 // múltiplo de 256 KB, como pide Google
    private val running = AtomicBoolean(false)

    fun networkOk(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (Prefs(c).wifiOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        return true
    }

    /** Devuelve false si algo falló y conviene reintentar más tarde. */
    fun uploadAll(c: Context, onProgress: (String) -> Unit = {}): Boolean {
        if (!running.compareAndSet(false, true)) return true
        try {
            val url = Prefs(c).scriptUrl
            val pending = Store.toUpload(c)
            if (pending.isEmpty()) return true
            if (url.isBlank()) {
                pending.forEach { r ->
                    Store.update(c, r.path) {
                        it.status = Recording.ERROR
                        it.error = "Falta conectar Google Drive (abre la app)"
                    }
                }
                return true // no tiene sentido reintentar hasta que lo configure
            }
            var allOk = true
            for (r in pending) {
                if (!r.file.exists()) {
                    Store.update(c, r.path) {
                        it.status = Recording.ERROR; it.error = "El archivo ya no está en el móvil"
                    }
                    continue
                }
                try {
                    uploadOne(c, url, r, onProgress)
                } catch (e: Exception) {
                    allOk = false
                    Store.update(c, r.path) { it.status = Recording.ERROR; it.error = friendly(e) }
                }
            }
            return allOk
        } finally {
            running.set(false)
        }
    }

    private fun friendly(e: Exception) = when (e) {
        is UnknownHostException -> "Sin internet; se reintentará sola"
        is SocketTimeoutException -> "La conexión es muy lenta; se reintentará sola"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun uploadOne(c: Context, scriptUrl: String, r: Recording, onProgress: (String) -> Unit) {
        Store.update(c, r.path) { it.status = Recording.UPLOADING; it.error = "" }
        val file = r.file
        val size = file.length()
        var session = r.uploadUrl
        var offset = 0L

        if (session.isNotEmpty()) {
            when (val st = queryStatus(session, size)) {
                is Status.Finished -> return finish(c, r, st.id)
                is Status.Partial -> offset = st.next
                Status.Gone -> session = ""
            }
        }
        if (session.isEmpty()) {
            val res = callScript(
                scriptUrl, JSONObject()
                    .put("action", "start").put("name", r.name)
                    .put("mimeType", "audio/mp4").put("size", size)
                    .put("subfolder", r.title)
            )
            session = res.optString("uploadUrl")
            if (session.isEmpty()) throw IOException("El script de Drive no devolvió dirección de subida")
            Store.update(c, r.path) { it.uploadUrl = session }
            offset = 0
        }

        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val len = minOf(CHUNK.toLong(), size - offset).toInt()
                raf.seek(offset)
                raf.readFully(buf, 0, len)
                onProgress("${r.name}: ${if (size > 0) offset * 100 / size else 0}%")
                val conn = (URL(session).openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    setFixedLengthStreamingMode(len)
                    setRequestProperty("Content-Type", "audio/mp4")
                    if (len > 0) setRequestProperty("Content-Range", "bytes $offset-${offset + len - 1}/$size")
                    else setRequestProperty("Content-Range", "bytes */$size")
                }
                conn.outputStream.use { it.write(buf, 0, len) }
                val code = conn.responseCode
                when (code) {
                    200, 201 -> {
                        val body = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        return finish(c, r, JSONObject(body).optString("id"))
                    }
                    308 -> {
                        offset = nextOffset(conn.getHeaderField("Range"))
                        conn.disconnect()
                    }
                    404, 410 -> {
                        conn.disconnect()
                        Store.update(c, r.path) { it.uploadUrl = "" }
                        throw IOException("La subida caducó; se empezará de nuevo")
                    }
                    else -> {
                        conn.disconnect()
                        throw IOException("Google Drive respondió con error $code")
                    }
                }
            }
        }
    }

    private fun finish(c: Context, r: Recording, id: String) {
        Store.update(c, r.path) {
            it.status = Recording.DONE
            it.uploadUrl = ""
            it.error = ""
            it.driveLink = if (id.isNotEmpty()) "https://drive.google.com/file/d/$id/view" else ""
        }
        if (Prefs(c).deleteAfterUpload) File(r.path).delete()
    }

    private sealed class Status {
        class Finished(val id: String) : Status()
        class Partial(val next: Long) : Status()
        object Gone : Status()
    }

    private fun queryStatus(session: String, size: Long): Status {
        val conn = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setFixedLengthStreamingMode(0)
            setRequestProperty("Content-Range", "bytes */$size")
        }
        return try {
            conn.outputStream.close()
            when (conn.responseCode) {
                200, 201 -> Status.Finished(
                    JSONObject(conn.inputStream.bufferedReader().readText()).optString("id")
                )
                308 -> Status.Partial(nextOffset(conn.getHeaderField("Range")))
                else -> Status.Gone
            }
        } finally {
            conn.disconnect()
        }
    }

    /** "bytes=0-1234" → 1235. Sin cabecera = Google aún no tiene nada. */
    private fun nextOffset(range: String?): Long {
        if (range.isNullOrBlank()) return 0
        return range.substringAfterLast('-').trim().toLongOrNull()?.plus(1) ?: 0
    }

    /** Llama al script de Google del usuario. Sigue las redirecciones a mano (POST → GET). */
    fun callScript(url: String, body: JSONObject): JSONObject {
        var conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        var code = conn.responseCode
        var hops = 0
        while (code in 300..399 && hops < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            code = conn.responseCode
            hops++
        }
        val text = try {
            (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        } finally {
            conn.disconnect()
        }
        if (code != 200) throw IOException("El enlace de Drive no responde bien (código $code). Revisa que esté bien copiado.")
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("El enlace no es el del script, o no se publicó con acceso «Cualquier usuario».")
        }
        if (json.has("error")) throw IOException("Drive: " + json.getString("error"))
        return json
    }
}
