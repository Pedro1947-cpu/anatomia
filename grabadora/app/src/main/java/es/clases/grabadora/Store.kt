package es.clases.grabadora

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Lista de grabaciones y su estado de subida, guardada en un JSON. */
data class Recording(
    val path: String,
    val name: String,
    val createdAt: Long,
    var status: String = PENDING,
    var error: String = "",
    var uploadUrl: String = "",
    var driveLink: String = "",
    /** Asignatura (título del evento del calendario); vacío si no se sabe. */
    val title: String = "",
) {
    val file get() = File(path)

    fun toJson() = JSONObject()
        .put("path", path).put("name", name).put("createdAt", createdAt)
        .put("status", status).put("error", error)
        .put("uploadUrl", uploadUrl).put("driveLink", driveLink).put("title", title)

    companion object {
        const val RECORDING = "grabando"
        const val PENDING = "pendiente"
        const val UPLOADING = "subiendo"
        const val DONE = "subida"
        const val ERROR = "error"

        fun fromJson(o: JSONObject) = Recording(
            o.getString("path"), o.getString("name"), o.getLong("createdAt"),
            o.optString("status", PENDING), o.optString("error"),
            o.optString("uploadUrl"), o.optString("driveLink"), o.optString("title"),
        )
    }
}

object Store {
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, "grabaciones.json")

    fun all(c: Context): List<Recording> {
        synchronized(lock) {
            val f = file(c)
            if (!f.exists()) return emptyList()
            return try {
                val a = JSONArray(f.readText())
                (0 until a.length()).map { Recording.fromJson(a.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun save(c: Context, list: List<Recording>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        val f = file(c)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f)
    }

    fun add(c: Context, r: Recording) = synchronized(lock) {
        save(c, all(c) + r)
    }

    fun update(c: Context, path: String, change: (Recording) -> Unit) = synchronized(lock) {
        val list = all(c)
        list.firstOrNull { it.path == path }?.let(change)
        save(c, list)
    }

    fun remove(c: Context, path: String) = synchronized(lock) {
        save(c, all(c).filterNot { it.path == path })
    }

    /** Las que faltan por subir (incluye las que fallaron o se cortaron a medias). */
    fun toUpload(c: Context) = all(c).filter {
        it.status == Recording.PENDING || it.status == Recording.ERROR || it.status == Recording.UPLOADING
    }

    /** Al arrancar: una grabación marcada "grabando" sin servicio activo se quedó cortada. */
    fun recoverInterrupted(c: Context) = synchronized(lock) {
        val list = all(c)
        var changed = false
        list.filter { it.status == Recording.RECORDING }.forEach {
            changed = true
            if (it.file.exists() && it.file.length() > 0) it.status = Recording.PENDING
            else { it.status = Recording.ERROR; it.error = "La grabación se interrumpió" }
        }
        if (changed) save(c, list)
    }
}
