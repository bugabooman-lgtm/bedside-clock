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
    private lateinit var locationLabel: TextView

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

        // ---- dim ----
        section("Dim")
        val nightSwitch = Switch(this)
        nightSwitch.isChecked = Store.nightOn(this)
        nightSwitch.setOnCheckedChangeListener { _, on -> Store.put(this, "nightOn", on) }
        add(settingRow("Dim automatically at night", "The Dim button on the clock turns dimming on or off any time", nightSwitch))
        add(settingRow("Night starts", null, timeButton("nightFrom", Store.nightFrom(this))))
        add(settingRow("Night ends", null, timeButton("nightTo", Store.nightTo(this))))
        add(settingRow("Dim brightness", "How bright the screen is when dimmed (left = darkest). Tip: hold the Dim button on the clock to adjust while you watch.",
            seek(Store.nightBright(this) / 0.3f, 0f) { v, _ -> Store.put(this, "nightBright", v * 0.3f) }))
        add(settingRow("Dim colour", null,
            choice(listOf("red" to "Red", "amber" to "Amber", "grey" to "Grey"), Store.getStr(this, "dimColor", "red")) {
                Store.put(this, "dimColor", it)
            }))

        // ---- location & weather ----
        section("Location, weather, sunrise & sunset")
        locationLabel = tv(Store.weatherName(this).ifEmpty { "Not set" }, 16f, Col.DIM)
        add(settingRow("Location", null, locationLabel))
        val locRow = LinearLayout(this)
        locRow.orientation = LinearLayout.HORIZONTAL
        locRow.gravity = Gravity.CENTER_VERTICAL
        val city = EditText(this)
        city.hint = "…or type a city or ZIP code"
        city.setTextColor(Col.TEXT)
        city.setHintTextColor(Col.DIM)
        city.isSingleLine = true
        locRow.addView(button("📍  Use this tablet's location", primary = true) { useTabletLocation() })
        locRow.addView(city, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(16) })
        locRow.addView(button("Set") { findCity(city.text.toString(), locationLabel) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        locRow.setPadding(0, dp(8), 0, dp(8))
        add(locRow)
        val wSwitch = Switch(this)
        wSwitch.isChecked = Store.getBool(this, "wShow", true)
        wSwitch.setOnCheckedChangeListener { _, on -> Store.put(this, "wShow", on) }
        add(settingRow("Show weather", null, wSwitch))
        val sSwitch = Switch(this)
        sSwitch.isChecked = Store.getBool(this, "sunShow", true)
        sSwitch.setOnCheckedChangeListener { _, on -> Store.put(this, "sunShow", on) }
        add(settingRow("Show sunrise & sunset", "Worked out on the tablet — no internet needed", sSwitch))
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

        // ---- about ----
        section("About")
        add(settingRow("Bedside Clock", versionText(), button("Change log") { showChangelog() }))

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
                    label.text = Store.weatherName(this).ifEmpty { "Not set" }
                    toast("Couldn't find “$query”. Check the spelling and that Wi-Fi is on.")
                } else {
                    Where.save(this, place.name, place.lat, place.lon)
                    label.text = place.name
                    toast("Location set to ${place.name}")
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

    private fun versionText(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "Version ${info.versionName} (build ${info.longVersionCode})"
        } catch (_: Exception) {
            "Version " + Changelog.entries.first().first
        }
    }

    private fun showChangelog() {
        val sb = android.text.SpannableStringBuilder()
        for ((ver, items) in Changelog.entries) {
            val start = sb.length
            sb.append("Version $ver\n")
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, 0)
            sb.setSpan(android.text.style.ForegroundColorSpan(Col.ACCENT), start, sb.length, 0)
            for (item in items) sb.append("•  ").append(item).append("\n")
            sb.append("\n")
        }
        val text = tv(sb, 17f)
        text.setLineSpacing(0f, 1.15f)
        text.setPadding(dp(28), dp(18), dp(28), dp(8))
        val scroll = ScrollView(this)
        scroll.addView(text)
        android.app.AlertDialog.Builder(this)
            .setTitle("What's new")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun useTabletLocation() {
        if (Where.hasPermission(this)) {
            locateAndShow()
        } else {
            requestPermissions(arrayOf(Where.PERMISSION), 5)
        }
    }

    private fun locateAndShow() {
        locationLabel.text = "Finding location…"
        Where.locate(this) { name ->
            if (name == null) {
                locationLabel.text = Store.weatherName(this).ifEmpty { "Not set" }
                toast("Couldn't get the tablet's location. Check Location is on in quick settings, or type a city instead.")
            } else {
                locationLabel.text = name
                toast("Location set to $name")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) locateAndShow()
            else toast("Location not allowed — you can type a city instead.")
        }
    }
}
