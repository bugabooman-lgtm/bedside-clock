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
    private lateinit var updStatus: TextView
    private var luxLabel: TextView? = null
    private lateinit var installBtn: TextView
    private val statusTick = object : Runnable {
        override fun run() {
            if (Updater.busy && Updater.status.isNotEmpty()) updStatus.text = Updater.status
            else if (Updater.status.isNotEmpty() && !Updater.busy && Updater.status != lastShown) updStatus.text = Updater.status
            lastShown = updStatus.text.toString()
            luxLabel?.let { l ->
                val v = Ambient.lux
                l.text = if (v < 0) "Reading…" else "${v.toInt()} lux — " + (if (Ambient.dark) "dark" else "light")
            }
            handler.postDelayed(this, 1000)
        }
    }
    private var lastShown = ""

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

        // ---- tabs: General / Analog face / Updates ----
        fun tabBox(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(40))
        }
        val generalBox = tabBox()
        val analogBox = tabBox()
        val updatesBox = tabBox()
        val tabs = listOf("general" to generalBox, "analog" to analogBox, "updates" to updatesBox)
        val scroll = ScrollView(this)
        fun showTab(key: String) {
            scroll.removeAllViews()
            scroll.addView(tabs.first { it.first == key }.second)
            scroll.scrollTo(0, 0)
            Store.put(this, "settingsTab", key)
        }
        val startTab = intent.getStringExtra("tab") ?: Store.getStr(this, "settingsTab", "general")
        val tabBar = choice(listOf("general" to "General", "analog" to "Analog Face", "updates" to "Updates & About"), startTab) { showTab(it) }
        outer.addView(tabBar, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16); bottomMargin = dp(4) })
        outer.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        var box = generalBox
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
        add(settingRow("Clock style", "Tip: press and hold the clock to switch styles",
            choice(Styles.ALL, Styles.current(this)) { Store.put(this, "clockStyle", it) }))
        fun switchRow(title: String, sub: String?, key: String, def: Boolean) {
            val sw = Switch(this)
            sw.isChecked = Store.getBool(this, key, def)
            sw.setOnCheckedChangeListener { _, on -> Store.put(this, key, on) }
            add(settingRow(title, sub, sw))
        }
        switchRow("0 before single-digit hours", "Shows 06:30 instead of 6:30 on the clock", "hourZero", true)

        // ---- analog face (own tab, with a live preview) ----
        box = analogBox
        val preview = AnalogClockView(this)
        fun refreshPreview() {
            preview.dialKey = Store.getStr(this, "anDial", "black")
            preview.accentKey = Store.getStr(this, "anAccent", "orange")
            preview.numerals = Store.getStr(this, "anNumerals", "arabic")
            preview.fontKey = Store.getStr(this, "anFont", "modern")
            preview.dateMode = Store.getStr(this, "anDate", "daydate")
            preview.layoutKey = Store.getStr(this, "anLayout", "classic")
            preview.showSeconds = Store.getBool(this, "analogSeconds", true)
            preview.invalidate()
        }
        refreshPreview()
        val analogRow = LinearLayout(this)
        analogRow.orientation = LinearLayout.HORIZONTAL
        analogRow.gravity = Gravity.TOP
        val analogOpts = LinearLayout(this)
        analogOpts.orientation = LinearLayout.VERTICAL
        analogRow.addView(preview, LinearLayout.LayoutParams(dp(300), dp(300)).apply { topMargin = dp(20); rightMargin = dp(32) })
        analogRow.addView(analogOpts, LinearLayout.LayoutParams(0, WRAP, 1f))
        analogBox.addView(analogRow)
        analogBox.addView(button("Use the analog clock") {
            Store.put(this, "clockStyle", "analog")
            toast("Clock style set to Analog")
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) })
        box = analogOpts
        section("Analog clock face")
        fun pick(title: String, key: String, def: String, opts: List<Pair<String, String>>) {
            add(settingRow(title, null, choice(opts, Store.getStr(this, key, def)) { Store.put(this, key, it); refreshPreview() }))
        }
        pick("Face layout", "anLayout", "classic", AnalogOpts.LAYOUTS)
        pick("Dial color", "anDial", "black", AnalogOpts.DIALS.map { it.first to it.second })
        pick("Hand accent color", "anAccent", "orange", AnalogOpts.ACCENT_NAMES)
        pick("Numbers", "anNumerals", "arabic", AnalogOpts.NUMERALS)
        pick("Number font", "anFont", "modern", AnalogOpts.FONTS)
        pick("Date window (Classic layout)", "anDate", "daydate", AnalogOpts.DATES)
        val secSwitch = Switch(this)
        secSwitch.isChecked = Store.getBool(this, "analogSeconds", true)
        secSwitch.setOnCheckedChangeListener { _, on -> Store.put(this, "analogSeconds", on); refreshPreview() }
        add(settingRow("Second hand", "A smooth sweeping second hand", secSwitch))

        // ---- surreal faces ----
        box = analogBox
        val srRow = LinearLayout(this)
        srRow.orientation = LinearLayout.HORIZONTAL
        srRow.gravity = Gravity.TOP
        val srPreview = SurrealClockView(this)
        fun refreshSurreal() {
            srPreview.scheme = Store.getStr(this, "srScheme", "classic")
            srPreview.showSeconds = Store.getBool(this, "srSeconds", false)
            srPreview.slimeKey = Store.getStr(this, "slimeColor", "pink")
            srPreview.invalidate()
        }
        refreshSurreal()
        val srOpts = LinearLayout(this)
        srOpts.orientation = LinearLayout.VERTICAL
        srRow.addView(srPreview, LinearLayout.LayoutParams(dp(300), dp(300)).apply { topMargin = dp(20); rightMargin = dp(32) })
        srRow.addView(srOpts, LinearLayout.LayoutParams(0, WRAP, 1f))
        analogBox.addView(srRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(24) })
        box = srOpts
        section("Surreal faces")
        add(settingRow("Shape", "Preview only — choose the clock style below",
            choice(listOf("melt" to "Melting", "drip" to "Dripping", "spiral" to "Spiral", "slime" to "Slime"), "melt") {
                srPreview.mode = it; srPreview.invalidate()
            }))
        add(settingRow("Colors", null, choice(SurrealOpts.SCHEMES, Store.getStr(this, "srScheme", "classic")) {
            Store.put(this, "srScheme", it); refreshSurreal()
        }))
        add(settingRow("Slime color", "For the Slime face", choice(SurrealOpts.SLIMES, Store.getStr(this, "slimeColor", "pink")) {
            Store.put(this, "slimeColor", it); refreshSurreal()
        }))
        val srSec = Switch(this)
        srSec.isChecked = Store.getBool(this, "srSeconds", false)
        srSec.setOnCheckedChangeListener { _, on -> Store.put(this, "srSeconds", on); refreshSurreal() }
        add(settingRow("Second hand", null, srSec))
        val srButtons = LinearLayout(this)
        srButtons.orientation = LinearLayout.HORIZONTAL
        srButtons.setPadding(0, dp(12), 0, dp(12))
        for ((key, label) in listOf("surrealMelt" to "Use Melting", "surrealDrip" to "Use Dripping", "surrealSpiral" to "Use Spiral", "surrealSlime" to "Use Slime")) {
            srButtons.addView(button(label) {
                Store.put(this, "clockStyle", key)
                toast("Clock style: " + Styles.name(key))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) })
        }
        srOpts.addView(srButtons)
        box = generalBox

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
        add(settingRow("Dim automatically", "The Dim button on the clock still turns dimming on or off any time",
            dropdown(Ambient.MODES, Ambient.mode(this)) { Store.put(this, "dimMode", it) }))
        if (Ambient.available(this)) {
            add(settingRow("Dark room means", "How dark the room must be before the clock dims. It waits 15 seconds before dimming and 4 seconds before brightening.",
                choice(Ambient.SENSITIVITY.map { it.first to it.second }, Store.getStr(this, "dimSense", "dark")) {
                    Store.put(this, "dimSense", it)
                }))
            luxLabel = tv("Reading…", 16f, Col.DIM)
            add(settingRow("Room light now", "From the tablet's light sensor, next to the front camera", luxLabel))
        } else {
            add(settingRow("Light sensor", "This tablet didn't report a light sensor, so only night hours can be used", null))
        }
        add(settingRow("Night starts", null, timeButton("nightFrom", Store.nightFrom(this))))
        add(settingRow("Night ends", null, timeButton("nightTo", Store.nightTo(this))))
        add(settingRow("Dim brightness", "How bright the screen is when dimmed (left = darkest). Tip: hold the Dim button on the clock to adjust while you watch.",
            seek(Store.nightBright(this) / 0.3f, 0f) { v, _ -> Store.put(this, "nightBright", v * 0.3f) }))
        add(settingRow("Dim color", null,
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

        // ---- updates & about (own tab) ----
        box = updatesBox
        section("Updates")
        val autoSwitch = Switch(this)
        autoSwitch.isChecked = Updater.autoOn(this)
        autoSwitch.setOnCheckedChangeListener { _, on -> Store.put(this, "autoUpdate", on) }
        add(settingRow("Install updates automatically",
            "Checks every 6 hours. Installs between 9 AM and 9 PM only — never during night hours or within 90 minutes of an alarm.",
            autoSwitch))
        updStatus = tv(lastCheckedText(), 15f, Col.DIM)
        val checkRow = LinearLayout(this)
        checkRow.orientation = LinearLayout.HORIZONTAL
        checkRow.gravity = Gravity.CENTER_VERTICAL
        checkRow.addView(button("Check for updates") { checkNow() })
        installBtn = button("Install now", primary = true) {
            val info = Updater.available
            if (info != null) startInstall(info)
        }
        installBtn.visibility = if (Updater.available != null) android.view.View.VISIBLE else android.view.View.GONE
        checkRow.addView(installBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        checkRow.addView(updStatus, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(18) })
        checkRow.setPadding(0, dp(12), 0, dp(12))
        add(checkRow)
        add(settingRow("Allow the app to install its updates", "One-time: switch on “Allow from this source”", button("Open") {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }))
        val reopen = Switch(this)
        reopen.isChecked = Store.getBool(this, "autoOpen", true)
        reopen.setOnCheckedChangeListener { _, on ->
            Store.put(this, "autoOpen", on)
            if (on && !Settings.canDrawOverlays(this)) openOverlaySettings()
        }
        add(settingRow("Reopen the clock after updates and restarts",
            "Needs “Appear on top” switched on for Bedside Clock — tap the switch to open that setting",
            reopen))

        // ---- about ----
        section("About")
        add(settingRow("Bedside Clock", versionText(), button("Change log") { showChangelog() }))

        showTab(if (tabs.any { it.first == startTab }) startTab else "general")
        setContentView(outer)
    }

    override fun onResume() {
        super.onResume()
        immersive()
        Ambient.start(this)
        handler.post(statusTick)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTick)
        Ambient.stop(this)
        super.onPause()
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

    private fun lastCheckedText(): String {
        val t = Store.getLong(this, "updLastCheck", 0L)
        val now = "You have version ${Updater.currentVersion(this)}."
        if (t == 0L) return now
        val mins = ((System.currentTimeMillis() - t) / 60_000L).toInt()
        val ago = when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 48 * 60 -> "${mins / 60} hr ago"
            else -> "${mins / 1440} days ago"
        }
        return "$now Last checked $ago."
    }

    private fun checkNow() {
        updStatus.text = "Checking…"
        Updater.status = ""
        Updater.check(this) { info, ok ->
            when {
                !ok -> updStatus.text = "Couldn't reach GitHub. Check that Wi-Fi is on."
                info == null -> updStatus.text = "You're up to date (version ${Updater.currentVersion(this)})."
                else -> updStatus.text = "Version ${info.version} is available."
            }
            installBtn.visibility = if (info != null) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun startInstall(info: Updater.Info) {
        if (!Updater.canInstall(this)) {
            toast("First allow Bedside Clock to install updates: switch on “Allow from this source”, then come back and tap Install now.")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            return
        }
        if (AlarmService.running) { toast("Wait until the alarm is off."); return }
        Updater.needsConfirm = false
        Updater.install(this, info)
        updStatus.text = Updater.status
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
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
