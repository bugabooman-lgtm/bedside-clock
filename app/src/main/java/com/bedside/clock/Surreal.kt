package com.bedside.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.util.Calendar
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object SurrealOpts {
    val SCHEMES = listOf(
        "classic" to "Black & white",
        "inverse" to "White & black",
        "desert" to "Desert",
        "ocean" to "Ocean",
        "sunset" to "Sunset"
    )
    /** rim, face, ink, track */
    fun colors(key: String): IntArray = when (key) {
        "inverse" -> intArrayOf(0xFFEDEDED.toInt(), 0xFF121212.toInt(), 0xFFF0F0F0.toInt(), 0xFF777777.toInt())
        "desert" -> intArrayOf(0xFF5A3A22.toInt(), 0xFFE9D8B4.toInt(), 0xFF3B2414.toInt(), 0xFF9C7F5A.toInt())
        "ocean" -> intArrayOf(0xFF0E3B5C.toInt(), 0xFFDCEBF2.toInt(), 0xFF0E2A40.toInt(), 0xFF6E93A8.toInt())
        "sunset" -> intArrayOf(0xFF6B1E3A.toInt(), 0xFFFFD9A8.toInt(), 0xFF4A1028.toInt(), 0xFFC77B6A.toInt())
        else -> intArrayOf(0xFF111111.toInt(), 0xFFF7F5F0.toInt(), 0xFF111111.toInt(), 0xFF8A8A8A.toInt())
    }
}

