package com.bedside.clock

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Sunrise and sunset worked out on the tablet (NOAA method, accurate to a minute or two). No internet needed. */
object Sun {
    /** (sunrise, sunset) in epoch ms for the local calendar day of [day], or null near the poles. */
    fun times(lat: Double, lon: Double, day: Calendar): Pair<Long, Long>? {
        val n = day.get(Calendar.DAY_OF_YEAR)
        val g = 2 * PI / 365.0 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
            0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val latR = Math.toRadians(lat)
        val arg = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (arg < -1.0 || arg > 1.0) return null
        val ha = Math.toDegrees(acos(arg))
        val riseMin = 720 - 4 * (lon + ha) - eqTime
        val setMin = 720 - 4 * (lon - ha) - eqTime
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        val base = utc.timeInMillis
        return Pair(base + (riseMin * 60_000).toLong(), base + (setMin * 60_000).toLong())
    }

    /** e.g. "☀ Sunrise 6:52 AM  ·  ☾ Sunset 5:14 PM" — shows tomorrow's sunrise once today's sun has set. */
    fun line(c: Context): String {
        if (!Where.hasLocation(c)) return ""
        val lat = Store.getFloat(c, "wLat", 0f).toDouble()
        val lon = Store.getFloat(c, "wLon", 0f).toDouble()
        val now = System.currentTimeMillis()
        val today = Calendar.getInstance()
        val t = times(lat, lon, today) ?: return ""
        return if (now < t.second) {
            "☀  Sunrise ${fmtMillis(c, t.first)}   ·   ☾  Sunset ${fmtMillis(c, t.second)}"
        } else {
            val tomorrow = Calendar.getInstance()
            tomorrow.add(Calendar.DAY_OF_YEAR, 1)
            val t2 = times(lat, lon, tomorrow)
            "☾  Sunset ${fmtMillis(c, t.second)}   ·   ☀  Sunrise tomorrow " + (t2?.let { fmtMillis(c, it.first) } ?: "")
        }
    }
}

/** Where the tablet is — used for sunrise/sunset and weather. */
object Where {
    const val PERMISSION = "android.permission.ACCESS_COARSE_LOCATION"

    fun hasLocation(c: Context) = Store.weatherName(c).isNotEmpty()

    fun hasPermission(c: Context) = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun save(c: Context, name: String, lat: Double, lon: Double) {
        Store.put(c, "wName", name)
        Store.put(c, "wLat", lat.toFloat())
        Store.put(c, "wLon", lon.toFloat())
    }

    /** Finds the tablet's approximate location (Wi-Fi based), saves it, then calls [done] with its name (null if unknown). */
    fun locate(a: Activity, done: (String?) -> Unit) {
        if (!hasPermission(a)) { done(null); return }
        val lm = a.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.NETWORK_PROVIDER, "fused", LocationManager.GPS_PROVIDER).filter {
            try { lm.isProviderEnabled(it) } catch (_: Exception) { false }
        }
        var best: Location? = null
        for (p in providers) {
            try {
                val l = lm.getLastKnownLocation(p)
                val b = best
                if (l != null && (b == null || l.time > b.time)) best = l
            } catch (_: Exception) {}
        }
        val last = best
        val finish = { l: Location? ->
            if (l == null) {
                a.runOnUiThread { done(null) }
            } else {
                Thread {
                    val name = try {
                        @Suppress("DEPRECATION")
                        Geocoder(a, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.let {
                            listOfNotNull(it.locality ?: it.subAdminArea, it.adminArea).joinToString(", ")
                        }
                    } catch (_: Exception) { null }
                    val label = if (name.isNullOrBlank()) "This tablet's location" else name
                    save(a, label, l.latitude, l.longitude)
                    a.runOnUiThread { done(label) }
                }.start()
            }
        }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3600_000L) { finish(last); return }
        val p = providers.firstOrNull()
        if (p == null) { finish(last); return }
        try {
            lm.getCurrentLocation(p, null, a.mainExecutor) { l -> finish(l ?: last) }
        } catch (_: Exception) {
            finish(last)
        }
    }
}
