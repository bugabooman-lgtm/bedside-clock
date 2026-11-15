package com.bedside.clock

import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Clock styles. */
object Styles {
    val ALL = listOf(
        "digital" to "Digital",
        "flipDark" to "Flip — black cards",
        "flipLight" to "Flip — white cards",
        "analog" to "Analog",
        "surrealMelt" to "Surreal — melting",
        "surrealDrip" to "Surreal — dripping",
        "surrealSpiral" to "Surreal — spiral",
        "surrealSlime" to "Surreal — slime"
    )
    fun current(c: Context) = Store.getStr(c, "clockStyle", "digital")
    fun name(key: String) = ALL.firstOrNull { it.first == key }?.second ?: "Digital"
}

/**
 * Draws flip cards. A card shows [cur]; while animating (p in 0..1) the top half of [prev]
 * folds down, then the bottom half of [cur] falls into place. A space draws a blank card.
 */
class FlipPainter {
    var cardColor = 0xFF1E1E21.toInt()
    var digitColor = 0xFFF2F2F2.toInt()
    var textScale = 0.86f

    private val accel = AccelerateInterpolator(1.4f)
    private val decel = DecelerateInterpolator(1.6f)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val camera = Camera()
    private val matrix = Matrix()

    /** Draws one card. [p] >= 1 means at rest. */
    fun drawCard(cv: Canvas, r: RectF, prev: Char, cur: Char, p: Float) {
        text.textSize = r.height() * textScale
        camera.setLocation(0f, 0f, -(r.height() / 72f) * 3.5f)
        if (p >= 1f) {
            drawHalf(cv, r, cur, true)
            drawHalf(cv, r, cur, false)
            return
        }
        drawHalf(cv, r, cur, true)      // new top, revealed behind the falling flap
        drawHalf(cv, r, prev, false)    // old bottom, until the new one covers it
        if (p < 0.5f) drawFlap(cv, r, prev, true, 90f * accel.getInterpolation(p / 0.5f))
        else drawFlap(cv, r, cur, false, 90f * (1f - decel.getInterpolation((p - 0.5f) / 0.5f)))
    }

    private fun clipHalf(cv: Canvas, r: RectF, top: Boolean) {
        val mid = r.centerY()
        val split = r.height() * 0.008f + 1f
        if (top) cv.clipRect(r.left, r.top, r.right, mid - split) else cv.clipRect(r.left, mid + split, r.right, r.bottom)
    }

    private fun drawHalf(cv: Canvas, r: RectF, ch: Char, top: Boolean) {
        cv.save()
        clipHalf(cv, r, top)
        card.color = cardColor
        val rad = r.width() * 0.09f
        cv.drawRoundRect(r, rad, rad, card)
        if (!top) {
            shade.color = 0x14000000
            cv.drawRoundRect(r, rad, rad, shade)
        }
        if (ch != ' ') {
            text.color = digitColor
            val baseline = r.centerY() - (text.descent() + text.ascent()) / 2f
            cv.drawText(ch.toString(), r.centerX(), baseline, text)
        }
        cv.restore()
    }

    private fun drawFlap(cv: Canvas, r: RectF, ch: Char, top: Boolean, angle: Float) {
        if (angle >= 89.5f) return
        val cx = r.centerX()
        val cy = r.centerY()
        camera.save()
        camera.rotateX(if (top) -angle else angle)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-cx, -cy)
        matrix.postTranslate(cx, cy)
        cv.save()
        cv.concat(matrix)
        drawHalf(cv, r, ch, top)
        clipHalf(cv, r, top)
        shade.color = ((angle / 90f * 150f).toInt().coerceIn(0, 150) shl 24)
        val rad = r.width() * 0.09f
        cv.drawRoundRect(r, rad, rad, shade)
        cv.restore()
    }
}

