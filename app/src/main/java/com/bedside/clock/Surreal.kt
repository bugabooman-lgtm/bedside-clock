package com.bedside.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import java.util.Calendar
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

object SurrealOpts {
    // key, label, (rim, face, ink, track)
    val SCHEMES = listOf(
        "classic" to "Black & white",
        "inverse" to "White & black",
        "desert" to "Desert",
        "ocean" to "Ocean",
        "sunset" to "Sunset"
    )
    fun colors(key: String): IntArray = when (key) {
        "inverse" -> intArrayOf(0xFFEDEDED.toInt(), 0xFF121212.toInt(), 0xFFF0F0F0.toInt(), 0xFF777777.toInt())
        "desert" -> intArrayOf(0xFF5A3A22.toInt(), 0xFFE9D8B4.toInt(), 0xFF3B2414.toInt(), 0xFF9C7F5A.toInt())
        "ocean" -> intArrayOf(0xFF0E3B5C.toInt(), 0xFFDCEBF2.toInt(), 0xFF0E2A40.toInt(), 0xFF6E93A8.toInt())
        "sunset" -> intArrayOf(0xFF6B1E3A.toInt(), 0xFFFFD9A8.toInt(), 0xFF4A1028.toInt(), 0xFFC77B6A.toInt())
        else -> intArrayOf(0xFF111111.toInt(), 0xFFF7F5F0.toInt(), 0xFF111111.toInt(), 0xFF8A8A8A.toInt())
    }
}

/**
 * Surreal clock faces: "melt" (a soft blob with warped numerals), "drip" (the bottom drips away)
 * and "spiral" (the numerals wind inwards). The shapes drift very slowly, and the hands droop.
 */
