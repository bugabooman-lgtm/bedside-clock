package com.bedside.clock

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

val TONES = listOf(
    "chimes" to "Chimes",
    "birds" to "Birdsong",
    "marimba" to "Marimba",
    "classic" to "Classic",
    "system" to "Phone alarm"
)

/** days uses Calendar.SUNDAY(1)..Calendar.SATURDAY(7); empty = rings once. */
data class Alarm(
    val id: Long,
    var hour: Int,
    var minute: Int,
    var days: MutableSet<Int>,
    var label: String,
    var tone: String,
    var rampMin: Int,
    var sunriseMin: Int,
    var enabled: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("hour", hour); put("minute", minute)
        put("days", JSONArray(days.toList()))
        put("label", label); put("tone", tone)
        put("ramp", rampMin); put("sunrise", sunriseMin); put("enabled", enabled)
    }

    /** Next time (ms) this alarm should ring strictly after [from], or null. */
    fun nextTrigger(from: Long = System.currentTimeMillis()): Long? {
        val c = Calendar.getInstance()
        c.timeInMillis = from
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        for (i in 0..8) {
            if (c.timeInMillis > from && (days.isEmpty() || days.contains(c.get(Calendar.DAY_OF_WEEK)))) {
                return c.timeInMillis
            }
            c.add(Calendar.DAY_OF_YEAR, 1)
            c.set(Calendar.HOUR_OF_DAY, hour)
            c.set(Calendar.MINUTE, minute)
        }
        return null
    }

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val arr = o.optJSONArray("days") ?: JSONArray()
            val set = mutableSetOf<Int>()
            for (i in 0 until arr.length()) set.add(arr.getInt(i))
            return Alarm(
                o.getLong("id"), o.getInt("hour"), o.getInt("minute"), set,
                o.optString("label", ""), o.optString("tone", "chimes"),
                o.optInt("ramp", 3), o.optInt("sunrise", 20), o.optBoolean("enabled", true)
            )
        }

        fun newDefault(): Alarm = Alarm(
            System.currentTimeMillis(), 7, 0,
            mutableSetOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
            "", "chimes", 3, 20, true
        )
    }
}

/** Everything the app remembers, kept on the tablet. */
object Store {
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("bedside", Context.MODE_PRIVATE)

    fun alarms(c: Context): MutableList<Alarm> {
        return try {
            val arr = JSONArray(sp(c).getString("alarms", "[]") ?: "[]")
            MutableList(arr.length()) { Alarm.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveAlarms(c: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(c).edit().putString("alarms", arr.toString()).apply()
    }

    fun getInt(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun getLong(c: Context, k: String, d: Long) = sp(c).getLong(k, d)
    fun getFloat(c: Context, k: String, d: Float) = sp(c).getFloat(k, d)
    fun getBool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun getStr(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d

    fun put(c: Context, k: String, v: Any) {
        val e = sp(c).edit()
        when (v) {
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is Boolean -> e.putBoolean(k, v)
            is String -> e.putString(k, v)
        }
        e.apply()
    }

    // ---- named settings ----
    fun is24(c: Context) = getBool(c, "is24", false)
    fun snoozeMin(c: Context) = getInt(c, "snoozeMin", 9)
    fun volume(c: Context) = getFloat(c, "volume", 0.85f)
    fun nightOn(c: Context) = getBool(c, "nightOn", true)
    fun nightFrom(c: Context) = getInt(c, "nightFrom", 22 * 60)
    fun nightTo(c: Context) = getInt(c, "nightTo", 6 * 60 + 30)
    fun nightBright(c: Context) = getFloat(c, "nightBright", 0.02f)
    fun weatherName(c: Context) = getStr(c, "wName", "")
    fun useF(c: Context) = getBool(c, "useF", true)

    // ---- snooze ----
    fun snoozeId(c: Context) = getLong(c, "snoozeId", 0L)
    fun snoozeAt(c: Context) = getLong(c, "snoozeAt", 0L)
    fun setSnooze(c: Context, id: Long, at: Long) {
        sp(c).edit().putLong("snoozeId", id).putLong("snoozeAt", at).apply()
    }
    fun clearSnooze(c: Context) {
        sp(c).edit().putLong("snoozeId", 0L).putLong("snoozeAt", 0L).apply()
    }
}