/** Keeps track of which characters changed and when each card's flip starts. */
class FlipState(size: Int) {
    var cur = CharArray(size) { ' ' }
    var prev = CharArray(size) { ' ' }
    var startAt = LongArray(size)
    var hasText = false
        private set
    private val duration = 700L

    /** Returns true if anything changed. Cards flip right-to-left, a moment apart. */
    fun set(text: String, staggerMs: Long = 120L): Boolean {
        val n = text.length
        if (n != cur.size) {   // layout changed (e.g. a longer month name): show straight away
            cur = text.toCharArray(); prev = text.toCharArray(); startAt = LongArray(n)
            hasText = true
            return true
        }
        val now = System.currentTimeMillis()
        var changed = false
        var delay = 0L
        for (i in n - 1 downTo 0) {
            if (text[i] != cur[i]) {
                prev[i] = cur[i]
                cur[i] = text[i]
                startAt[i] = if (hasText) now + delay else 0L
                if (hasText) delay += staggerMs
                changed = true
            }
        }
        hasText = true
        return changed
    }

    /** Progress 0..1 for card [i] at [now]; -1 = still waiting to start. */
    fun progress(i: Int, now: Long): Float {
        val s = startAt[i]
        if (s == 0L) return 1f
        if (now < s) return -1f
        val p = ((now - s).toFloat() / duration).coerceIn(0f, 1f)
        if (p >= 1f) startAt[i] = 0L
        return p
    }
}

/** The HH MM flip clock. A blank first card (space) still shows as an empty card. */
class FlipClockView(c: Context) : View(c) {
    val painter = FlipPainter()
    private val state = FlipState(4)
    private var ampm = ""
    private val rect = RectF()
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    /** [four] = 4 characters, e.g. "0630" or " 630" (blank first card). */
    fun setTime(four: String, amPm: String) {
        state.set(four.padEnd(4, ' ').take(4))
        ampm = amPm
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val gapR = 0.05f
        val groupR = 0.32f
        var cw = (w * 0.94f) / (4 + 2 * gapR + groupR)
        var ch = cw * 1.42f
        if (ch > h * 0.98f) { ch = h * 0.98f; cw = ch / 1.42f }
        val gap = cw * gapR
        val group = cw * groupR
        val total = 4 * cw + 2 * gap + group
        val x0 = (w - total) / 2f
        val y0 = (h - ch) / 2f
        val xs = floatArrayOf(x0, x0 + cw + gap, x0 + 2 * cw + gap + group, x0 + 3 * cw + 2 * gap + group)
        val now = System.currentTimeMillis()
        var animating = false
        for (i in 0 until 4) {
            rect.set(xs[i], y0, xs[i] + cw, y0 + ch)
            val p = state.progress(i, now)
            if (p < 0f) { painter.drawCard(cv, rect, state.prev[i], state.prev[i], 1f); animating = true; continue }
            painter.drawCard(cv, rect, state.prev[i], state.cur[i], p)
            if (p < 1f) animating = true
        }
        if (ampm.isNotEmpty()) {
            small.textSize = ch * 0.1f
            small.color = painter.digitColor
            small.alpha = 200
            cv.drawText(ampm, xs[0] + cw * 0.08f, y0 + ch - ch * 0.07f, small)
        }
        if (animating) postInvalidateOnAnimation()
    }
}

/** A row of small flip cards for any text, e.g. the date "SAT NOV 14". Spaces become gaps between words. */
class FlipRowView(c: Context) : View(c) {
    val painter = FlipPainter().apply { textScale = 0.72f }
    private var state = FlipState(0)
    private val rect = RectF()