class SurrealClockView(c: Context) : View(c) {
    var mode = "melt"
    var scheme = "classic"
    var showSeconds = false
    var night = false
    var nightBright = 0xFFFF5A3C.toInt()
    var nightFaint = 0xFF7A2A1E.toInt()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    }
    private val path = Path()

    // fixed "personality" for each numeral so they don't jump about
    private val rnd = Random(1931)
    private val numScale = FloatArray(12) { 0.85f + rnd.nextFloat() * 0.45f }
    private val numTilt = FloatArray(12) { -14f + rnd.nextFloat() * 28f }
    private val numSkew = FloatArray(12) { -0.28f + rnd.nextFloat() * 0.56f }
    private val drips = listOf( // angle (clock degrees), length, width
        Triple(150.0, 0.26, 0.09), Triple(178.0, 0.42, 0.07), Triple(205.0, 0.2, 0.08), Triple(232.0, 0.33, 0.06)
    )

    private var cx = 0f
    private var cy = 0f
    private var R = 0f
    private var ph = 0.0   // slow drift phase

    /** Wobbly radius multiplier at clock angle [t] (radians, 0 = 12 o'clock, clockwise). */
    private fun wob(t: Double): Double =
        1 + 0.11 * sin(2 * t + 0.6 + ph) + 0.075 * sin(3 * t + 1.9 - ph * 0.6) + 0.035 * sin(5 * t + 0.4 + ph * 1.3)

    /** Downward sag: strongest at the bottom. */
    private fun sag(t: Double): Double = 0.13 * (1 - cos(t)) / 2

    private fun drip(t: Double, r: Double): Double {
        if (mode != "drip" || r < 0.6) return 0.0
        val k = ((r - 0.6) / 0.4).coerceIn(0.0, 1.0)
        var sum = 0.0
        for ((deg, len, w) in drips) {
            val d = t - Math.toRadians(deg)
            sum += len * exp(-(d * d) / (w * w)) * (0.85 + 0.15 * sin(ph * 2 + deg))
        }
        return sum * k
    }

    /** Where the point at clock angle t and normalised radius r ends up on the warped face. */
    private fun pt(t: Double, r: Double): FloatArray {
        val shapeWob = if (mode == "spiral") 1 + 0.03 * sin(4 * t + ph) else wob(t)
        val rr = r * R * shapeWob
        var x = cx + sin(t) * rr * 0.86
        var y = cy - cos(t) * rr
        if (mode != "spiral") y += sag(t) * r * r * R
        y += drip(t, r) * R
        return floatArrayOf(x.toFloat(), y.toFloat())
    }

    private fun shape(r: Double): Path {
        val out = Path()
        val n = 180
        for (i in 0..n) {
            val q = pt(i * 2 * PI / n, r)
            if (i == 0) out.moveTo(q[0], q[1]) else out.lineTo(q[0], q[1])
        }
        out.close()
        return out
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        ph = (System.currentTimeMillis() % 600_000L) / 600_000.0 * 2 * PI   // one slow cycle every 10 minutes
        R = min(w / 0.86f, h) / 2f * (if (mode == "drip") 0.72f else 0.84f)
        cx = w / 2f
        cy = h / 2f - (if (mode == "drip") R * 0.18f else R * 0.06f)

        val col = SurrealOpts.colors(scheme)
        var rim = col[0]; var face = col[1]; var ink = col[2]; var track = col[3]
        if (night) { rim = nightFaint; face = 0xFF0A0605.toInt(); ink = nightBright; track = nightFaint }

        // rim + face
        p.style = Paint.Style.FILL
        p.color = rim
        cv.drawPath(shape(1.0), p)
        p.color = face
        cv.drawPath(shape(0.9), p)

        // inner track with hour dots (spiral draws its own line)
        p.style = Paint.Style.STROKE
        p.strokeWidth = R * 0.006f
        p.color = track
        if (mode == "spiral") drawSpiral(cv) else cv.drawPath(shape(0.52), p)

        // numerals
        for (i in 0 until 12) {
            val hr = if (i == 0) 12 else i
            val t = i * PI / 6
            val r = if (mode == "spiral") 0.8 - i * 0.032 else 0.72
            val q = pt(t, r)
            var size = R * 0.23f * numScale[i] * (if (mode == "spiral") (1.1f - i * 0.035f) else wob(t).toFloat())
            if (mode == "spiral") size *= 0.9f
            txt.textSize = size
            txt.color = ink
            cv.save()
            cv.translate(q[0], q[1])
            // lean with the shape, stretch downward where the face sags
            val lean = if (mode == "spiral") (Math.toDegrees(t).toFloat() * 0.25f) else numTilt[i] + (sin(t) * 10).toFloat()
            cv.rotate(lean)
            cv.skew(numSkew[i], 0f)
            val stretch = 1f + (if (mode == "spiral") 0f else (sag(t) * 3.2 + drip(t, 0.95) * 1.5).toFloat())
            cv.scale(1f, stretch)
            cv.drawText(hr.toString(), 0f, -(txt.descent() + txt.ascent()) / 2f, txt)
            cv.restore()

            // hour dot on the track
            if (mode != "spiral") {
                val d = pt(t, 0.52)
                p.style = Paint.Style.FILL
                p.color = ink
                cv.drawCircle(d[0], d[1], R * 0.012f, p)
            }
        }

        // droopy hands
        val cal = Calendar.getInstance()
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000.0
        val min = cal.get(Calendar.MINUTE) + sec / 60.0
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60.0
        val hc = pt(0.0, 0.0)
        hand(cv, hc[0], hc[1], hr * PI / 6, R * 0.38f, R * 0.028f, ink, 0.18f)
        hand(cv, hc[0], hc[1], min * PI / 30, R * 0.58f, R * 0.018f, ink, 0.24f)
        if (showSeconds) hand(cv, hc[0], hc[1], sec * PI / 30, R * 0.62f, R * 0.007f, if (night) nightFaint else 0xFFC0392B.toInt(), 0.12f)
        p.style = Paint.Style.FILL
        p.color = ink
        cv.drawCircle(hc[0], hc[1], R * 0.03f, p)
        p.color = 0xFF9A9A9A.toInt()
        cv.drawCircle(hc[0], hc[1], R * 0.012f, p)

        postInvalidateDelayed(if (showSeconds) 60L else 250L)
    }

    /** A hand that bends downward a little, as if it were going soft. */
    private fun hand(cv: Canvas, x0: Float, y0: Float, a: Double, len: Float, width: Float, color: Int, droop: Float) {
        val ex = x0 + (sin(a) * len).toFloat()
        val ey = y0 - (cos(a) * len).toFloat()
        val horiz = kotlin.math.abs(sin(a)).toFloat()           // flat hands droop most
        val mx = (x0 + ex) / 2f
        val my = (y0 + ey) / 2f + len * droop * horiz
        path.reset()
        path.moveTo(x0, y0)
        path.quadTo(mx, my, ex, ey + len * droop * horiz * 0.6f)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = width
        p.color = color
        cv.drawPath(path, p)
    }

    private fun drawSpiral(cv: Canvas) {
        path.reset()
        val turns = 2.2
        val n = 300
        for (i in 0..n) {
            val f = i.toDouble() / n
            val t = f * turns * 2 * PI + ph * 0.2
            val r = 0.86 - f * 0.78
            val x = cx + (sin(t) * r * R * 0.86).toFloat()
            val y = cy - (cos(t) * r * R).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        cv.drawPath(path, p)
    }
}
