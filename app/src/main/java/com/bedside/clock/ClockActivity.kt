package com.bedside.clock

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** The bedside clock face. Stays on screen all night. */
class ClockActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var center: LinearLayout
    private lateinit var hoursView: TextView
    private lateinit var colonView: TextView
    private lateinit var minsView: TextView
    private val clockParts get() = listOf(hoursView, colonView, minsView)
    private lateinit var clockRow: LinearLayout
    private lateinit var flipView: FlipClockView
    private lateinit var analogView: AnalogClockView
    private lateinit var dateFlip: FlipRowView
    private lateinit var surrealView: SurrealClockView
    private lateinit var clockBox: FrameLayout
    private var style = "digital"
    private lateinit var dateView: TextView
    private lateinit var nextView: TextView
    private lateinit var weatherView: TextView
    private lateinit var sleepChip: TextView
    private lateinit var updateChip: TextView
    private lateinit var bar: LinearLayout
    private lateinit var sunrise: View

    private var lastMin = -1
    private var peekUntil = 0L
    private var dimOverride: Boolean? = null   // null = follow the night schedule
    private var lastScheduled: Boolean? = null
    private var dimWanted = false
    private var isNight = false
    private lateinit var dimBtn: TextView
    private lateinit var sunView: TextView
    private var weatherAt = 0L
    private var weatherText = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val hideBar = Runnable {
        bar.animate().alpha(0f).setDuration(600).withEndAction { bar.visibility = View.INVISIBLE }.start()
    }

    private val tick = object : Runnable {
        override fun run() {
            update(false)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        root.setBackgroundColor(Col.BG)

        sunrise = View(this)
        sunrise.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xFFFFD9A0.toInt(), 0xFFFF9A5A.toInt(), 0xFFC2547A.toInt(), 0xFF2A1B3D.toInt())
        )
        sunrise.alpha = 0f
        root.addView(sunrise, FrameLayout.LayoutParams(MATCH, MATCH))

        center = LinearLayout(this)
        center.orientation = LinearLayout.VERTICAL
        center.gravity = Gravity.CENTER
        // Hours | : | minutes — the two sides get equal width, so the colon sits dead centre.
        clockRow = LinearLayout(this)
        clockRow.orientation = LinearLayout.HORIZONTAL
        clockRow.isBaselineAligned = true
        hoursView = tv("", 200f, Col.CLOCK, light = true)
        hoursView.gravity = Gravity.END
        colonView = tv(":", 200f, Col.CLOCK, light = true)
        minsView = tv("", 200f, Col.CLOCK, light = true)
        minsView.gravity = Gravity.START
        for (v in listOf(hoursView, colonView, minsView)) {
            v.includeFontPadding = false
            v.maxLines = 1
        }
        clockRow.addView(hoursView, LinearLayout.LayoutParams(0, WRAP, 1f))
        clockRow.addView(colonView, LinearLayout.LayoutParams(WRAP, WRAP))
        clockRow.addView(minsView, LinearLayout.LayoutParams(0, WRAP, 1f))
        dateView = tv("", 30f, Col.DIM, light = true)
        dateView.gravity = Gravity.CENTER
        nextView = tv("", 22f, Col.ACCENT)
        nextView.gravity = Gravity.CENTER
        weatherView = tv("", 22f, Col.DIM)
        weatherView.gravity = Gravity.CENTER
        flipView = FlipClockView(this)
        analogView = AnalogClockView(this)
        surrealView = SurrealClockView(this)
        clockBox = FrameLayout(this)
        clockBox.addView(clockRow, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        clockBox.addView(flipView, FrameLayout.LayoutParams(MATCH, dp(300), Gravity.CENTER))
        clockBox.addView(analogView, FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER))
        clockBox.addView(surrealView, FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER))
        // press and hold the clock to switch style
        clockBox.setOnLongClickListener {
            val keys = Styles.ALL.map { it.first }
            val next = keys[(keys.indexOf(style) + 1) % keys.size]
            Store.put(this, "clockStyle", next)
            applyStyle()
            toast("Clock style: " + Styles.name(next))
            true
        }
        center.addView(clockBox, LinearLayout.LayoutParams(MATCH, WRAP))
        center.addView(dateView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        dateFlip = FlipRowView(this)
        center.addView(dateFlip, LinearLayout.LayoutParams(MATCH, dp(64)).apply { topMargin = dp(14) })
        center.addView(nextView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })
        center.addView(weatherView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        sunView = tv("", 20f, Col.DIM)
        sunView.gravity = Gravity.CENTER
        center.addView(sunView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        root.addView(center, FrameLayout.LayoutParams(MATCH, MATCH))

        sleepChip = tv("", 16f, 0xFF7CC4FF.toInt())
        sleepChip.visibility = View.GONE
        root.addView(sleepChip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(18); rightMargin = dp(22)
        })

        updateChip = tv("", 16f, Col.ACCENT)
        updateChip.setPadding(dp(14), dp(8), dp(14), dp(8))
        updateChip.background = roundBg(Col.PANEL, dp(20).toFloat(), Col.LINE, dp(1))
        updateChip.visibility = View.GONE
        updateChip.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java).putExtra("tab", "updates")) }
        root.addView(updateChip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(16); leftMargin = dp(18)
        })

        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        fun barBtn(label: String, action: () -> Unit): TextView {
            val b = button(label) { action() }
            b.background = roundBg(Col.PANEL, dp(30).toFloat(), Col.LINE, dp(1))
            bar.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(7); rightMargin = dp(7) })
            return b
        }
        barBtn("⏰  Alarms") { startActivity(Intent(this, AlarmsActivity::class.java)) }
        barBtn("🎤  Voice") { listen() }
        barBtn("☾  Sleep sounds") { sleepDialog() }
        dimBtn = barBtn("◐  Dim: Off") {
            dimOverride = !dimWanted
            peekUntil = 0L
            update(true)
            toast(if (dimWanted) "Dim on — hold the Dim button to change brightness" else "Dim off")
        }
        dimBtn.setOnLongClickListener { dimDialog(); true }
        barBtn("⚙  Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(20) })

        setContentView(root)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitClock() }
        askPermissions()
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        immersive()
        Scheduler.reschedule(this)
        lastMin = -1
        weatherAt = 0L
        showBar()
        handler.removeCallbacks(tick)
        Ambient.start(this)
        applyStyle()
        handler.post(tick)
        showWhatsNew()
    }

    /** After an update, show what changed (once per version). */
    private fun showWhatsNew() {
        val now = Updater.currentVersion(this)
        val seen = Store.getStr(this, "seenVersion", "")
        Store.put(this, "seenVersion", now)
        if (seen.isEmpty() || seen == now) return
        val entry = Changelog.entries.firstOrNull { it.first == now } ?: return
        AlertDialog.Builder(this)
            .setTitle("Updated to version $now")
            .setMessage(entry.second.joinToString("\n\n") { "•  $it" })
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        Ambient.stop(this)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = android.graphics.Rect()
            val onBar = bar.visibility == View.VISIBLE && bar.alpha > 0.5f &&
                bar.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())
            showBar()
            if (isNight && !onBar) {
                // tap the clock while dimmed: brighten for 15 seconds
                peekUntil = System.currentTimeMillis() + 15_000L
                update(true)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showBar() {
        bar.animate().cancel()
        bar.visibility = View.VISIBLE
        bar.alpha = 1f
        handler.removeCallbacks(hideBar)
        handler.postDelayed(hideBar, 8000)
    }

    // ---------------- drawing ----------------

    private fun update(force: Boolean) {
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (nowMin != lastMin || force) {
            lastMin = nowMin
            renderClock(now)
        }
        applyLighting(nowMin, force)
        updateSleepChip()
        if (nowMin != lastUpdTick) { lastUpdTick = nowMin; Updater.tick(this) }
        val upd = Updater.available
        if (upd != null && !Updater.busy && (!Updater.autoOn(this) || !Updater.canInstall(this) || Updater.needsConfirm)) {
            updateChip.text = "⬆  Version ${upd.version} available — tap"
            updateChip.visibility = View.VISIBLE
        } else if (Updater.busy && Updater.status.isNotEmpty()) {
            updateChip.text = Updater.status
            updateChip.visibility = View.VISIBLE
        } else {
            updateChip.visibility = View.GONE
        }
        maybeRefreshWeather()
    }

    private fun renderClock(now: Calendar) {
        val (main, ampm) = fmtTime(this, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val hh = main.substringBefore(':')
        val mm = main.substringAfter(':')
        val right = if (ampm.isEmpty()) mm else "$mm $ampm"
        val s = SpannableString(right)
        if (ampm.isNotEmpty()) {
            s.setSpan(RelativeSizeSpan(0.18f), mm.length, right.length, 0)
            s.setSpan(ForegroundColorSpan(if (isNight) dimColors().second else Col.DIM), mm.length, right.length, 0)
        }
        val zero = Store.getBool(this, "hourZero", true)
        centreDigital(zero)
        hoursView.text = if (zero) hh.padStart(2, '0') else hh
        minsView.text = s
        flipView.setTime((if (zero) hh.padStart(2, '0') else hh.padStart(2, ' ')) + mm, ampm)
        analogView.invalidate()
        fitClock()
        dateView.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now.time)
        dateFlip.setText(SimpleDateFormat("EEE MMM d", Locale.getDefault()).format(now.time))
        val n = Scheduler.next(this)
        nextView.text = if (n == null) "" else {
            (if (n.snoozed) "⏰  Snoozed until " else "⏰  ") + fmtMillis(this, n.at) + " · " + untilText(n.at)
        }
        weatherView.text = weatherText
        sunView.text = if (Store.getBool(this, "sunShow", true)) Sun.line(this) else ""
    }

    /** Makes the digits as big as the screen allows. */
    private fun fitClock() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val (main, ampm) = fmtTime(this, lastMin.coerceAtLeast(0) / 60, lastMin.coerceAtLeast(0) % 60)
        when (style) {
            "flipDark", "flipLight" -> {
                val want = (h * 0.52f).toInt()
                val lp = flipView.layoutParams
                if (lp.height != want) { lp.height = want; flipView.layoutParams = lp }
                val dateH = (h * 0.1f).toInt()
                val dlp = dateFlip.layoutParams
                if (dlp.height != dateH) { dlp.height = dateH; dateFlip.layoutParams = dlp }
                return
            }
            "surrealMelt", "surrealDrip", "surrealSpiral", "surrealSlime" -> {
                val want = minOf(roomForFace(h), w * 0.62f).toInt()
                val lp = surrealView.layoutParams
                if (lp.height != want) { lp.width = want; lp.height = want; surrealView.layoutParams = lp }
                return
            }
            "analog" -> {
                val want = minOf(h * 0.78f, roomForFace(h), w * 0.62f).toInt()
                val lp = analogView.layoutParams
                if (lp.height != want) { lp.width = want; lp.height = want; analogView.layoutParams = lp }
                return
            }
        }
        val p = Paint(hoursView.paint)
        var size = h * 0.52f
        p.textSize = size
        val hh = main.substringBefore(':')
        val left = p.measureText(if (Store.getBool(this, "hourZero", true)) hh.padStart(2, '0') else hh)
        var right = p.measureText(main.substringAfter(':'))
        val colon = p.measureText(":")
        if (ampm.isNotEmpty()) { p.textSize = size * 0.18f; right += p.measureText(" $ampm") }
        // both halves are as wide as the wider one, so the colon stays centred
        val width = if (Store.getBool(this, "hourZero", true)) 2 * maxOf(left, right) + colon else left + colon + right
        val maxW = w * 0.92f
        if (width > maxW) size *= maxW / width
        for (v in clockParts) v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    private var isNightRendered = false

    /** Height left for a clock face after the date / alarm / weather / sunrise lines below it. */
    private fun roomForFace(h: Int): Float {
        var others = 0
        for (i in 0 until center.childCount) {
            val v = center.getChildAt(i)
            if (v === clockBox || v.visibility == View.GONE) continue
            if (v is TextView && v.text.isNullOrEmpty()) { others += v.lineHeight } else others += v.measuredHeight
            val lp = v.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) others += lp.topMargin + lp.bottomMargin
        }
        return (h - others - dp(28)).toFloat().coerceAtLeast(h * 0.4f)
    }
    private var digitalZero: Boolean? = null

    /**
     * With the leading 0, the colon sits in the middle (both sides are two digits wide).
     * Without it, the whole time ("6:30 PM") is centred instead.
     */
    private fun centreDigital(zero: Boolean) {
        if (digitalZero == zero) return
        digitalZero = zero
        clockRow.gravity = Gravity.CENTER_HORIZONTAL
        hoursView.layoutParams = if (zero) LinearLayout.LayoutParams(0, WRAP, 1f) else LinearLayout.LayoutParams(WRAP, WRAP)
        minsView.layoutParams = if (zero) LinearLayout.LayoutParams(0, WRAP, 1f) else LinearLayout.LayoutParams(WRAP, WRAP)
    }

    /** Shows the chosen clock style. */
    private fun applyStyle() {
        style = Styles.current(this)
        clockRow.visibility = if (style == "digital") View.VISIBLE else View.GONE
        flipView.visibility = if (style.startsWith("flip")) View.VISIBLE else View.GONE
        dateFlip.visibility = flipView.visibility
        dateView.visibility = if (style.startsWith("flip")) View.GONE else View.VISIBLE
        analogView.visibility = if (style == "analog") View.VISIBLE else View.GONE
        surrealView.visibility = if (style.startsWith("surreal")) View.VISIBLE else View.GONE
        surrealView.mode = when (style) { "surrealDrip" -> "drip"; "surrealSpiral" -> "spiral"; "surrealSlime" -> "slime"; else -> "melt" }
        surrealView.slimeKey = Store.getStr(this, "slimeColor", "pink")
        surrealView.scheme = Store.getStr(this, "srScheme", "classic")
        surrealView.showSeconds = Store.getBool(this, "srSeconds", false)
        analogView.showSeconds = Store.getBool(this, "analogSeconds", true)
        analogView.dialKey = Store.getStr(this, "anDial", "black")
        analogView.accentKey = Store.getStr(this, "anAccent", "orange")
        analogView.numerals = Store.getStr(this, "anNumerals", "arabic")
        analogView.fontKey = Store.getStr(this, "anFont", "modern")
        analogView.dateMode = Store.getStr(this, "anDate", "daydate")
        analogView.layoutKey = Store.getStr(this, "anLayout", "classic")
        // the date lives inside the analog face (unless its date window is off)
        if (style == "analog" && (analogView.dateMode != "off" || analogView.layoutKey == "calendar")) dateView.visibility = View.GONE
        faceColors(isNight)
        lastMin = -1
        fitClock()
        update(true)
    }

    /** Colours for the flip and analog faces, day or dimmed. */
    private fun faceColors(night: Boolean) {
        val (bright, faint) = dimColors()
        if (night) {
            for (fp in listOf(flipView.painter, dateFlip.painter)) {
                fp.cardColor = 0xFF120A08.toInt()
                fp.digitColor = bright
            }
            analogView.night = true
            surrealView.night = true
            surrealView.nightBright = bright
            surrealView.nightFaint = faint
            analogView.nightBright = bright
            analogView.nightFaint = faint
        } else {
            for (fp in listOf(flipView.painter, dateFlip.painter)) {
                if (style == "flipLight") {
                    fp.cardColor = 0xFFF2F2F0.toInt()
                    fp.digitColor = 0xFF141414.toInt()
                } else {
                    fp.cardColor = 0xFF1E1E21.toInt()
                    fp.digitColor = 0xFFF2F2F2.toInt()
                }
            }
            analogView.night = false
            surrealView.night = false
        }
        flipView.invalidate()
        dateFlip.invalidate()
        analogView.invalidate()
        surrealView.invalidate()
    }
    private var lastUpdTick = -1

    /** (clock colour, small-text colour) for the dimmed screen. */
    private fun dimColors(): Pair<Int, Int> = when (Store.getStr(this, "dimColor", "red")) {
        "amber" -> Pair(0xFFFFA040.toInt(), 0xFF7A4A18.toInt())
        "grey" -> Pair(0xFFA8A8A8.toInt(), 0xFF555555.toInt())
        else -> Pair(Col.NIGHT, Col.NIGHT_DIM)
    }

    private fun applyLighting(nowMin: Int, force: Boolean = false) {
        val n = Scheduler.next(this)
        var sun = 0f
        if (n != null && !n.snoozed && n.alarm.sunriseMin > 0) {
            val left = (n.at - System.currentTimeMillis()) / 60_000f
            if (left >= 0f && left <= n.alarm.sunriseMin) sun = 1f - left / n.alarm.sunriseMin
        }
        sunrise.alpha = sun * 0.92f

        Ambient.evaluate()
        val hours = inRange(nowMin, Store.nightFrom(this), Store.nightTo(this))
        val scheduledNight = when (Ambient.mode(this)) {
            "either" -> hours || Ambient.dark
            "light" -> Ambient.dark
            "schedule" -> hours
            else -> false
        }
        // a manual Dim on/off lasts until the night schedule next starts or ends
        if (lastScheduled != null && lastScheduled != scheduledNight) dimOverride = null
        lastScheduled = scheduledNight
        dimWanted = dimOverride ?: scheduledNight
        dimBtn.text = if (dimWanted) "◐  Dim: On" else "◐  Dim: Off"
        val night = sun == 0f && System.currentTimeMillis() > peekUntil && dimWanted
        if (night != isNight || force) {
            isNight = night
            val (bright, faint) = dimColors()
            for (v in clockParts) v.setTextColor(if (night) bright else Col.CLOCK)
            faceColors(night)
            dateView.setTextColor(if (night) faint else Col.DIM)
            nextView.setTextColor(if (night) faint else Col.ACCENT)
            weatherView.setTextColor(if (night) faint else Col.DIM)
            sunView.setTextColor(if (night) faint else Col.DIM)
            if (night != isNightRendered) { isNightRendered = night; lastMin = -1 } // re-render AM/PM colour
        }

        val nightLevel = Store.nightBright(this).coerceAtLeast(0.004f)
        val brightness = when {
            sun > 0f -> maxOf(nightLevel, sun)
            night -> nightLevel
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        val lp = window.attributes
        if (lp.screenBrightness != brightness) {
            lp.screenBrightness = brightness
            window.attributes = lp
        }
    }

    private fun updateSleepChip() {
        if (!SleepPlayer.isPlaying) {
            sleepChip.visibility = View.GONE
            return
        }
        sleepChip.visibility = View.VISIBLE
        val name = SleepPlayer.TYPES.firstOrNull { it.first == SleepPlayer.type }?.second ?: "Sleep sound"
        val left = SleepPlayer.minutesLeft()
        sleepChip.text = if (left > 0) "$name · $left min left" else "$name · playing"
    }

    private fun maybeRefreshWeather() {
        if (Store.weatherName(this).isEmpty() || !Store.getBool(this, "wShow", true)) {
            if (weatherText.isNotEmpty()) { weatherText = ""; weatherView.text = "" }
            return
        }
        val now = System.currentTimeMillis()
        if (now - weatherAt < 30 * 60_000L) return
        weatherAt = now
        val lat = Store.getFloat(this, "wLat", 0f).toDouble()
        val lon = Store.getFloat(this, "wLon", 0f).toDouble()
        val f = Store.useF(this)
        Thread {
            try {
                val s = Weather.summary(lat, lon, f)
                runOnUiThread { weatherText = s; weatherView.text = s }
            } catch (_: Exception) {
                weatherAt = now - 25 * 60_000L // try again in ~5 minutes
            }
        }.start()
    }

    // ---------------- dim brightness ----------------

    /** Quick slider for how bright the dimmed screen is (and its colour). Dims the screen while open so you can see it. */
    private fun dimDialog() {
        val before = dimOverride
        dimOverride = true
        peekUntil = 0L
        update(true)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(28), dp(18), dp(28), dp(6))
        box.addView(tv("Dim brightness", 24f))
        box.addView(tv("Slide to set how bright the screen is when dimmed", 15f, Col.DIM))
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = (Store.nightBright(this) / 0.3f * 100).toInt().coerceIn(0, 100)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                Store.put(this@ClockActivity, "nightBright", p / 100f * 0.3f)
                update(true)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        box.addView(seek, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18); bottomMargin = dp(10) })
        box.addView(settingRow("Dim color", null,
            choice(listOf("red" to "Red", "amber" to "Amber", "grey" to "Grey"), Store.getStr(this, "dimColor", "red")) {
                Store.put(this, "dimColor", it)
                update(true)
            }))
        AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Done", null)
            .setOnDismissListener {
                dimOverride = before
                update(true)
            }
            .show()
    }

    // ---------------- sleep sounds ----------------

    private fun sleepDialog() {
        var type = Store.getStr(this, "sleepType", "rain")
        var timer = Store.getInt(this, "sleepTimer", 30)
        var vol = Store.getFloat(this, "sleepVol", 0.25f)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(28), dp(16), dp(28), dp(4))
        box.addView(tv("Sleep sounds", 26f))
        box.addView(settingRow("Sound", null, choice(SleepPlayer.TYPES, type) {
            type = it
            if (SleepPlayer.isPlaying) SleepPlayer.play(type, vol, timer)
        }))
        box.addView(settingRow("Turn off after", null,
            choice(listOf(15 to "15 min", 30 to "30 min", 60 to "1 hr", 0 to "All night"), timer) {
                timer = it
                if (SleepPlayer.isPlaying) SleepPlayer.setTimer(it)
            }))
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = (vol * 100).toInt()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                vol = (p / 100f).coerceAtLeast(0.02f)
                SleepPlayer.volume = vol
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        box.addView(settingRow("Volume", null, null))
        box.addView(seek, LinearLayout.LayoutParams(MATCH, WRAP))
        AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Play") { _, _ ->
                Store.put(this, "sleepType", type)
                Store.put(this, "sleepTimer", timer)
                Store.put(this, "sleepVol", vol)
                SleepPlayer.play(type, vol, timer)
                updateSleepChip()
            }
            .setNegativeButton("Stop") { _, _ -> SleepPlayer.stop(); updateSleepChip() }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun locateNow() {
        Where.locate(this) { name ->
            if (name != null) {
                weatherAt = 0L
                update(true)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val i = permissions.indexOf(Where.PERMISSION)
        if (i >= 0 && grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED) locateNow()
    }

    // ---------------- permissions ----------------

    private fun askPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) wanted.add("android.permission.POST_NOTIFICATIONS")
        // First run: find where the tablet is, for sunrise, sunset and weather
        if (!Where.hasLocation(this) && !Store.getBool(this, "locAsked", false)) {
            Store.put(this, "locAsked", true)
            if (Where.hasPermission(this)) locateNow() else wanted.add(Where.PERMISSION)
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                AlertDialog.Builder(this)
                    .setTitle("Allow full-screen alarms")
                    .setMessage("So the wake-up screen can appear even when the tablet is locked, turn on “Bedside Clock” on the next screen.")
                    .setPositiveButton("Open settings") { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    // ---------------- voice ----------------

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Try: “Set an alarm for 6 AM” · “Wake me at 6:30 on weekdays” · “Play rain sounds”")
        try {
            startActivityForResult(i, REQ_VOICE)
        } catch (_: Exception) {
            toast("Voice input isn't available. Make sure the Google app is installed and up to date.")
        }
    }

    @Deprecated("Uses the platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK || data == null) return
        val heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) ?: return
        var cmd: Voice.Cmd = Voice.Cmd.Unknown
        for (h in heard) {
            cmd = Voice.parse(h)
            if (cmd !is Voice.Cmd.Unknown) break
        }
        val reply = Voice.run(this, cmd)
        toast(reply)
        if (ttsReady) tts?.speak(reply.replace("—", ","), TextToSpeech.QUEUE_FLUSH, null, "reply")
        lastMin = -1
        update(true)
    }

    companion object {
        private const val REQ_VOICE = 31
    }
}
