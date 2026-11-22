package es.clases.grabadora

import android.app.Application
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro sencillo de lo que hace la app, visible en la pantalla para diagnosticar. */
object Log {
    private const val MAX = 150
    @Volatile private var dir: File? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale("es", "ES"))

    fun init(c: Context) { dir = c.applicationContext.filesDir }

    @Synchronized fun add(msg: String) {
        val d = dir ?: return
        try {
            val f = File(d, "registro.txt")
            val lines = (if (f.exists()) f.readLines() else emptyList()) + (fmt.format(Date()) + "  " + msg)
            f.writeText(lines.takeLast(MAX).joinToString("\n") + "\n")
        } catch (_: Exception) { }
    }

    fun last(n: Int): List<String> {
        val d = dir ?: return emptyList()
        return try {
            File(d, "registro.txt").takeIf { it.exists() }?.readLines()?.takeLast(n)?.reversed() ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.add("FALLO: " + e.javaClass.simpleName + ": " + (e.message ?: "") +
                " @ " + (e.stackTrace.firstOrNull { it.className.startsWith("es.clases") } ?: e.stackTrace.firstOrNull()))
            prev?.uncaughtException(t, e)
        }
    }
}