/**
 * Surreal clock faces: "melt" (a soft blob with warped numerals), "drip" (round drops running off
 * the bottom, one falling now and then) and "spiral" (numerals winding down into a vortex).
 * Every face is scaled to fit inside the view, so nothing below it gets pushed off screen.
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

    private val rnd = Random(1931)
    private val numScale = FloatArray(12) { 0.85f + rnd.nextFloat() * 0.45f }
    private val numTilt = FloatArray(12) { -14f + rnd.nextFloat() * 28f }
    private val numSkew = FloatArray(12) { -0.28f + rnd.nextFloat() * 0.56f }

    /** clock angle (deg), length and width of each drip, in face radii */
    private val drips = listOf(
        Triple(150.0, 0.30, 0.12), Triple(178.0, 0.48, 0.15), Triple(205.0, 0.22, 0.10), Triple(230.0, 0.36, 0.11)
    )

    private var cx = 0f
    private var cy = 0f
    private var R = 1f
    private var ph = 0.0

    private fun wob(t: Double): Double =
        1 + 0.11 * sin(2 * t + 0.6 + ph) + 0.075 * sin(3 * t + 1.9 - ph * 0.6) + 0.035 * sin(5 * t + 0.4 + ph * 1.3)

    private fun sag(t: Double): Double = (if (mode == "drip") 0.16 else 0.13) * (1 - cos(t)) / 2

    /** Shape point at clock angle t (0 = 12 o'clock, clockwise) and normalised radius r, before scaling. */
    private fun ux(t: Double, r: Double): Double {
        val s = if (mode == "spiral") 1 + 0.03 * sin(4 * t + ph) else wob(t)
        return sin(t) * r * s * 0.86
    }
    private fun uy(t: Double, r: Double): Double {
        val s = if (mode == "spiral") 1 + 0.03 * sin(4 * t + ph) else wob(t)
        var y = -cos(t) * r * s
        if (mode != "spiral") y += sag(t) * r * r
        return y
    }
    private fun px(t: Double, r: Double) = (cx + ux(t, r) * R).toFloat()
    private fun py(t: Double, r: Double) = (cy + uy(t, r) * R).toFloat()

    /** Sizes and centres the face so the whole thing (drips included) fits inside the view. */
    private fun fit(w: Float, h: Float) {
        var minX = 1e9; var maxX = -1e9; var minY = 1e9; var maxY = -1e9
        for (i in 0 until 180) {
            val t = i * 2 * PI / 180
            val x = ux(t, 1.0); val y = uy(t, 1.0)
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        if (mode == "drip") {
            for ((deg, len, wd) in drips) {
                val t = Math.toRadians(deg)
                maxY = max(maxY, uy(t, 1.0) + len + wd * 0.8)
            }
            maxY += 0.12   // room for the falling drop
        }
        val bw = maxX - minX
        val bh = maxY - minY
        R = min(w * 0.97 / bw, h * 0.97 / bh).toFloat()
        cx = (w / 2 - R * (minX + maxX) / 2).toFloat()
        cy = (h / 2 - R * (minY + maxY) / 2).toFloat()
    }

    private fun shape(r: Double): Path {
        val out = Path()
        val n = 180
        for (i in 0..n) {
            val t = i * 2 * PI / n
            if (i == 0) out.moveTo(px(t, r), py(t, r)) else out.lineTo(px(t, r), py(t, r))
        }
        out.close()
        return out
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        ph = (System.currentTimeMillis() % 600_000L) / 600_000.0 * 2 * PI
        fit(w, h)

        val col = SurrealOpts.colors(scheme)
        var rim = col[0]; var face = col[1]; var ink = col[2]; var track = col[3]
        if (night) { rim = nightFaint; face = 0xFF0A0605.toInt(); ink = nightBright; track = nightFaint }

        // ---- rim (and drips) ----
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = rim
        cv.drawPath(shape(1.0), p)
        if (mode == "drip") drawDrips(cv, rim)

        // ---- face ----
        p.style = Paint.Style.FILL
        if (mode == "spiral" && !night) {
            // a vortex: light at the edge, falling away into darkness at the centre
            p.shader = RadialGradient(
                cx, cy, R * 0.9f,
                intArrayOf(mix(face, 0xFF000000.toInt(), 0.62f), mix(face, 0xFF000000.toInt(), 0.25f), face),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        } else {
            p.color = face
        }
        cv.drawPath(shape(0.9), p)
        p.shader = null

        if (mode == "spiral") {
            drawSpiral(cv, ink, face)
            // highlight along the upper-left of the rim so it looks raised
            p.style = Paint.Style.STROKE
            p.strokeWidth = R * 0.02f
            p.color = 0x33FFFFFF
            cv.drawArc(cx - R * 0.83f, cy - R * 0.93f, cx + R * 0.83f, cy + R * 0.93f, 200f, 110f, false, p)
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = R * 0.006f
            p.color = track
            cv.drawPath(shape(0.52), p)
        }

        // ---- numerals ----
        for (i in 0 until 12) {
            val hr = if (i == 0) 12 else i
            val t = i * PI / 6
            if (mode == "spiral") {
                // ride the spiral down into the vortex: smaller, dimmer, tipped away
                val depth = i / 11f
                val r = 0.72 - i * 0.03
                val size = R * 0.2f * (1.1f - depth * 0.55f)
                txt.textSize = size
                cv.save()
                cv.translate(px(t, r), py(t, r))
                cv.rotate(Math.toDegrees(t).toFloat() * 0.25f)
                cv.scale(1f - depth * 0.15f, 1f - depth * 0.3f)
                txt.color = 0x55000000
                cv.drawText(hr.toString(), size * 0.05f, size * 0.07f - (txt.descent() + txt.ascent()) / 2f, txt)
                txt.color = mix(ink, face, depth * 0.45f)
                cv.drawText(hr.toString(), 0f, -(txt.descent() + txt.ascent()) / 2f, txt)
                cv.restore()
            } else {
                txt.textSize = R * 0.23f * numScale[i] * wob(t).toFloat()
                txt.color = ink
                cv.save()
                cv.translate(px(t, 0.72), py(t, 0.72))
                cv.rotate(numTilt[i] + (sin(t) * 10).toFloat())
                cv.skew(numSkew[i], 0f)
                cv.scale(1f, 1f + (sag(t) * 3.2).toFloat())
                cv.drawText(hr.toString(), 0f, -(txt.descent() + txt.ascent()) / 2f, txt)
                cv.restore()
                p.style = Paint.Style.FILL
                p.color = ink
                cv.drawCircle(px(t, 0.52), py(t, 0.52), R * 0.012f, p)
            }
        }

        // ---- droopy hands ----
        val cal = Calendar.getInstance()
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000.0
        val mins = cal.get(Calendar.MINUTE) + sec / 60.0
        val hrs = (cal.get(Calendar.HOUR) % 12) + mins / 60.0
        val hx = px(0.0, 0.0)
        val hy = py(0.0, 0.0)
        // in the dark centre of the spiral, light hands read better
        val handInk = if (mode == "spiral" && !night && !isDark(face)) mix(ink, 0xFFFFFFFF.toInt(), 0.85f) else ink
        hand(cv, hx, hy, hrs * PI / 6, R * 0.38f, R * 0.028f, handInk, 0.18f)
        hand(cv, hx, hy, mins * PI / 30, R * 0.58f, R * 0.018f, handInk, 0.24f)
        if (showSeconds) hand(cv, hx, hy, sec * PI / 30, R * 0.62f, R * 0.007f, if (night) nightFaint else 0xFFC0392B.toInt(), 0.12f)
        p.style = Paint.Style.FILL
        p.color = handInk
        cv.drawCircle(hx, hy, R * 0.03f, p)
        p.color = 0xFF9A9A9A.toInt()
        cv.drawCircle(hx, hy, R * 0.012f, p)

        postInvalidateDelayed(if (showSeconds || mode == "drip") 50L else 250L)
    }

    /** Rounded drops hanging off the bottom, each with a glossy highlight; one drop falls every few seconds. */
    private fun drawDrips(cv: Canvas, rim: Int) {
        for ((k, d) in drips.withIndex()) {
            val t = Math.toRadians(d.first)
            val len = d.second * (0.92 + 0.08 * sin(ph * 3 + k))    // drips slowly grow and shrink
            val wd = d.third
            val span = wd * 1.15
            // base points on the bottom edge, sorted so "left" really is on the left
            var lx = px(t - span, 0.995); var ly = py(t - span, 0.995)
            var rx = px(t + span, 0.995); var ry = py(t + span, 0.995)
            if (lx > rx) { val tx = lx; lx = rx; rx = tx; val ty = ly; ly = ry; ry = ty }
            val bx = px(t, 1.0)
            val L = (len * R).toFloat()
            val by = py(t, 1.0) + L
            val neck = (wd * R * 0.62).toFloat()
            val bulb = (wd * R * 0.78).toFloat()
            path.reset()
            path.moveTo(lx, ly)
            path.cubicTo(lx + (bx - lx) * 0.15f, ly + L * 0.4f, bx - neck, by - L * 0.45f, bx - neck, by - bulb * 1.3f)
            path.cubicTo(bx - neck - bulb * 0.35f, by - bulb * 0.6f, bx - bulb * 1.05f, by + bulb * 0.9f, bx, by + bulb * 0.95f)
            path.cubicTo(bx + bulb * 1.05f, by + bulb * 0.9f, bx + neck + bulb * 0.35f, by - bulb * 0.6f, bx + neck, by - bulb * 1.3f)
            path.cubicTo(bx + neck, by - L * 0.45f, rx - (rx - bx) * 0.15f, ry + L * 0.4f, rx, ry)
            path.close()
            p.style = Paint.Style.FILL
            p.color = rim
            cv.drawPath(path, p)
            p.color = if (isDark(rim)) 0x55FFFFFF else 0x33000000
            cv.drawOval(bx - bulb * 0.55f, by - bulb * 0.35f, bx - bulb * 0.15f, by + bulb * 0.25f, p)
        }
        // a drop lets go of the longest drip every 7 seconds and falls away
        val d = drips[1]
        val f = (System.currentTimeMillis() % 7000L).toFloat() / 7000f
        if (f < 0.45f) {
            val t = Math.toRadians(d.first)
            val x = px(t, 1.0)
            val startY = py(t, 1.0) + (d.second * R).toFloat() + (d.third * R * 1.6).toFloat()
            val fall = f / 0.45f
            val y = startY + fall * fall * R * 0.1f
            val r = (d.third * R * 0.5).toFloat()
            if (y + r < height) {
                p.color = rim
                p.alpha = (255 * (1f - fall * 0.6f)).toInt()
                path.reset()
                path.moveTo(x, y - r * 2.2f)
                path.cubicTo(x + r * 0.5f, y - r, x + r * 1.1f, y + r, x, y + r)
                path.cubicTo(x - r * 1.1f, y + r, x - r * 0.5f, y - r, x, y - r * 2.2f)
                cv.drawPath(path, p)
                p.alpha = 255
            }
        }
    }

    /** A ribbon spiral that thins and darkens as it winds inward, with a shadow for depth. */
    private fun drawSpiral(cv: Canvas, ink: Int, face: Int) {
        val turns = 2.2
        val n = 220
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (pass in 0..1) {
            var lx = 0f
            var ly = 0f
            for (i in 0..n) {
                val f = i.toDouble() / n
                val t = f * turns * 2 * PI + ph * 0.2
                val r = 0.86 - f * 0.78
                val x = px(t, r)
                val y = py(t, r)
                if (i > 0) {
                    val wdt = R * (0.03f - f.toFloat() * 0.024f)
                    if (pass == 0) {
                        p.color = 0x40000000
                        p.strokeWidth = wdt * 1.3f
                        cv.drawLine(lx + wdt * 0.5f, ly + wdt * 0.8f, x + wdt * 0.5f, y + wdt * 0.8f, p)
                    } else {
                        p.color = mix(mix(ink, face, 0.35f), 0xFF000000.toInt(), f.toFloat() * 0.5f)
                        p.strokeWidth = wdt
                        cv.drawLine(lx, ly, x, y, p)
                    }
                }
                lx = x
                ly = y
            }
        }
    }

    private fun hand(cv: Canvas, x0: Float, y0: Float, a: Double, len: Float, width: Float, color: Int, droop: Float) {
        val ex = x0 + (sin(a) * len).toFloat()
        val ey = y0 - (cos(a) * len).toFloat()
        val horiz = abs(sin(a)).toFloat()
        path.reset()
        path.moveTo(x0, y0)
        path.quadTo((x0 + ex) / 2f, (y0 + ey) / 2f + len * droop * horiz, ex, ey + len * droop * horiz * 0.6f)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = width
        p.color = 0x30000000
        cv.save()
        cv.translate(width * 0.6f, width * 1.1f)
        cv.drawPath(path, p)
        cv.restore()
        p.color = color
        cv.drawPath(path, p)
    }

    private fun isDark(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) < 140
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(sh: Int) = ((((a shr sh) and 0xFF) * (1 - f) + ((b shr sh) and 0xFF) * f).toInt() and 0xFF) shl sh
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}
