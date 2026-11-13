package es.clases.grabadora

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla mínima: solo la conexión con Drive y los permisos.
 * Todo lo demás es automático: graba cada clase del calendario y la sube a Drive.
 */
class MainActivity : Activity() {

    companion object {
        const val ACTION_RECORD_NOW = "es.clases.grabadora.RECORD_NOW"
    }

    private lateinit var prefs: Prefs
    private val es = Locale("es", "ES")
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private val tvStatus by lazy { findViewById<TextView>(R.id.tvStatus) }
    private val tvDrive by lazy { findViewById<TextView>(R.id.tvDrive) }
    private val tvPerms by lazy { findViewById<TextView>(R.id.tvPerms) }
    private val btnPerms by lazy { findViewById<Button>(R.id.btnPerms) }
    private val btnUpdate by lazy { findViewById<Button>(R.id.btnUpdate) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Notifs.createChannels(this)
        if (!RecorderService.isRecording) Store.recoverInterrupted(this)

        findViewById<Button>(R.id.btnDrive).setOnClickListener { askScriptUrl() }
        btnPerms.setOnClickListener { requestNextPermission() }
        btnUpdate.setOnClickListener { pendingUpdate?.let { doUpdate(it) } }

        handleIntent(intent)
        checkUpdate()
        if (prefs.scriptUrl.isNotBlank()) testConnection()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Aviso "Es la hora de clase" tocado: Android no dejó empezar sola, empezamos ahora. */
    private fun handleIntent(i: Intent?) {
        if (i?.action == ACTION_RECORD_NOW && !RecorderService.isRecording) {
            i.action = Intent.ACTION_MAIN
            if (has(Manifest.permission.RECORD_AUDIO)) {
                val minutes = i.getIntExtra("minutes", 0).takeIf { it > 0 } ?: 60
                RecorderService.start(this, minutes, scheduled = false, title = i.getStringExtra("title") ?: "")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RecorderService.listener = { runOnUiThread { refreshStatus() } }
        Scheduler.reschedule(this)
        refreshStatus()
        refreshPermissions()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        RecorderService.listener = null
        handler.removeCallbacks(ticker)
    }

    // ---------- Estado ----------

    private fun refreshStatus() {
        val lines = mutableListOf<String>()
        if (RecorderService.isRecording) {
            val secs = (System.currentTimeMillis() - RecorderService.startedAt) / 1000
            lines += "● Grabando  %d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
        }
        val next = if (CalendarSource.hasPermission(this)) Scheduler.next(this) else null
        lines += if (next != null) {
            "Próxima: ${next.title}\n" +
                SimpleDateFormat("EEEE d, HH:mm", es).format(Date(next.begin)) + "–" +
                SimpleDateFormat("HH:mm", es).format(Date(next.end))
        } else if (!CalendarSource.hasPermission(this)) {
            "Falta el permiso del calendario."
        } else {
            "No veo clases en los próximos 8 días. " + CalendarSource.diagnose(this)
        }
        val pending = Store.toUpload(this).size
        if (pending > 0) lines += "$pending grabación(es) pendientes de subir"
        tvStatus.text = lines.joinToString("\n\n")
    }

    // ---------- Drive ----------

    private fun askScriptUrl() {
        val input = EditText(this).apply {
            hint = "https://script.google.com/macros/s/…/exec"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.scriptUrl)
        }
        AlertDialog.Builder(this)
            .setTitle("Enlace del script de Drive")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.scriptUrl = input.text.toString().trim()
                testConnection()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun testConnection() {
        val url = prefs.scriptUrl
        if (url.isBlank()) {
            tvDrive.text = "Sin conectar. Pega el enlace de tu script."
            return
        }
        tvDrive.text = "Comprobando…"
        Thread {
            val msg = try {
                val r = Uploader.callScript(url, JSONObject().put("action", "ping"))
                "✓ Conectado a «${r.optString("folder")}»"
            } catch (e: Exception) {
                "✗ No funciona: ${e.message}"
            }
            runOnUiThread {
                tvDrive.text = msg
                if (msg.startsWith("✓")) UploadJob.schedule(this)
            }
        }.start()
    }

    // ---------- Actualizaciones ----------

    private var pendingUpdate: Updater.Update? = null

    private fun checkUpdate() {
        Thread {
            val u = try { Updater.check(this) } catch (e: Exception) { null }
            runOnUiThread {
                pendingUpdate = u
                if (u != null) {
                    btnUpdate.text = "⬇  Actualizar a la versión ${u.name}"
                    btnUpdate.visibility = View.VISIBLE
                } else {
                    btnUpdate.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun doUpdate(u: Updater.Update) {
        if (RecorderService.isRecording) {
            Toast.makeText(this, "Espera a que termine la grabación para actualizar", Toast.LENGTH_LONG).show()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setMessage("Para actualizarse sola, permite a la Grabadora instalar apps. Activa el interruptor y vuelve atrás.")
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Ahora no", null).show()
            return
        }
        btnUpdate.isEnabled = false
        Thread {
            try {
                val apk = Updater.download(this, u) { p -> runOnUiThread { btnUpdate.text = "Descargando… $p%" } }
                runOnUiThread { btnUpdate.text = "Instalando…" }
                Updater.install(this, apk)
            } catch (e: Exception) {
                runOnUiThread {
                    btnUpdate.isEnabled = true
                    btnUpdate.text = "⬇  Actualizar a la versión ${u.name}"
                    Toast.makeText(this, "No se pudo descargar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- Permisos ----------

    private data class Perm(val label: String, val ok: Boolean, val ask: () -> Unit)

    private fun perms(): List<Perm> {
        val list = mutableListOf<Perm>()
        list += Perm("Micrófono", has(Manifest.permission.RECORD_AUDIO)) { requestPermissions(runtimeMissing(), 1) }
        list += Perm("Calendario", has(Manifest.permission.READ_CALENDAR)) { requestPermissions(runtimeMissing(), 1) }
        if (Build.VERSION.SDK_INT >= 33) {
            list += Perm("Notificaciones", has(Manifest.permission.POST_NOTIFICATIONS)) { requestPermissions(runtimeMissing(), 1) }
        }
        list += Perm("Alarmas exactas", Scheduler.canExact(this)) {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        list += Perm("Batería sin restricciones", pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        return list
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun runtimeMissing(): Array<String> {
        val l = mutableListOf<String>()
        if (!has(Manifest.permission.RECORD_AUDIO)) l += Manifest.permission.RECORD_AUDIO
        if (!has(Manifest.permission.READ_CALENDAR)) l += Manifest.permission.READ_CALENDAR
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) l += Manifest.permission.POST_NOTIFICATIONS
        return l.toTypedArray()
    }

    private fun refreshPermissions() {
        val all = perms()
        tvPerms.text = all.joinToString("\n") { (if (it.ok) "✓  " else "✗  ") + it.label }
        val missing = all.count { !it.ok }
        btnPerms.visibility = if (missing == 0) View.GONE else View.VISIBLE
    }

    private fun requestNextPermission() {
        val runtime = runtimeMissing()
        if (runtime.isNotEmpty()) { requestPermissions(runtime, 1); return }
        perms().firstOrNull { !it.ok }?.ask?.invoke()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        refreshPermissions()
        Scheduler.reschedule(this)
        refreshStatus()
        if (permissions.any { !has(it) && !shouldShowRequestPermissionRationale(it) }) {
            AlertDialog.Builder(this)
                .setMessage("Android ya no deja volver a preguntar. Actívalo en Ajustes → Permisos.")
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Ahora no", null).show()
        }
    }
}