    fun setText(t: String) {
        if (state.set(t.uppercase(), 60L)) invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val chars = state.cur
        if (w <= 0 || h <= 0 || chars.isEmpty()) return
        var ch = h * 0.96f
        var cw = ch * 0.68f
        val gapR = 0.07f
        val spaceR = 0.45f
        fun widthFor(cardW: Float): Float {
            var t = 0f
            for ((i, c) in chars.withIndex()) {
                t += if (c == ' ') cardW * spaceR else cardW
                if (i < chars.size - 1 && c != ' ' && chars[i + 1] != ' ') t += cardW * gapR
            }
            return t
        }
        var total = widthFor(cw)
        if (total > w * 0.92f) { val k = w * 0.92f / total; cw *= k; ch *= k; total = widthFor(cw) }
        var x = (w - total) / 2f
        val y0 = (h - ch) / 2f
        val now = System.currentTimeMillis()
        var animating = false
        for ((i, c) in chars.withIndex()) {
            if (c == ' ') { x += cw * spaceR; continue }
            rect.set(x, y0, x + cw, y0 + ch)
            val p = state.progress(i, now)
            if (p < 0f) { painter.drawCard(cv, rect, state.prev[i], state.prev[i], 1f); animating = true }
            else { painter.drawCard(cv, rect, state.prev[i], c, p); if (p < 1f) animating = true }
            x += cw
            if (i < chars.size - 1 && chars[i + 1] != ' ') x += cw * gapR
        }
        if (animating) postInvalidateOnAnimation()
    }
}

/** Options for the analog face. */
object AnalogOpts {
    // key, label, dial colour, ink (numbers/hands), secondary ink (minute track)
    val DIALS = listOf(
        Triple("black", "Black", Triple(0xFF111214.toInt(), 0xFFEDEDED.toInt(), 0xFF8A8F99.toInt())),
        Triple("white", "White", Triple(0xFFF5F3EE.toInt(), 0xFF1A1A1A.toInt(), 0xFF6E6E6E.toInt())),
        Triple("navy", "Navy", Triple(0xFF14213D.toInt(), 0xFFF1F1F1.toInt(), 0xFF9AA6C2.toInt())),
        Triple("green", "Green", Triple(0xFF13362B.toInt(), 0xFFF0EBDD.toInt(), 0xFF9FB5A6.toInt())),
        Triple("cream", "Cream", Triple(0xFFEFE6D2.toInt(), 0xFF2B2219.toInt(), 0xFF8A7A64.toInt())),
        Triple("silver", "Silver", Triple(0xFFC9CCD1.toInt(), 0xFF1C1F24.toInt(), 0xFF5C6168.toInt())),
        Triple("burgundy", "Burgundy", Triple(0xFF4A1220.toInt(), 0xFFF3E9DC.toInt(), 0xFFC59AA2.toInt()))
    )
    val ACCENTS = listOf(
        "orange" to 0xFFFFB35C.toInt(), "red" to 0xFFE53935.toInt(), "gold" to 0xFFD4AF37.toInt(),
        "blue" to 0xFF3D8BFF.toInt(), "ink" to 0
    )
    val ACCENT_NAMES = listOf("orange" to "Orange", "red" to "Red", "gold" to "Gold", "blue" to "Blue", "ink" to "Same as hands")
    val NUMERALS = listOf("arabic" to "1 2 3", "roman" to "I II III", "quarters" to "12 · 3 · 6 · 9", "markers" to "Markers", "dots" to "Dots")
    val FONTS = listOf("modern" to "Modern", "classic" to "Classic", "bold" to "Bold", "mono" to "Typewriter")
    val DATES = listOf(
        "daydate" to "SAT 14", "date" to "14", "monthdate" to "NOV 14", "full" to "SAT NOV 14", "off" to "Off"
    )
    val LAYOUTS = listOf("classic" to "Classic", "calendar" to "Calendar (day, month, date, moon)")

