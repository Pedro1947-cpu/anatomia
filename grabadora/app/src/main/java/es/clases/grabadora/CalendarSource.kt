package es.clases.grabadora

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

data class ClassEvent(val title: String, val begin: Long, val end: Long)

/** Lee las clases del calendario del móvil (el de Google Calendar se sincroniza aquí). */
object CalendarSource {

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Calendarios del móvil: id → nombre (cuenta). */
    fun calendars(c: Context): List<Pair<Long, String>> {
        if (!hasPermission(c)) return emptyList()
        val out = mutableListOf<Pair<Long, String>>()
        c.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
            ),
            "${CalendarContract.Calendars.VISIBLE} = 1", null,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(1) ?: "(sin nombre)"
                val account = cur.getString(2) ?: ""
                out += cur.getLong(0) to (if (account.isNotEmpty() && account != name) "$name · $account" else name)
            }
        }
        return out
    }

    /** Primera clase que empieza después de [after] (busca 8 días hacia delante). */
    fun nextEvent(c: Context, after: Long): ClassEvent? {
        val p = Prefs(c)
        val ids = p.calendarIds
        if (!hasPermission(c) || ids.isEmpty()) return null
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, after)
            ContentUris.appendId(it, after + 8L * 24 * 3600 * 1000)
        }.build()
        val sel = "${CalendarContract.Instances.ALL_DAY} = 0 AND " +
            "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",")})"
        val filter = p.calendarFilter.lowercase()
        return try {
            c.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.STATUS,
                    CalendarContract.Instances.DESCRIPTION,
                ),
                sel, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val title = cur.getString(0)?.trim().orEmpty()
                    val begin = cur.getLong(1)
                    val end = cur.getLong(2)
                    val cancelled = !cur.isNull(3) && cur.getInt(3) == CalendarContract.Events.STATUS_CANCELED
                    if (begin <= after || end <= begin || cancelled) continue
                    val desc = cur.getString(4).orEmpty()
                    if (filter.isNotEmpty() && !(title + " " + desc).lowercase().contains(filter)) continue
                    return ClassEvent(title.ifEmpty { "Clase" }, begin, end)
                }
                null
            }
        } catch (e: SecurityException) {
            null
        }
    }
}

/** Se despierta cuando cambia el calendario y vuelve a programar la próxima clase. */
class CalendarWatchJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Scheduler.reschedule(this) // vuelve a programar la alarma y este mismo vigilante
        return false
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val JOB_ID = 43

        fun arm(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            val p = Prefs(c)
            if (!p.scheduleEnabled || !p.useCalendar || !CalendarSource.hasPermission(c)) {
                js.cancel(JOB_ID); return
            }
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, CalendarWatchJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .setTriggerContentUpdateDelay(10_000)
                .setTriggerContentMaxDelay(120_000)
                .build()
            js.schedule(job)
        }
    }
}
