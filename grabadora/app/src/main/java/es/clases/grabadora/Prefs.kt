package es.clases.grabadora

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/** Ajustes del usuario: horario y enlace de Drive. */
class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var scriptUrl: String
        get() = p.getString("scriptUrl", "") ?: ""
        set(v) = p.edit().putString("scriptUrl", v.trim()).apply()

    /** Siempre activo: la app graba sola todas las clases del calendario. */
    var scheduleEnabled: Boolean
        get() = true
        set(v) { }

    /** Días de la semana con constantes de Calendar (MONDAY=2 ... SUNDAY=1). */
    var days: Set<Int>
        get() = (p.getStringSet("days", null) ?: DEFAULT_DAYS).mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = p.edit().putStringSet("days", v.map { it.toString() }.toSet()).apply()

    var startHour: Int
        get() = p.getInt("startHour", 9)
        set(v) = p.edit().putInt("startHour", v).apply()

    var startMinute: Int
        get() = p.getInt("startMinute", 0)
        set(v) = p.edit().putInt("startMinute", v).apply()

    var durationMinutes: Int
        get() = p.getInt("durationMinutes", 120)
        set(v) = p.edit().putInt("durationMinutes", v).apply()

    var wifiOnly: Boolean
        get() = false
        set(v) { }

    var deleteAfterUpload: Boolean
        get() = p.getBoolean("deleteAfterUpload", false)
        set(v) = p.edit().putBoolean("deleteAfterUpload", v).apply()

    /** Usar el calendario del móvil (Google Calendar) en vez del horario fijo. */
    var useCalendar: Boolean
        get() = true
        set(v) { }

    /** Vacío = todos los calendarios visibles del móvil. */
    var calendarIds: Set<Long>
        get() = emptySet()
        set(v) { }

    /** Si no está vacío, solo se graban los eventos cuyo título contiene este texto. */
    /** Solo se graban los eventos con esta palabra en el título o la descripción. */
    var calendarFilter: String
        get() = "Grupo"
        set(v) { }

    /** La próxima clase programada (para saber qué grabar cuando suene la alarma). */
    var nextTitle: String
        get() = p.getString("nextTitle", "") ?: ""
        set(v) = p.edit().putString("nextTitle", v).apply()
    var nextBegin: Long
        get() = p.getLong("nextBegin", 0L)
        set(v) = p.edit().putLong("nextBegin", v).apply()
    var nextEnd: Long
        get() = p.getLong("nextEnd", 0L)
        set(v) = p.edit().putLong("nextEnd", v).apply()

    /** Momento (ms) en que debe pararse la grabación en curso; 0 si no hay. */
    var stopAt: Long
        get() = p.getLong("stopAt", 0L)
        set(v) = p.edit().putLong("stopAt", v).apply()

    companion object {
        val DEFAULT_DAYS = setOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY
        ).map { it.toString() }.toSet()
    }
}
