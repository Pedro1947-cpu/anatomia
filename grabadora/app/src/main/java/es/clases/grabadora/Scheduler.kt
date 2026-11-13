package es.clases.grabadora

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** Programa las alarmas de inicio y fin de grabación. */
object Scheduler {
    const val ACTION_START = "es.clases.grabadora.START"
    const val ACTION_STOP = "es.clases.grabadora.STOP"

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun pi(c: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        c, code, Intent(c, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun canExact(c: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(c).canScheduleExactAlarms()

    /** Próximo inicio de clase a partir de [from], o null si no hay días marcados. */
    fun nextStart(c: Context, from: Long = System.currentTimeMillis()): Long? {
        val p = Prefs(c)
        if (p.days.isEmpty()) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, p.startHour)
            set(Calendar.MINUTE, p.startMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (cal.timeInMillis > from && cal.get(Calendar.DAY_OF_WEEK) in p.days) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /** Vuelve a calcular y programar la siguiente grabación automática. */
    fun reschedule(c: Context) {
        val p = Prefs(c)
        val start = pi(c, ACTION_START, 1)
        am(c).cancel(start)
        if (!p.scheduleEnabled) return
        val at = nextStart(c) ?: return
        val show = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        try {
            // setAlarmClock es lo más fiable: el sistema no la retrasa por ahorro de batería
            // y permite a la app arrancar el micrófono aunque esté en segundo plano.
            am(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), start)
        } catch (e: SecurityException) {
            am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, start)
        }
    }

    fun scheduleStop(c: Context, at: Long) {
        val stop = pi(c, ACTION_STOP, 2)
        try {
            am(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, stop)
        } catch (e: SecurityException) {
            am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, stop)
        }
    }

    fun cancelStop(c: Context) = am(c).cancel(pi(c, ACTION_STOP, 2))
}
