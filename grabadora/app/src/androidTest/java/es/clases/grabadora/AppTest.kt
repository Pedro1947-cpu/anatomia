package es.clases.grabadora

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.TimeZone

/**
 * Pruebas en un Android de verdad (emulador): calendario, programación y grabación.
 */
@RunWith(AndroidJUnit4::class)
class AppTest {

    @get:Rule
    val perms: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val c: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var calId = 0L
    private val hour = 3600_000L

    private fun asSyncAdapter(uri: Uri) = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "prueba@ejemplo.com")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    @Before
    fun setUp() {
        val v = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, "prueba@ejemplo.com")
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "prueba@ejemplo.com")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "prueba@ejemplo.com")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, "prueba@ejemplo.com")
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "Europe/Madrid")
        }
        val uri = c.contentResolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), v)!!
        calId = uri.lastPathSegment!!.toLong()
        Prefs(c).apply {
            scheduleEnabled = true
            useCalendar = true
            calendarIds = setOf(calId)
            calendarFilter = ""
        }
    }

    @After
    fun tearDown() {
        c.contentResolver.delete(
            asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
            "${CalendarContract.Calendars._ID} = ?", arrayOf(calId.toString())
        )
        Prefs(c).scheduleEnabled = false
        Scheduler.reschedule(c)
    }

    private fun event(title: String, desc: String, begin: Long, end: Long, rrule: String? = null, allDay: Boolean = false) {
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, desc)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
            if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
            if (rrule != null) {
                put(CalendarContract.Events.RRULE, rrule)
                put(CalendarContract.Events.DURATION, "PT${(end - begin) / 60000}M")
            } else {
                put(CalendarContract.Events.DTEND, end)
            }
        }
        assertNotNull(c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v))
    }

    private fun roundedNow() = System.currentTimeMillis() / 60000 * 60000

    @Test
    fun encuentraLaClaseYAplicaElFiltro() {
        val now = roundedNow()
        // Como en el calendario real: una frase diaria, el bus, una clase con "Grupo" y un día entero.
        event("\"Llega a ser el que eres.\"", "Frase", now + 1 * hour, now + 1 * hour + 300_000, rrule = "FREQ=DAILY")
        event("🚌 Sal del aula", "Bizkaibus", now + 2 * hour, now + 2 * hour + 1800_000)
        event("Bioquímica Básica", "Grupo T02 · Aula P2", now + 3 * hour, now + 4 * hour)
        event("Festivo", "", now - now % (24 * hour) + 24 * hour, now - now % (24 * hour) + 48 * hour, allDay = true)

        val first = CalendarSource.nextEvent(c, now)
        assertNotNull("Sin filtro debería encontrar algo. " + CalendarSource.diagnose(c), first)
        assertEquals("\"Llega a ser el que eres.\"", first!!.title)

        Prefs(c).calendarFilter = "grupo"
        val clase = CalendarSource.nextEvent(c, now)
        assertNotNull("Con filtro «grupo» debería encontrar la clase. " + CalendarSource.diagnose(c), clase)
        assertEquals("Bioquímica Básica", clase!!.title)
        assertEquals(now + 3 * hour, clase.begin)
        assertEquals(now + 4 * hour, clase.end)

        // La programación guarda la próxima clase para la alarma.
        Scheduler.reschedule(c)
        assertEquals("Bioquímica Básica", Prefs(c).nextTitle)
        assertEquals(now + 3 * hour, Prefs(c).nextBegin)

        // Después de que empiece, la siguiente ya no es esa misma.
        assertNull(CalendarSource.nextEvent(c, now + 3 * hour))
    }

    @Test
    fun clasesSeguidasYAlarma() {
        val now = roundedNow()
        Prefs(c).calendarFilter = "Grupo"
        event("Bioquímica Básica", "Grupo T02", now + 1 * hour, now + 2 * hour)
        event("Anatomía Humana I", "Grupo T02", now + 2 * hour, now + 3 * hour)
        val a = CalendarSource.nextEvent(c, now)!!
        assertEquals("Bioquímica Básica", a.title)
        val b = CalendarSource.nextEvent(c, a.begin)!!
        assertEquals("Anatomía Humana I", b.title)
    }

    @Test
    fun grabaYGuardaElArchivo() {
        val before = Store.all(c).size
        RecorderService.start(c, 5, scheduled = true, title = "Prueba Anatomía")
        waitFor(10_000) { RecorderService.isRecording }
        assertTrue("No empezó a grabar", RecorderService.isRecording)
        Thread.sleep(4_000)
        RecorderService.stop(c)
        waitFor(10_000) { !RecorderService.isRecording }
        val rec = Store.all(c).last()
        assertEquals(before + 1, Store.all(c).size)
        assertEquals("Prueba Anatomía", rec.title)
        assertTrue("Nombre: ${rec.name}", rec.name.startsWith("Prueba Anatomía "))
        assertTrue("El archivo está vacío", File(rec.path).length() > 1000)
        assertTrue("Estado: ${rec.status}", rec.status != Recording.RECORDING)
    }

    @Test
    fun laAlarmaEmpiezaLaGrabacion() {
        val now = System.currentTimeMillis()
        Prefs(c).apply {
            nextTitle = "Clase de alarma"
            nextBegin = now
            nextEnd = now + 60_000
        }
        c.sendBroadcast(Intent(c, AlarmReceiver::class.java).setAction(Scheduler.ACTION_START))
        waitFor(10_000) { RecorderService.isRecording }
        assertTrue("La alarma no empezó a grabar", RecorderService.isRecording)
        RecorderService.stop(c)
        waitFor(10_000) { !RecorderService.isRecording }
        assertEquals("Clase de alarma", Store.all(c).last().title)
    }

    private fun waitFor(ms: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(200)
    }
}
