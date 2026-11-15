package com.bedside.clock

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

object Col {
    const val BG = 0xFF07080C.toInt()
    const val PANEL = 0xFF11131B.toInt()
    const val PANEL2 = 0xFF181B26.toInt()
    const val LINE = 0xFF262A38.toInt()
    const val TEXT = 0xFFE9ECF5.toInt()
    const val DIM = 0xFF8A90A6.toInt()
    const val ACCENT = 0xFFFFB35C.toInt()
    const val ON_ACCENT = 0xFF1B1206.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()
    const val CLOCK = 0xFFF4F1EA.toInt()
    const val NIGHT = 0xFFFF5A3C.toInt()
    const val NIGHT_DIM = 0xFF7A2A1E.toInt()
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun roundBg(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun Context.tv(s: CharSequence, sizeSp: Float, color: Int = Col.TEXT, light: Boolean = false): TextView {
    val v = TextView(this)
    v.text = s
    v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    v.setTextColor(color)
    if (light) v.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    return v
}

fun Context.button(label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): TextView {
    val v = tv(label, 18f, if (primary) Col.ON_ACCENT else if (danger) Col.DANGER else Col.TEXT)
    v.gravity = Gravity.CENTER
    v.setPadding(dp(22), dp(13), dp(22), dp(13))
    v.background = roundBg(if (primary) Col.ACCENT else Col.PANEL2, dp(30).toFloat(), Col.LINE, if (primary) 0 else dp(1))
    if (primary) v.typeface = Typeface.DEFAULT_BOLD
    v.setOnClickListener { onClick() }
    return v
}

/** A row of segmented choices; the picked one is highlighted. */
fun <T> Context.choice(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit): View {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.HORIZONTAL
    box.background = roundBg(Col.PANEL2, dp(12).toFloat(), Col.LINE, dp(1))
    val views = mutableListOf<TextView>()
    val radius = dp(12).toFloat()
    fun style(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Col.ON_ACCENT else Col.DIM)
        v.background = if (on) roundBg(Col.ACCENT, radius) else null
    }
    for ((value, label) in options) {
        val v = tv(label, 16f, Col.DIM)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(16), dp(11), dp(16), dp(11))
        style(v, value == selected)
        v.setOnClickListener {
            views.forEach { style(it, false) }
            style(v, true)
            onPick(value)
        }
        views.add(v)
        box.addView(v)
    }
    val scroll = HorizontalScrollView(this)
    scroll.isHorizontalScrollBarEnabled = false
    scroll.addView(box)
    return scroll
}

fun Context.settingRow(title: String, sub: String?, right: View?): LinearLayout {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(0, dp(14), 0, dp(14))
    val left = LinearLayout(this)
    left.orientation = LinearLayout.VERTICAL
    left.addView(tv(title, 19f))
    if (sub != null) left.addView(tv(sub, 14f, Col.DIM))
    row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
    if (right != null) row.addView(right, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(16) })
    return row
}

fun Context.divider(): View = View(this).apply { setBackgroundColor(Col.LINE) }

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

/** ("7:05", "AM") or ("07:05", "") in 24-hour mode. */
fun fmtTime(c: Context, h: Int, m: Int): Pair<String, String> {
    return if (Store.is24(c)) {
        Pair(String.format("%02d:%02d", h, m), "")
    } else {
        val hh = if (h % 12 == 0) 12 else h % 12
        Pair("$hh:" + String.format("%02d", m), if (h < 12) "AM" else "PM")
    }
}

fun fmtStr(c: Context, h: Int, m: Int): String {
    val (a, b) = fmtTime(c, h, m)
    return if (b.isEmpty()) a else "$a $b"
}

fun fmtMillis(c: Context, at: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = at
    return fmtStr(c, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

private val DAY_SHORT = arrayOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

fun daysText(days: Set<Int>): String {
    if (days.isEmpty()) return "Once"
    val s = days.sorted()
    if (s.size == 7) return "Every day"
    if (s == listOf(2, 3, 4, 5, 6)) return "Weekdays"
    if (s == listOf(1, 7)) return "Weekends"
    val order = listOf(2, 3, 4, 5, 6, 7, 1)
    return order.filter { days.contains(it) }.joinToString(", ") { DAY_SHORT[it] }
}

fun untilText(at: Long): String {
    val mins = ((at - System.currentTimeMillis()) / 60_000L).toInt()
    if (mins < 1) return "in under a minute"
    val h = mins / 60
    val m = mins % 60
    if (h >= 24) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = at
        return "on " + DAY_SHORT[cal.get(Calendar.DAY_OF_WEEK)]
    }
    return if (h > 0) "in $h hr $m min" else "in $m min"
}

fun inRange(nowMin: Int, from: Int, to: Int): Boolean =
    if (from <= to) nowMin in from until to else (nowMin >= from || nowMin < to)

fun minutesText(c: Context, mins: Int): String = fmtStr(c, mins / 60, mins % 60)

fun Activity.immersive() {
    window.insetsController?.let {
        it.hide(WindowInsets.Type.systemBars())
        it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
