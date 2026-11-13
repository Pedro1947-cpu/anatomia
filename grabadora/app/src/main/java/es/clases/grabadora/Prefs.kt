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

    var scheduleEnabled: Boolean
        get() = p.getBoolean("scheduleEnabled", false)
        set(v) = p.edit().putBoolean("scheduleEnabled", v).apply()

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
        get() = p.getBoolean("wifiOnly", false)
        set(v) = p.edit().putBoolean("wifiOnly", v).apply()

    var deleteAfterUpload: Boolean
        get() = p.getBoolean("deleteAfterUpload", false)
        set(v) = p.edit().putBoolean("deleteAfterUpload", v).apply()

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
