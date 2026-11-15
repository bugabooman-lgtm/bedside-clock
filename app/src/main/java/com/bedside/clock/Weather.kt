package com.bedside.clock

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Free weather from Open-Meteo (no account or key needed). Call these off the main thread. */
object Weather {
    class Place(val name: String, val lat: Double, val lon: Double)

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Finds a city or ZIP code. */
    fun find(query: String): Place? {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json"))
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        val region = r.optString("admin1", "")
        val name = if (region.isNotEmpty()) "${r.getString("name")}, $region" else r.getString("name")
        return Place(name, r.getDouble("latitude"), r.getDouble("longitude"))
    }

    /** e.g. "52° Partly cloudy · H 60°  L 41°" */
    fun summary(lat: Double, lon: Double, fahrenheit: Boolean): String {
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val json = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&temperature_unit=$unit&timezone=auto&forecast_days=1"
            )
        )
        val cur = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val t = cur.getDouble("temperature_2m").roundToInt()
        val code = cur.getInt("weather_code")
        val hi = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
        val lo = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
        val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
        val rainText = if (rain >= 30) " · $rain% rain" else ""
        return "$t° ${describe(code)} · H $hi°  L $lo°$rainText"
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorms"
        else -> ""
    }
}
