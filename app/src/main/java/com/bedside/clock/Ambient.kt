package com.bedside.clock

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Reads the tablet's light sensor and decides whether the room is dark.
 * Uses two thresholds (so it doesn't flicker at the edge) and a short wait before switching:
 * the room has to stay dark for 15 seconds before dimming, and bright for 4 seconds before brightening.
 */
object Ambient : SensorEventListener {
    val MODES = listOf(
        "either" to "Night hours or dark room",
        "light" to "Dark room only",
        "schedule" to "Night hours only",
        "off" to "Off (Dim button only)"
    )
    // key, label, dark below (lux), bright above (lux)
    val SENSITIVITY = listOf(
        Triple("vdark", "Lights off", 3f to 12f),
        Triple("dark", "Very dim", 10f to 30f),
        Triple("dim", "Dim lamp", 30f to 90f)
    )

    private const val DARK_AFTER_MS = 15_000L
    private const val BRIGHT_AFTER_MS = 4_000L

    @Volatile var lux = -1f          // smoothed reading; -1 = no reading yet
        private set
    @Volatile var dark = false        // the settled answer
        private set
    private var pendingSince = 0L
    private var users = 0
    private var ctx: Context? = null

    fun available(c: Context): Boolean =
        (c.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_LIGHT) != null

    /** Older versions only had an on/off switch for night hours; carry that choice over. */
    fun mode(c: Context) = Store.getStr(c, "dimMode", if (Store.nightOn(c)) "either" else "light")

    fun start(c: Context) {
        users++
        if (users > 1) return
        ctx = c.applicationContext
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop(c: Context) {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        (c.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values[0].coerceAtLeast(0f)
        lux = if (lux < 0) v else lux * 0.7f + v * 0.3f
        evaluate()
    }

    /** Re-checks dark/bright; also called every second by the clock, since the sensor only reports changes. */
    fun evaluate() {
        val c = ctx ?: return
        if (lux < 0) return
        val key = Store.getStr(c, "dimSense", "dark")
        val (darkBelow, brightAbove) = SENSITIVITY.firstOrNull { it.first == key }?.third ?: (10f to 30f)
        val wantChange = if (dark) lux > brightAbove else lux < darkBelow
        val now = System.currentTimeMillis()
        if (!wantChange) { pendingSince = 0L; return }
        if (pendingSince == 0L) pendingSince = now
        if (now - pendingSince >= if (dark) BRIGHT_AFTER_MS else DARK_AFTER_MS) {
            dark = !dark
            pendingSince = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
