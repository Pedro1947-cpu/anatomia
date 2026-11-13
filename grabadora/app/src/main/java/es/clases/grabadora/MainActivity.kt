package es.clases.grabadora

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val ACTION_RECORD_NOW = "es.clases.grabadora.RECORD_NOW"
        private val DURATIONS = listOf(30, 45, 60, 90, 120, 150, 180, 210, 240, 300, 360)
        private val DAYS = listOf(
            Calendar.MONDAY to "L", Calendar.TUESDAY to "M", Calendar.WEDNESDAY to "X",
            Calendar.THURSDAY to "J", Calendar.FRIDAY to "V", Calendar.SATURDAY to "S",
            Calendar.SUNDAY to "D",
        )
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
    private val btnRecord by lazy { findViewById<Button>(R.id.btnRecord) }
    private val swSchedule by lazy { findViewById<Switch>(R.id.swSchedule) }
    private val btnStart by lazy { findViewById<Button>(R.id.btnStart) }
    private val btnDuration by lazy { findViewById<Button>(R.id.btnDuration) }
    private val tvNext by lazy { findViewById<TextView>(R.id.tvNext) }
    private val swCalendar by lazy { findViewById<Switch>(R.id.swCalendar) }
    private val btnCalendars by lazy { findViewById<Button>(R.id.btnCalendars) }
    private val btnFilter by lazy { findViewById<Button>(R.id.btnFilter) }
    private val tvDrive by lazy { findViewById<TextView>(R.id.tvDrive) }
    private val tvPerms by lazy { findViewById<TextView>(R.id.tvPerms) }
    private val btnPerms by lazy { findViewById<Button>(R.id.btnPerms) }
    private val listRecs by lazy { findViewById<LinearLayout>(R.id.listRecs) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Notifs.createChannels(this)
        if (!RecorderService.isRecording) Store.recoverInterrupted(this)

        btnRecord.setOnClickListener { toggleRecording() }

        swSchedule.isChecked = prefs.scheduleEnabled
        swSchedule.setOnCheckedChangeListener { _, on ->
            prefs.scheduleEnabled = on
            Scheduler.reschedule(this)
            refreshSchedule()
            if (on && missingPermissions().isNotEmpty()) {
                Toast.makeText(this, "Revisa los permisos de abajo para que funcione bien", Toast.LENGTH_LONG).show()
            }
        }
        buildDayToggles()
        swCalendar.isChecked = prefs.useCalendar
        swCalendar.setOnCheckedChangeListener { _, on ->
            prefs.useCalendar = on
            if (on && !CalendarSource.hasPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2)
            } else if (on && prefs.calendarIds.isEmpty()) {
                chooseCalendars()
            }
            Scheduler.reschedule(this); refreshSchedule(); refreshPermissions()
        }
        btnCalendars.setOnClickListener {
            if (!CalendarSource.hasPermission(this)) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2)
            else chooseCalendars()
        }
        btnFilter.setOnClickListener { askFilter() }
        btnStart.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                prefs.startHour = h; prefs.startMinute = m
                Scheduler.reschedule(this); refreshSchedule()
            }, prefs.startHour, prefs.startMinute, true).show()
        }
        btnDuration.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Cuánto dura la grabación?")
                .setItems(DURATIONS.map { durationText(it) }.toTypedArray()) { _, i ->
                    prefs.durationMinutes = DURATIONS[i]; refreshSchedule()
                }.show()
        }

        findViewById<Button>(R.id.btnDrive).setOnClickListener { askScriptUrl() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }
        findViewById<CheckBox>(R.id.cbWifi).apply {
            isChecked = prefs.wifiOnly
            setOnCheckedChangeListener { _, v -> prefs.wifiOnly = v }
        }
        findViewById<CheckBox>(R.id.cbDelete).apply {
            isChecked = prefs.deleteAfterUpload
            setOnCheckedChangeListener { _, v -> prefs.deleteAfterUpload = v }
        }
        btnPerms.setOnClickListener { requestNextPermission() }
        findViewById<Button>(R.id.btnRetry).setOnClickListener { uploadNow() }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == ACTION_RECORD_NOW && !RecorderService.isRecording) {
            i.action = Intent.ACTION_MAIN
            val minutes = i.getIntExtra("minutes", 0).takeIf { it > 0 } ?: prefs.durationMinutes
            startRecording(minutes, i.getStringExtra("title") ?: "")
        }
    }

    override fun onResume() {
        super.onResume()
        RecorderService.listener = { runOnUiThread { refreshAll() } }
        Scheduler.reschedule(this)
        refreshAll()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        RecorderService.listener = null
        handler.removeCallbacks(ticker)
    }

    private fun refreshAll() {
        refreshStatus()
        refreshSchedule()
        refreshDrive()
        refreshPermissions()
        refreshList()
    }

    // ---------- Grabar ----------

    private fun toggleRecording() {
        if (RecorderService.isRecording) RecorderService.stop(this)
        else startRecording(0)
    }

    private fun startRecording(minutes: Int, title: String = "") {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Primero dale permiso al micrófono", Toast.LENGTH_LONG).show()
            requestNextPermission()
            return
        }
        RecorderService.start(this, minutes, scheduled = false, title = title)
        handler.postDelayed({ refreshAll() }, 600)
    }

    private fun refreshStatus() {
        if (RecorderService.isRecording) {
            val secs = (System.currentTimeMillis() - RecorderService.startedAt) / 1000
            val stopAt = prefs.stopAt
            val until = if (stopAt > 0) "\nHasta las " + hhmm(stopAt) else ""
            tvStatus.text = "● Grabando  %d:%02d:%02d%s".format(secs / 3600, secs / 60 % 60, secs % 60, until)
            btnRecord.text = "■  Parar"
            btnRecord.backgroundTintList = getColorStateList(R.color.stop)
        } else {
            tvStatus.text = "No se está grabando"
            btnRecord.text = "●  Grabar ahora"
            btnRecord.backgroundTintList = getColorStateList(R.color.rec)
        }
    }

    // ---------- Horario ----------

    private fun buildDayToggles() {
        val row = findViewById<LinearLayout>(R.id.daysRow)
        row.removeAllViews()
        for ((day, letter) in DAYS) {
            val cb = CheckBox(this).apply {
                text = letter
                isChecked = day in prefs.days
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnCheckedChangeListener { _, on ->
                    prefs.days = if (on) prefs.days + day else prefs.days - day
                    Scheduler.reschedule(this@MainActivity)
                    refreshSchedule()
                }
            }
            row.addView(cb)
        }
    }

    private fun refreshSchedule() {
        val cal = prefs.useCalendar
        findViewById<View>(R.id.fixedGroup).visibility = if (cal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.calGroup).visibility = if (cal) View.VISIBLE else View.GONE
        btnStart.text = "Empieza: " + "%02d:%02d".format(prefs.startHour, prefs.startMinute)
        btnDuration.text = "Dura: " + durationText(prefs.durationMinutes)
        val names = CalendarSource.calendars(this).filter { it.first in prefs.calendarIds }.map { it.second }
        btnCalendars.text = if (names.isEmpty()) "Elegir calendario" else "Calendario: " + names.joinToString(", ")
        btnFilter.text = if (prefs.calendarFilter.isEmpty()) "Grabar todos los eventos de ese calendario"
        else "Solo eventos que contienen «${prefs.calendarFilter}»"
        tvNext.text = when {
            !prefs.scheduleEnabled -> "El horario automático está apagado."
            cal && !CalendarSource.hasPermission(this) -> "Falta el permiso para leer el calendario."
            cal && prefs.calendarIds.isEmpty() -> "Elige qué calendario tiene tus clases."
            !cal && prefs.days.isEmpty() -> "Marca al menos un día."
            else -> {
                val next = Scheduler.next(this)
                if (next == null) {
                    if (cal) "No hay clases en los próximos 8 días de ese calendario."
                    else "No hay ninguna grabación programada."
                } else {
                    val subject = if (next.title.isNotEmpty()) next.title + "\n" else ""
                    "Próxima grabación: " + subject +
                        SimpleDateFormat("EEEE d 'de' MMMM, HH:mm", es).format(Date(next.begin)) +
                        " – " + hhmm(next.end)
                }
            }
        }
    }

    private fun chooseCalendars() {
        val all = CalendarSource.calendars(this)
        if (all.isEmpty()) {
            Toast.makeText(this, "No encuentro calendarios en el móvil. ¿Está la app de Google Calendar con tu cuenta?", Toast.LENGTH_LONG).show()
            return
        }
        val checked = all.map { it.first in prefs.calendarIds }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("¿Qué calendario tiene tus clases?")
            .setMultiChoiceItems(all.map { it.second }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Guardar") { _, _ ->
                prefs.calendarIds = all.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                Scheduler.reschedule(this); refreshSchedule()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun askFilter() {
        val input = EditText(this).apply {
            hint = "Por ejemplo: Clase"
            setText(prefs.calendarFilter)
        }
        AlertDialog.Builder(this)
            .setTitle("Filtrar eventos (opcional)")
            .setMessage("Si en ese calendario también hay cosas que no son clases, escribe una palabra que tengan solo las clases. Déjalo vacío para grabar todos los eventos.")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.calendarFilter = input.text.toString()
                Scheduler.reschedule(this); refreshSchedule()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun durationText(m: Int) = when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }

    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", es).format(Date(t))

    // ---------- Drive ----------

    private fun refreshDrive() {
        tvDrive.text = if (prefs.scriptUrl.isBlank())
            "Sin conectar. Sigue las instrucciones para crear tu enlace y pégalo aquí."
        else "Enlace guardado. Pulsa «Probar conexión» para comprobarlo."
    }

    private fun askScriptUrl() {
        val input = EditText(this).apply {
            hint = "https://script.google.com/macros/s/…/exec"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.scriptUrl)
        }
        AlertDialog.Builder(this)
            .setTitle("Enlace del script de Drive")
            .setMessage("Pega aquí la URL de la aplicación web que te dio Google al implementar el script.")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty() && !url.startsWith("https://script.google.com/")) {
                    Toast.makeText(this, "Ese enlace no parece de script.google.com", Toast.LENGTH_LONG).show()
                }
                prefs.scriptUrl = url
                refreshDrive()
                if (url.isNotEmpty()) testConnection()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun testConnection() {
        val url = prefs.scriptUrl
        if (url.isBlank()) { askScriptUrl(); return }
        tvDrive.text = "Probando…"
        Thread {
            val msg = try {
                val r = Uploader.callScript(url, JSONObject().put("action", "ping"))
                "✓ Conectado. Las grabaciones irán a la carpeta «${r.optString("folder")}» de tu Drive."
            } catch (e: Exception) {
                "✗ No funciona: ${e.message}"
            }
            runOnUiThread {
                tvDrive.text = msg
                if (msg.startsWith("✓")) UploadJob.schedule(this)
            }
        }.start()
    }

    private fun uploadNow() {
        if (prefs.scriptUrl.isBlank()) { askScriptUrl(); return }
        if (Store.toUpload(this).isEmpty()) {
            Toast.makeText(this, "No hay nada pendiente", Toast.LENGTH_SHORT).show(); return
        }
        Toast.makeText(this, "Subiendo…", Toast.LENGTH_SHORT).show()
        Thread {
            Uploader.uploadAll(this) { runOnUiThread { refreshList() } }
            runOnUiThread { refreshList() }
            UploadJob.schedule(this)
        }.start()
    }

    // ---------- Permisos ----------

    private data class Perm(val label: String, val ok: Boolean, val ask: () -> Unit)

    private fun perms(): List<Perm> {
        val list = mutableListOf<Perm>()
        list += Perm("Micrófono", has(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(runtimeMissing(), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list += Perm("Notificaciones", has(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(runtimeMissing(), 1)
            }
        }
        list += Perm("Alarmas exactas (para empezar a la hora)", Scheduler.canExact(this)) {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        if (prefs.useCalendar) {
            list += Perm("Leer el calendario", CalendarSource.hasPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2)
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        list += Perm("Sin restricciones de batería", pm.isIgnoringBatteryOptimizations(packageName)) {
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
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) l += Manifest.permission.POST_NOTIFICATIONS
        return l.toTypedArray()
    }

    private fun missingPermissions() = perms().filter { !it.ok }

    private fun refreshPermissions() {
        val all = perms()
        tvPerms.text = all.joinToString("\n") { (if (it.ok) "✓  " else "✗  ") + it.label }
        val missing = all.count { !it.ok }
        btnPerms.visibility = if (missing == 0) View.GONE else View.VISIBLE
        btnPerms.text = if (missing == 1) "Dar el permiso que falta" else "Dar los permisos que faltan ($missing)"
    }

    private fun requestNextPermission() {
        val runtime = runtimeMissing()
        if (runtime.isNotEmpty()) { requestPermissions(runtime, 1); return }
        missingPermissions().firstOrNull()?.ask?.invoke()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        refreshPermissions()
        if (code == 2) {
            if (CalendarSource.hasPermission(this) && prefs.calendarIds.isEmpty()) chooseCalendars()
            Scheduler.reschedule(this); refreshSchedule()
            return
        }
        if (runtimeMissing().isNotEmpty() && permissions.any { !shouldShowRequestPermissionRationale(it) && !has(it) }) {
            // Denegado para siempre: hay que ir a Ajustes
            AlertDialog.Builder(this)
                .setMessage("Android ya no deja volver a preguntar. Ábrelo en Ajustes → Permisos y actívalo.")
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Ahora no", null).show()
        }
    }

    // ---------- Lista ----------

    private fun refreshList() {
        listRecs.removeAllViews()
        val recs = Store.all(this).sortedByDescending { it.createdAt }.take(40)
        if (recs.isEmpty()) {
            listRecs.addView(TextView(this).apply {
                text = "Todavía no hay grabaciones."
                setPadding(0, 16, 0, 16)
            })
            return
        }
        val pad = (12 * resources.displayMetrics.density).toInt()
        for (r in recs) {
            val (icon, state) = when (r.status) {
                Recording.RECORDING -> "●" to "Grabando…"
                Recording.DONE -> "✓" to "En Drive"
                Recording.UPLOADING -> "↑" to "Subiendo…"
                Recording.ERROR -> "⚠" to r.error.ifBlank { "Error al subir; se reintentará" }
                else -> "…" to "Pendiente de subir"
            }
            val sizeMb = if (r.file.exists()) " · %.1f MB".format(es, r.file.length() / 1048576.0) else ""
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad, 0, pad)
            }
            row.addView(TextView(this).apply {
                text = "$icon  ${r.name.removeSuffix(".m4a")}"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            })
            row.addView(TextView(this).apply {
                text = state + sizeMb
                textSize = 13f
                gravity = Gravity.START
            })
            if (r.status == Recording.DONE && r.driveLink.isNotEmpty()) {
                row.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.driveLink)))
                }
            }
            listRecs.addView(row)
        }
    }
}
