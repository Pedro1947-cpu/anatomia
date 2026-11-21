package es.clases.grabadora

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Deja que otra app (Drive, WhatsApp…) lea una grabación para enviarla a mano. */
class AudioProvider : ContentProvider() {
    companion object {
        fun uri(c: android.content.Context, f: File): Uri =
            Uri.parse("content://${c.packageName}.audio/" + Uri.encode(f.name))
    }

    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        val f = File(File(context!!.getExternalFilesDir(null), "Grabaciones"), name)
        return if (f.isFile) f else null
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "audio/mp4"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        file(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