    fun typeface(key: String): Typeface = when (key) {
        "classic" -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        "bold" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        "mono" -> Typeface.MONOSPACE
        else -> Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
}

/** A watch-style analog face: bezel, dial, minute track, numerals, date window, tapered hands. */
class AnalogClockView(c: Context) : View(c) {
    var dialKey = "black"
    var accentKey = "orange"
    var numerals = "arabic"
    var fontKey = "modern"
    var dateMode = "daydate"
    var layoutKey = "classic"
    var showSeconds = true
    var night = false
    var nightBright = 0xFFFF5A3C.toInt()
    var nightFaint = 0xFF7A2A1E.toInt()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = android.graphics.Path()
    private val rect = RectF()
    private val ROMAN = arrayOf("XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI")

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val R = min(w, h) / 2f * 0.98f
        if (R <= 0) return
        val cx = w / 2f
        val cy = h / 2f

        val dial = AnalogOpts.DIALS.firstOrNull { it.first == dialKey }?.third ?: AnalogOpts.DIALS[0].third
        var dialCol = dial.first
        var ink = dial.second
        var sub = dial.third
        var accent = AnalogOpts.ACCENTS.firstOrNull { it.first == accentKey }?.second ?: 0xFFFFB35C.toInt()
        if (accent == 0) accent = ink
        if (night) { dialCol = 0xFF0A0605.toInt(); ink = nightBright; sub = nightFaint; accent = nightFaint }
        val face = AnalogOpts.typeface(fontKey)

        // ---- bezel ----
        p.style = Paint.Style.FILL
        p.shader = if (night) null else android.graphics.SweepGradient(
            cx, cy,
            intArrayOf(0xFF8E9196.toInt(), 0xFFE6E8EB.toInt(), 0xFF6B6E73.toInt(), 0xFFD5D8DC.toInt(), 0xFF8E9196.toInt()),
            null
        )
        p.color = if (night) 0xFF1A0E0B.toInt() else 0xFF9A9DA2.toInt()
        cv.drawCircle(cx, cy, R, p)
        p.shader = null
        p.color = 0x55000000
        cv.drawCircle(cx, cy, R * 0.935f, p)            // inner edge shadow

