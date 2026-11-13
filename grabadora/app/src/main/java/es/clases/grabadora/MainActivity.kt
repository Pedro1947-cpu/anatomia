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
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
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
    private var wasRecording = false
    private val ticker = object : Runnable {
        override fun run() {
            if (RecorderService.isRecording || wasRecording) refreshStatus()
            wasRecording = RecorderService.isRecording
            handler.postDelayed(this, 1000)
        }
    }

    private val card by lazy { findViewById<LinearLayout>(R.id.card) }
    private val cardDot by lazy { findViewById<View>(R.id.cardDot) }
    private val cardLabel by lazy { findViewById<TextView>(R.id.cardLabel) }
    private val cardTitle by lazy { findViewById<TextView>(R.id.cardTitle) }
    private val cardTimer by lazy { findViewById<TextView>(R.id.cardTimer) }
    private val cardSub by lazy { findViewById<TextView>(R.id.cardSub) }
    private val driveRows by lazy { findViewById<LinearLayout>(R.id.driveRows) }
    private val permRows by lazy { findViewById<LinearLayout>(R.id.permRows) }
    private val btnUpdate by lazy { findViewById<TextView>(R.id.btnUpdate) }
    private var driveState = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Notifs.createChannels(this)
        if (!RecorderService.isRecording) Store.recoverInterrupted(this)

        btnUpdate.setOnClickListener { pendingUpdate?.let { doUpdate(it) } }

        handleIntent(intent)
        checkUpdate()
        testConnection()
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
        refreshDrive()
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
        val hm = SimpleDateFormat("HH:mm", es)
        if (RecorderService.isRecording) {
            card.setBackgroundResource(R.drawable.card_filled)
            cardDot.visibility = View.VISIBLE
            cardLabel.text = "Grabando"
            cardLabel.setTextColor(getColor(R.color.on_fg_muted))
            cardTitle.setTextColor(getColor(R.color.on_fg))
            cardSub.setTextColor(getColor(R.color.on_fg_muted))
            cardTitle.text = RecorderService.currentTitle.ifEmpty { "Clase" }
            val secs = (System.currentTimeMillis() - RecorderService.startedAt) / 1000
            cardTimer.visibility = View.VISIBLE
            cardTimer.text = "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
            val stopAt = prefs.stopAt
            cardSub.text = if (stopAt > 0) "Termina a las " + hm.format(Date(stopAt)) else ""
            return
        }
        card.setBackgroundResource(R.drawable.card_outline)
        cardDot.visibility = View.GONE
        cardTimer.visibility = View.GONE
        cardLabel.text = "Próxima clase"
        cardLabel.setTextColor(getColor(R.color.muted))
        cardTitle.setTextColor(getColor(R.color.fg))
        cardSub.setTextColor(getColor(R.color.fg))
        val next = if (CalendarSource.hasPermission(this)) Scheduler.next(this) else null
        if (next != null) {
            cardTitle.text = next.title
            val day = SimpleDateFormat("EEEE d", es).format(Date(next.begin)).replaceFirstChar { it.uppercase() }
            cardSub.text = "$day · ${hm.format(Date(next.begin))} – ${hm.format(Date(next.end))}"
        } else if (!CalendarSource.hasPermission(this)) {
            cardTitle.text = "Sin calendario"
            cardSub.text = "Falta el permiso del calendario."
        } else {
            cardTitle.text = "Sin clases a la vista"
            cardSub.text = CalendarSource.diagnose(this)
        }
    }

    // ---------- Filas ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Una fila: texto a la izquierda; a la derecha ✓ o una pastilla con acción. */
    private fun row(parent: LinearLayout, text: String, ok: Boolean?, action: String? = null,
                    muted: Boolean = false, onClick: (() -> Unit)? = null) {
        if (parent.childCount > 0) {
            parent.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.divider))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(0, dp(8), 0, dp(8))
            if (onClick != null) setOnClickListener { onClick() }
        }
        r.addView(TextView(this).apply {
            this.text = text
            textSize = if (muted) 13f else 15f
            setTextColor(getColor(if (muted) R.color.muted else R.color.fg))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (ok == true) {
            r.addView(TextView(this).apply {
                this.text = "✓"; textSize = 16f; setTextColor(getColor(R.color.fg))
            })
        } else if (action != null) {
            r.addView(TextView(this).apply {
                this.text = action
                textSize = 13f
                setTextColor(getColor(R.color.fg))
                setBackgroundResource(R.drawable.pill)
                setPadding(dp(14), dp(5), dp(14), dp(5))
                if (onClick != null) setOnClickListener { onClick() }
            })
        }
        parent.addView(r)
    }

    private fun refreshDrive() {
        driveRows.removeAllViews()
        when {
            prefs.scriptUrl.isBlank() -> row(driveRows, "Sin conectar", false, "Conectar") { askScriptUrl() }
            driveState.startsWith("✓") -> row(driveRows, driveState.removePrefix("✓"), true) { askScriptUrl() }
            driveState.isEmpty() -> row(driveRows, "Comprobando…", null, muted = true)
            else -> row(driveRows, driveState, false, "Cambiar") { askScriptUrl() }
        }
        val pending = Store.toUpload(this).size
        if (pending > 0) {
            row(driveRows, if (pending == 1) "1 grabación por subir" else "$pending grabaciones por subir",
                null, muted = true)
        }
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
        driveState = ""
        refreshDrive()
        if (url.isBlank()) return
        Thread {
            val msg = try {
                val r = Uploader.callScript(url, JSONObject().put("action", "ping"))
                "✓" + r.optString("folder").trim()
            } catch (e: Exception) {
                "No conecta: ${e.message}"
            }
            runOnUiThread {
                driveState = msg
                refreshDrive()
                if (msg.startsWith("✓")) UploadJob.schedule(this)
            }
        }.start()
    }

    // ---------- Actualizaciones ----------

    private var pendingUpdate: Updater.Update? = null

    /** Al abrir la app: si hay versión nueva, se descarga e instala sola. */
    private fun checkUpdate() {
        UpdateJob.schedule(this)
        Thread {
            val u = try { Updater.check(this) } catch (e: Exception) { null }
            runOnUiThread {
                pendingUpdate = u
                if (u != null) {
                    btnUpdate.text = "Actualizar a la versión ${u.name}"
                    btnUpdate.visibility = View.VISIBLE
                    if (!RecorderService.isRecording && packageManager.canRequestPackageInstalls()) doUpdate(u)
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
                    btnUpdate.text = "Actualizar a la versión ${u.name}"
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
        permRows.removeAllViews()
        val all = perms()
        if (all.all { it.ok }) {
            row(permRows, "Todo listo", true)
        } else {
            all.forEach { p -> row(permRows, p.label, p.ok, if (p.ok) null else "Activar") { if (!p.ok) p.ask() } }
        }
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
