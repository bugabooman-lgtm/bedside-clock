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
        "analog" to "Analog"
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

/** A classic analog clock face with an optional sweeping second hand. */
class AnalogClockView(c: Context) : View(c) {
    var faceColor = 0xFFF4F1EA.toInt()
    var dimColor = 0xFF8A90A6.toInt()
    var secondColor = 0xFFFFB35C.toInt()
    var showSeconds = true
    var showNumbers = true

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) / 2f * 0.96f
        if (r <= 0) return
        val cx = w / 2f
        val cy = h / 2f

        // ring
        p.style = Paint.Style.STROKE
        p.color = dimColor
        p.alpha = 90
        p.strokeWidth = r * 0.012f
        cv.drawCircle(cx, cy, r, p)

        // ticks
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val major = i % 5 == 0
            val r1 = r * (if (major) 0.86f else 0.92f)
            val r2 = r * 0.96f
            p.color = if (major) faceColor else dimColor
            p.alpha = if (major) 255 else 160
            p.strokeWidth = r * (if (major) 0.022f else 0.008f)
            cv.drawLine(
                cx + (sin(a) * r1).toFloat(), cy - (cos(a) * r1).toFloat(),
                cx + (sin(a) * r2).toFloat(), cy - (cos(a) * r2).toFloat(), p
            )
        }
        // numbers
        if (showNumbers) {
            num.color = faceColor
            num.textSize = r * 0.15f
            for (n in 1..12) {
                val a = Math.toRadians(n * 30.0)
                val rr = r * 0.71f
                val x = cx + (sin(a) * rr).toFloat()
                val y = cy - (cos(a) * rr).toFloat() - (num.descent() + num.ascent()) / 2f
                cv.drawText(n.toString(), x, y, num)
            }
        }

        val cal = Calendar.getInstance()
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000.0
        val min = cal.get(Calendar.MINUTE) + sec / 60.0
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60.0

        fun hand(angleDeg: Double, len: Float, tail: Float, width: Float, color: Int) {
            val a = Math.toRadians(angleDeg)
            p.style = Paint.Style.STROKE
            p.color = color
            p.alpha = 255
            p.strokeWidth = width
            cv.drawLine(
                cx - (sin(a) * tail).toFloat(), cy + (cos(a) * tail).toFloat(),
                cx + (sin(a) * len).toFloat(), cy - (cos(a) * len).toFloat(), p
            )
        }
        hand(hr * 30.0, r * 0.5f, r * 0.08f, r * 0.05f, faceColor)
        hand(min * 6.0, r * 0.76f, r * 0.1f, r * 0.032f, faceColor)
        if (showSeconds) hand(sec * 6.0, r * 0.86f, r * 0.16f, r * 0.012f, secondColor)
        p.style = Paint.Style.FILL
        p.color = if (showSeconds) secondColor else faceColor
        cv.drawCircle(cx, cy, r * 0.035f, p)

        // smooth sweep when the second hand shows; otherwise once a second is plenty
        if (showSeconds) postInvalidateDelayed(40) else postInvalidateDelayed(1000)
    }
}