        // ---- dial with a soft centre highlight ----
        val dialR = R * 0.925f
        p.shader = if (night) null else android.graphics.RadialGradient(
            cx, cy - dialR * 0.25f, dialR * 1.2f,
            intArrayOf(lighten(dialCol, 0.10f), dialCol, darken(dialCol, 0.18f)), floatArrayOf(0f, 0.6f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        p.color = dialCol
        cv.drawCircle(cx, cy, dialR, p)
        p.shader = null

        // ---- minute track ----
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        for (i in 0 until 60) {
            if (i % 5 == 0) continue
            val a = Math.toRadians(i * 6.0)
            p.color = sub
            p.strokeWidth = R * 0.008f
            line(cv, cx, cy, a, dialR * 0.90f, dialR * 0.96f)
        }

        // ---- hour indices and numerals ----
        val calendar = layoutKey == "calendar"
        val dateAtThree = dateMode != "off" && !calendar
        txt.typeface = face
        txt.color = ink
        for (hr in 0 until 12) {
            val a = Math.toRadians(hr * 30.0)
            val n = if (hr == 0) 12 else hr
            val isQuarter = hr % 3 == 0
            if (calendar) {
                // big numerals; 3, 6 and 9 (and 5, 7) give way to the calendar dials
                p.style = Paint.Style.STROKE
                p.color = ink; p.strokeWidth = R * 0.016f
                line(cv, cx, cy, a, dialR * 0.9f, dialR * 0.96f)
                if (hr == 3 || hr == 5 || hr == 6 || hr == 7 || hr == 9) continue
                when (numerals) {
                    "arabic", "roman" -> {
                        txt.textSize = R * (if (numerals == "roman") 0.12f else 0.15f)
                        numeral(cv, cx, cy, a, dialR * 0.8f, if (numerals == "roman") ROMAN[hr] else n.toString())
                    }
                    else -> baton(cv, cx, cy, a, dialR * 1.08f, R * 0.8f, ink, hr == 0)
                }
                continue
            }
            if (dateAtThree && hr == 3) {
                // short index only; the date window takes the 3's place
                p.color = ink; p.strokeWidth = R * 0.02f
                line(cv, cx, cy, a, dialR * 0.88f, dialR * 0.96f)
                continue
            }
            when (numerals) {
                "arabic", "roman" -> {
                    p.color = ink; p.strokeWidth = R * 0.016f
                    line(cv, cx, cy, a, dialR * 0.89f, dialR * 0.96f)
                    val label = if (numerals == "roman") ROMAN[hr] else n.toString()
                    txt.textSize = R * (if (numerals == "roman") 0.13f else 0.16f)
                    numeral(cv, cx, cy, a, dialR * 0.74f, label)
                }
                "quarters" -> {
                    if (isQuarter) {
                        txt.textSize = R * 0.2f
                        numeral(cv, cx, cy, a, dialR * 0.74f, n.toString())
                    } else baton(cv, cx, cy, a, dialR, R, ink, false)
                }
                "markers" -> baton(cv, cx, cy, a, dialR, R, ink, hr == 0)
                else -> {   // dots
                    p.style = Paint.Style.FILL
                    p.color = ink
                    val rr = dialR * 0.86f
                    cv.drawCircle(cx + (sin(a) * rr).toFloat(), cy - (cos(a) * rr).toFloat(), R * (if (isQuarter) 0.032f else 0.02f), p)
                    p.style = Paint.Style.STROKE
                }
            }
        }

        val cal = Calendar.getInstance()

        // ---- date window at 3 o'clock ----
        if (dateAtThree) {
            val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(cal.time).uppercase()
            val mon = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(cal.time).uppercase()
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val label = when (dateMode) {
                "date" -> d.toString()
                "monthdate" -> "$mon $d"
                "full" -> "$day $mon $d"
                else -> "$day $d"
            }
            txt.typeface = face
            txt.textSize = R * 0.085f
            val tw = txt.measureText(label)
            val bh = R * 0.13f
            val bw = tw + R * 0.07f
            val bx = minOf(cx + dialR * 0.66f, cx + dialR * 0.9f - bw / 2f)
            rect.set(bx - bw / 2f, cy - bh / 2f, bx + bw / 2f, cy + bh / 2f)
            p.style = Paint.Style.FILL
            p.color = if (night) 0xFF140A08.toInt() else if (isDark(dialCol)) 0xFFF4F2EC.toInt() else 0xFF15161A.toInt()
            cv.drawRoundRect(rect, R * 0.012f, R * 0.012f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = R * 0.008f
            p.color = if (night) nightFaint else 0xFF7C7F84.toInt()
            cv.drawRoundRect(rect, R * 0.012f, R * 0.012f, p)
            val old = txt.color
            txt.color = if (night) nightBright else if (isDark(dialCol)) 0xFF15161A.toInt() else 0xFFF4F2EC.toInt()
            cv.drawText(label, bx, cy - (txt.descent() + txt.ascent()) / 2f, txt)
            txt.color = old
        }

        if (calendar) drawCalendar(cv, cx, cy, dialR, R, cal, dialCol, ink, sub, accent)

        // ---- hands ----
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000.0
        val mins = cal.get(Calendar.MINUTE) + sec / 60.0
        val hrs = (cal.get(Calendar.HOUR) % 12) + mins / 60.0
        val shadowDx = R * 0.012f
        val shadowDy = R * 0.022f
        // shadows first
        handPath(cv, cx + shadowDx, cy + shadowDy, hrs * 30.0, dialR * 0.52f, R * 0.075f, R * 0.028f, 0x40000000)
        handPath(cv, cx + shadowDx, cy + shadowDy, mins * 6.0, dialR * 0.80f, R * 0.055f, R * 0.018f, 0x40000000)
        handPath(cv, cx, cy, hrs * 30.0, dialR * 0.52f, R * 0.075f, R * 0.028f, ink)
        handPath(cv, cx, cy, mins * 6.0, dialR * 0.80f, R * 0.055f, R * 0.018f, ink)
        if (showSeconds) {
            val a = Math.toRadians(sec * 6.0)
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.color = 0x33000000
            p.strokeWidth = R * 0.012f
            line(cv, cx + shadowDx, cy + shadowDy, a, -dialR * 0.2f, dialR * 0.9f)
            p.color = accent
            p.strokeWidth = R * 0.01f
            line(cv, cx, cy, a, -dialR * 0.22f, dialR * 0.9f)
            p.style = Paint.Style.FILL
            // counterweight on the tail
            cv.drawCircle(cx - (sin(a) * dialR * 0.16f).toFloat(), cy + (cos(a) * dialR * 0.16f).toFloat(), R * 0.028f, p)
        }
        // centre cap
        p.style = Paint.Style.FILL
        p.color = if (showSeconds) accent else ink
        cv.drawCircle(cx, cy, R * 0.04f, p)
        p.color = darken(dialCol, 0.4f)
        cv.drawCircle(cx, cy, R * 0.014f, p)

        if (showSeconds) postInvalidateDelayed(40) else postInvalidateDelayed(1000)
    }

    private fun line(cv: Canvas, cx: Float, cy: Float, a: Double, r1: Float, r2: Float) {
        cv.drawLine(
            cx + (sin(a) * r1).toFloat(), cy - (cos(a) * r1).toFloat(),
            cx + (sin(a) * r2).toFloat(), cy - (cos(a) * r2).toFloat(), p
        )
    }

    private fun numeral(cv: Canvas, cx: Float, cy: Float, a: Double, r: Float, label: String) {
        val x = cx + (sin(a) * r).toFloat()
        val y = cy - (cos(a) * r).toFloat() - (txt.descent() + txt.ascent()) / 2f
        cv.drawText(label, x, y, txt)
    }

    /** A raised rectangular hour marker (doubled at 12). */
    private fun baton(cv: Canvas, cx: Float, cy: Float, a: Double, dialR: Float, R: Float, ink: Int, double: Boolean) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.SQUARE
        p.color = ink
        p.strokeWidth = R * 0.035f
        if (double) {
            val off = 0.035
            line(cv, cx, cy, a - off, dialR * 0.72f, dialR * 0.92f)
            line(cv, cx, cy, a + off, dialR * 0.72f, dialR * 0.92f)
        } else {
            line(cv, cx, cy, a, dialR * 0.74f, dialR * 0.92f)
        }
        p.strokeCap = Paint.Cap.BUTT
    }

    /** A tapered hand from a short tail through the centre to a narrow tip. */
    private fun handPath(cv: Canvas, cx: Float, cy: Float, deg: Double, len: Float, base: Float, tip: Float, color: Int) {
        val a = Math.toRadians(deg)
        val ux = sin(a).toFloat(); val uy = -cos(a).toFloat()      // along the hand
        val px = -uy; val py = ux                                  // across the hand
        val tail = len * 0.16f
        path.reset()
        path.moveTo(cx - ux * tail - px * base * 0.35f, cy - uy * tail - py * base * 0.35f)
        path.lineTo(cx - px * base / 2f, cy - py * base / 2f)
        path.lineTo(cx + ux * len - px * tip / 2f, cy + uy * len - py * tip / 2f)
        path.lineTo(cx + ux * (len + tip), cy + uy * (len + tip))
        path.lineTo(cx + ux * len + px * tip / 2f, cy + uy * len + py * tip / 2f)
        path.lineTo(cx + px * base / 2f, cy + py * base / 2f)
        path.lineTo(cx - ux * tail + px * base * 0.35f, cy - uy * tail + py * base * 0.35f)
        path.close()
        p.style = Paint.Style.FILL
        p.color = color
        cv.drawPath(path, p)
    }

    private fun isDark(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) < 140
    }

