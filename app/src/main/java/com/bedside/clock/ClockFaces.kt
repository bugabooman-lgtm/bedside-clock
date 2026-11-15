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
 * A split-flap clock: four cards (HH MM). When a digit changes, the top half of the old
 * digit folds down, then the bottom half of the new digit falls into place.
 */
class FlipClockView(c: Context) : View(c) {
    var cardColor = 0xFF1C1C1E.toInt()
    var digitColor = 0xFFF2F2F2.toInt()
    var pageColor = 0xFF000000.toInt()

    private val digits = CharArray(4) { ' ' }
    private val prev = CharArray(4) { ' ' }
    private val startAt = LongArray(4)
    private var ampm = ""
    var hasTime = false
        private set

    private val duration = 700L
    private val accel = AccelerateInterpolator(1.4f)
    private val decel = DecelerateInterpolator(1.6f)

    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val camera = Camera()
    private val matrix = Matrix()
    private val rect = RectF()

    /** [four] = 4 characters "0630" (a space = blank card); animates the cards that changed. */
    fun setTime(four: String, amPm: String) {
        val now = System.currentTimeMillis()
        var stagger = 0L
        for (i in 0 until 4) {
            val ch = four.getOrElse(i) { ' ' }
            if (ch != digits[i]) {
                prev[i] = digits[i]
                digits[i] = ch
                // flip from the right-most card backwards, like a real flip clock
                startAt[i] = if (hasTime) now + stagger else 0L
            }
        }
        // stagger: minutes flip first, then hours a moment later
        if (hasTime) {
            for (i in 3 downTo 0) if (startAt[i] > 0) { startAt[i] = now + stagger; stagger += 120L }
        }
        ampm = amPm
        hasTime = true
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        // geometry: [card][gap][card]  [groupGap]  [card][gap][card]
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
        text.textSize = ch * 0.86f
        camera.setLocation(0f, 0f, -(ch / 72f) * 3.5f)

        val now = System.currentTimeMillis()
        var animating = false
        for (i in 0 until 4) {
            rect.set(xs[i], y0, xs[i] + cw, y0 + ch)
            val s = startAt[i]
            val p = if (s == 0L) 1f else ((now - s).toFloat() / duration).coerceIn(0f, 1f)
            if (s != 0L && now < s) {
                // waiting for its turn: show the old digit
                drawHalf(cv, rect, prev[i], true)
                drawHalf(cv, rect, prev[i], false)
                animating = true
                continue
            }
            if (p >= 1f) {
                startAt[i] = 0L
                drawHalf(cv, rect, digits[i], true)
                drawHalf(cv, rect, digits[i], false)
            } else {
                animating = true
                drawHalf(cv, rect, digits[i], true)     // new top, revealed behind the falling flap
                drawHalf(cv, rect, prev[i], false)      // old bottom, until the new one covers it
                if (p < 0.5f) {
                    drawFlap(cv, rect, prev[i], true, 90f * accel.getInterpolation(p / 0.5f))
                } else {
                    drawFlap(cv, rect, digits[i], false, 90f * (1f - decel.getInterpolation((p - 0.5f) / 0.5f)))
                }
            }
        }
        // AM / PM in the corner of the first visible hour card
        if (ampm.isNotEmpty()) {
            val i = if (digits[0] == ' ') 1 else 0
            small.textSize = ch * 0.1f
            small.color = digitColor
            small.alpha = 200
            cv.drawText(ampm, xs[i] + cw * 0.08f, y0 + ch - ch * 0.07f, small)
        }
        if (animating) postInvalidateOnAnimation()
    }

    /** One half of a card with its half of the digit. */
    private fun drawHalf(cv: Canvas, r: RectF, digit: Char, top: Boolean) {
        if (digit == ' ') return
        val mid = r.centerY()
        val split = r.height() * 0.008f + 1f
        cv.save()
        if (top) cv.clipRect(r.left, r.top, r.right, mid - split) else cv.clipRect(r.left, mid + split, r.right, r.bottom)
        card.color = cardColor
        val rad = r.width() * 0.09f
        cv.drawRoundRect(r, rad, rad, card)
        // a hint of depth: lower half a touch darker
        if (!top) {
            shade.color = 0x14000000
            cv.drawRoundRect(r, rad, rad, shade)
        }
        text.color = digitColor
        val baseline = mid - (text.descent() + text.ascent()) / 2f
        cv.drawText(digit.toString(), r.centerX(), baseline, text)
        cv.restore()
    }

    /** The moving flap, tilted [angle] degrees (0 = flat on the card, 90 = edge-on) about the middle hinge. */
    private fun drawFlap(cv: Canvas, r: RectF, digit: Char, top: Boolean, angle: Float) {
        if (digit == ' ' || angle >= 89.5f) return
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
        drawHalf(cv, r, digit, top)
        // darken as it turns away from the light
        val mid = r.centerY()
        val split = r.height() * 0.008f + 1f
        if (top) cv.clipRect(r.left, r.top, r.right, mid - split) else cv.clipRect(r.left, mid + split, r.right, r.bottom)
        shade.color = ((angle / 90f * 150f).toInt().coerceIn(0, 150) shl 24)
        val rad = r.width() * 0.09f
        cv.drawRoundRect(r, rad, rad, shade)
        cv.restore()
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
