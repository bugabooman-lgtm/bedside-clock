package com.bedside.clock

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setBackgroundColor(Col.BG)
        outer.setPadding(dp(40), dp(24), dp(40), dp(0))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(button("‹  Back") { finish() })
        header.addView(tv("Settings", 30f).apply { setPadding(dp(22), 0, 0, 0) })
        outer.addView(header)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(10), 0, dp(40))
        val scroll = ScrollView(this)
        scroll.addView(box)
        outer.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        fun section(name: String) {
            box.addView(tv(name.uppercase(), 14f, Col.ACCENT).apply { setPadding(0, dp(22), 0, dp(4)) })
        }
        fun add(v: android.view.View) {
            box.addView(v)
            box.addView(divider(), LinearLayout.LayoutParams(MATCH, 1))
        }

        // ---- clock ----
        section("Clock")
        add(settingRow("Clock format", null,
            choice(listOf(false to "12-hour", true to "24-hour"), Store.is24(this)) { Store.put(this, "is24", it) }))

        // ---- alarms ----
        section("Alarms")
        add(settingRow("Snooze length", null,
            choice(listOf(5 to "5 min", 9 to "9 min", 10 to "10 min", 15 to "15 min"), Store.snoozeMin(this)) {
                Store.put(this, "snoozeMin", it)
            }))
        add(settingRow("Alarm volume", "How loud the alarm gets at its peak",
            seek(Store.volume(this), 0.1f) { v, done ->
                Store.put(this, "volume", v)
                if (done) previewVolume()
            }))
        add(settingRow("Test the alarm", "Rings right now so you can check it works", button("Ring now") {
            AlarmService.ringNow(this, -1L, false)
        }))

        // ---- night ----
        section("Night mode")
        val nightSwitch = Switch(this)
        nightSwitch.isChecked = Store.nightOn(this)
        nightSwitch.setOnCheckedChangeListener { _, on -> Store.put(this, "nightOn", on) }
        add(settingRow("Dim red clock at night", "Tap the screen at night to brighten it for 15 seconds", nightSwitch))
        add(settingRow("Night starts", null, timeButton("nightFrom", Store.nightFrom(this))))
        add(settingRow("Night ends", null, timeButton("nightTo", Store.nightTo(this))))
        add(settingRow("Night brightness", "Lower = darker room",
            seek(Store.nightBright(this) / 0.3f, 0f) { v, _ -> Store.put(this, "nightBright", v * 0.3f) }))

        // ---- weather ----
        section("Weather")
        val current = tv(Store.weatherName(this).ifEmpty { "Off" }, 16f, Col.DIM)
        add(settingRow("Location", null, current))
        val city = EditText(this)
        city.hint = "City or ZIP code"
        city.setTextColor(Col.TEXT)
        city.setHintTextColor(Col.DIM)
        city.isSingleLine = true
        val cityRow = LinearLayout(this)
        cityRow.orientation = LinearLayout.HORIZONTAL
        cityRow.gravity = Gravity.CENTER_VERTICAL
        cityRow.addView(city, LinearLayout.LayoutParams(0, WRAP, 1f))
        cityRow.addView(button("Set", primary = true) { findCity(city.text.toString(), current) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        cityRow.addView(button("Turn off") {
            Store.put(this, "wName", "")
            current.text = "Off"
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        cityRow.setPadding(0, dp(8), 0, dp(8))
        add(cityRow)
        add(settingRow("Temperature", null,
            choice(listOf(true to "°F", false to "°C"), Store.useF(this)) { Store.put(this, "useF", it) }))

        // ---- reliability ----
        section("Make sure alarms always ring")
        add(settingRow("Battery: no limits", "Stops Samsung from putting the app to sleep", button("Open") {
            openBatterySettings()
        }))
        if (Build.VERSION.SDK_INT >= 34) {
            add(settingRow("Full-screen alarms", "Lets the wake-up screen show over the lock screen", button("Open") {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }))
        }
        add(settingRow("Keep screen on while charging", "Developer options → Stay awake (optional — the clock already keeps the screen on while it's open)", null))

        setContentView(outer)
    }

    override fun onResume() {
        super.onResume()
        immersive()
    }

    private fun timeButton(key: String, minutes: Int): TextView {
        var mins = minutes
        lateinit var b: TextView
        b = button(minutesText(this, mins)) {
            TimePickerDialog(this, { _, h, m ->
                mins = h * 60 + m
                Store.put(this, key, mins)
                b.text = minutesText(this, mins)
            }, mins / 60, mins % 60, Store.is24(this)).show()
        }
        return b
    }

    /** SeekBar from 0..1; onChange(value, finishedDragging). */
    private fun seek(start: Float, min: Float, onChange: (Float, Boolean) -> Unit): SeekBar {
        val s = SeekBar(this)
        s.max = 100
        s.progress = (start.coerceIn(0f, 1f) * 100).toInt()
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChange((p / 100f).coerceAtLeast(min), false)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                onChange((sb.progress / 100f).coerceAtLeast(min), true)
            }
        })
        s.layoutParams = LinearLayout.LayoutParams(dp(300), WRAP)
        s.minimumWidth = dp(300)
        return s
    }

    private fun previewVolume() {
        try {
            val am = getSystemService(android.media.AudioManager::class.java)
            val stream = android.media.AudioManager.STREAM_ALARM
            val old = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            am.setStreamVolume(stream, (max * Store.volume(this)).toInt().coerceIn(1, max), 0)
            val t = Synth.loopingTrack("chimes", AudioAttributes.USAGE_ALARM)
            t.play()
            handler.postDelayed({
                try { t.stop() } catch (_: Exception) {}
                t.release()
                am.setStreamVolume(stream, old, 0)
            }, 2500)
        } catch (_: Exception) {}
    }

    private fun findCity(query: String, label: TextView) {
        if (query.isBlank()) return
        label.text = "Searching…"
        Thread {
            val place = try { Weather.find(query) } catch (_: Exception) { null }
            runOnUiThread {
                if (place == null) {
                    label.text = Store.weatherName(this).ifEmpty { "Off" }
                    toast("Couldn't find “$query”. Check the spelling and that Wi-Fi is on.")
                } else {
                    Store.put(this, "wName", place.name)
                    Store.put(this, "wLat", place.lat.toFloat())
                    Store.put(this, "wLon", place.lon.toFloat())
                    label.text = place.name
                    toast("Weather set to ${place.name}")
                }
            }
        }.start()
    }

    private fun openBatterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        try {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                return
            }
        } catch (_: Exception) {}
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
        toast("Already unrestricted. In App info → Battery, make sure it says Unrestricted.")
    }
}