    private fun lighten(c: Int, f: Float): Int = mix(c, 0xFFFFFFFF.toInt(), f)
    private fun darken(c: Int, f: Float): Int = mix(c, 0xFF000000.toInt(), f)
    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(sh: Int) = ((((a shr sh) and 0xFF) * (1 - f) + ((b shr sh) and 0xFF) * f).toInt() and 0xFF) shl sh
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    // ---------------- calendar layout ----------------

    private val DAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val oval = RectF()

    /** Day dial at 9, month dial at 3, date dial with moon phase at 6. */
    private fun drawCalendar(cv: Canvas, cx: Float, cy: Float, dialR: Float, R: Float, cal: Calendar,
                             dialCol: Int, ink: Int, sub: Int, accent: Int) {
        val d = dialR * 0.43f
        val sr = dialR * 0.27f
        val hourFrac = (cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0) / 24.0
        val handCol = if (night) ink else accent

        // ---- day of week (9 o'clock) with 24-hour scale inside ----
        var sx = cx - d
        subBase(cv, sx, cy, sr, dialCol, sub)
        ticks(cv, sx, cy, sr, 7, sub)
        ringText(cv, sx, cy, sr * 0.72f, DAYS, sr * 0.17f, ink)
        ringText(cv, sx, cy, sr * 0.36f, listOf("24", "6", "12", "18"), sr * 0.14f, sub)
        val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
        handPath(cv, sx, cy, (dow + hourFrac) / 7.0 * 360.0, sr * 0.82f, sr * 0.09f, sr * 0.03f, handCol)
        handPath(cv, sx, cy, hourFrac * 360.0, sr * 0.42f, sr * 0.08f, sr * 0.03f, ink)
        pin(cv, sx, cy, sr, handCol)

        // ---- month (3 o'clock) with leap-year scale inside ----
        sx = cx + d
        subBase(cv, sx, cy, sr, dialCol, sub)
        ticks(cv, sx, cy, sr, 12, sub)
        ringText(cv, sx, cy, sr * 0.74f, MONTHS, sr * 0.14f, ink)
        ringText(cv, sx, cy, sr * 0.36f, listOf("4", "1", "2", "3"), sr * 0.14f, sub)
        val dom = cal.get(Calendar.DAY_OF_MONTH)
        val dim = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val monthFrac = (dom - 1 + hourFrac) / dim
        handPath(cv, sx, cy, (cal.get(Calendar.MONTH) + monthFrac) / 12.0 * 360.0, sr * 0.82f, sr * 0.09f, sr * 0.03f, handCol)
        val leap = cal.get(Calendar.YEAR) % 4   // 0 = leap year, shown at "4"
        handPath(cv, sx, cy, (leap + (cal.get(Calendar.MONTH) + monthFrac) / 12.0) / 4.0 * 360.0, sr * 0.42f, sr * 0.08f, sr * 0.03f, ink)
        pin(cv, sx, cy, sr, handCol)

        // ---- date (6 o'clock) around a moon-phase window ----
        val sy = cy + d
        subBase(cv, cx, sy, sr, dialCol, sub)
        ticks(cv, cx, sy, sr, 31, sub)
        val dateLabels = (1..31).map { if (it % 2 == 1) it.toString() else "" }
        ringText(cv, cx, sy, sr * 0.78f, dateLabels, sr * 0.13f, ink)
        moonWindow(cv, cx, sy - sr * 0.12f, sr * 0.5f, ink)
        handPath(cv, cx, sy, (dom - 1 + hourFrac) / 31.0 * 360.0, sr * 0.86f, sr * 0.08f, sr * 0.025f, handCol)
        pin(cv, cx, sy, sr, handCol)
    }

    private fun subBase(cv: Canvas, sx: Float, sy: Float, sr: Float, dialCol: Int, sub: Int) {
        p.style = Paint.Style.FILL
        p.color = if (isDark(dialCol)) darken(dialCol, 0.22f) else darken(dialCol, 0.07f)
        cv.drawCircle(sx, sy, sr, p)
        p.style = Paint.Style.STROKE
        p.color = sub
        p.strokeWidth = sr * 0.02f
        cv.drawCircle(sx, sy, sr, p)
    }

    private fun ticks(cv: Canvas, sx: Float, sy: Float, sr: Float, n: Int, sub: Int) {
        p.style = Paint.Style.STROKE
        p.color = sub
        p.strokeWidth = sr * 0.015f
        for (i in 0 until n) line(cv, sx, sy, Math.toRadians(i * 360.0 / n), sr * 0.9f, sr * 0.98f)
    }

    private fun ringText(cv: Canvas, sx: Float, sy: Float, r: Float, labels: List<String>, size: Float, color: Int) {
        val old = txt.color
        txt.textSize = size
        txt.color = color
        for ((i, l) in labels.withIndex()) {
            if (l.isEmpty()) continue
            numeral(cv, sx, sy, Math.toRadians(i * 360.0 / labels.size), r, l)
        }
        txt.color = old
    }

    private fun pin(cv: Canvas, sx: Float, sy: Float, sr: Float, color: Int) {
        p.style = Paint.Style.FILL
        p.color = color
        cv.drawCircle(sx, sy, sr * 0.07f, p)
    }

    /** A dome-shaped window with a starry sky and tonight's real moon phase. */
    private fun moonWindow(cv: Canvas, mx: Float, my: Float, half: Float, ink: Int) {
        val sky = if (night) 0xFF0C0605.toInt() else 0xFF14244E.toInt()
        val gold = if (night) nightBright else 0xFFE9C46A.toInt()
        // window: upper half-disc with a flat bottom
        oval.set(mx - half, my - half, mx + half, my + half)
        path.reset()
        path.arcTo(oval, 180f, 180f, true)
        path.close()
        p.style = Paint.Style.FILL
        p.color = sky
        cv.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = half * 0.04f
        p.color = ink
        cv.drawPath(path, p)
        // stars
        p.style = Paint.Style.FILL
        p.color = gold
        val stars = floatArrayOf(-0.7f, -0.2f, -0.45f, -0.6f, 0.5f, -0.55f, 0.75f, -0.18f, -0.15f, -0.85f, 0.25f, -0.85f)
        for (i in stars.indices step 2) cv.drawCircle(mx + stars[i] * half, my + stars[i + 1] * half, half * 0.035f, p)

        // moon phase: 0 = new, 0.5 = full
        val synodic = 29.530588853
        val days = (System.currentTimeMillis() - 947182440000L) / 86_400_000.0
        val phase = ((days % synodic) + synodic) % synodic / synodic
        val r = half * 0.42f
        val ccx = mx
        val ccy = my - half * 0.45f
        val dark = if (night) 0xFF1A0E0B.toInt() else 0xFF2A3A66.toInt()
        p.color = dark
        cv.drawCircle(ccx, ccy, r, p)
        val lit = (1 - cos(2 * Math.PI * phase)) / 2
        cv.save()
        oval.set(ccx - r, ccy - r, ccx + r, ccy + r)
        path.reset()
        path.addOval(oval, android.graphics.Path.Direction.CW)
        cv.clipPath(path)
        p.color = gold
        if (phase < 0.5) cv.drawRect(ccx, ccy - r, ccx + r, ccy + r, p) else cv.drawRect(ccx - r, ccy - r, ccx, ccy + r, p)
        val ew = (r * kotlin.math.abs(cos(2 * Math.PI * phase))).toFloat()
        p.color = if (lit > 0.5) gold else dark
        cv.drawOval(ccx - ew, ccy - r, ccx + ew, ccy + r, p)
        cv.restore()
    }
}
