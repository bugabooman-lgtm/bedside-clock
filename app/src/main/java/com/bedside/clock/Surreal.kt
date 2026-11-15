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
import kotlin.math.sqrt

object SurrealOpts {
    val SCHEMES = listOf(
        "classic" to "Black & white",
        "inverse" to "White & black",
        "desert" to "Desert",
        "ocean" to "Ocean",
        "sunset" to "Sunset"
    )
    val SLIMES = listOf(
        "pink" to "Pink", "lime" to "Lime", "orange" to "Orange", "purple" to "Purple", "blue" to "Blue", "red" to "Red"
    )
    fun slime(key: String): Int = when (key) {
        "lime" -> 0xFF7ED321.toInt()
        "orange" -> 0xFFFF8A1F.toInt()
        "purple" -> 0xFF8E44EC.toInt()
        "blue" -> 0xFF1E88E5.toInt()
        "red" -> 0xFFD7263D.toInt()
        else -> 0xFFE0249A.toInt()
    }

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
    var slimeKey = "pink"
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

    // Dripping face: a curtain of drips across the lower part of a round clock.
    private val dripW = doubleArrayOf(1.0, 0.85, 1.1, 1.25, 1.1, 0.85, 1.0)      // relative widths (middle drip under the 6)
    private val dripL = doubleArrayOf(0.42, 0.72, 0.50, 0.95, 0.55, 0.80, 0.40)  // lengths below the start line, in radii
    private val dripGap = 0.22                                                    // gap between drips, relative to a width
    private val dripY0 = 0.72                                                     // where the drips start (below centre)
    private val oval = android.graphics.RectF()

    // Slime face: goo anchors around the frame — clock angle (deg), drip length, drip width (radii)
    private val slimeDrips = listOf(
        Triple(98.0, 0.42, 0.075), Triple(136.0, 0.30, 0.07), Triple(166.0, 0.62, 0.09),
        Triple(194.0, 0.34, 0.07), Triple(232.0, 0.52, 0.08), Triple(262.0, 0.26, 0.065)
    )
    private val ROMAN = arrayOf("XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI")

    /** Drips as (left x, right x, length), in clock radii from the centre. */
    private fun dripLayout(): List<Triple<Double, Double, Double>> {
        val half = 1.0   // the sides run straight down, so the drips use the full width
        val k = 2 * half / (dripW.sum() + dripGap * (dripW.size - 1))
        val out = ArrayList<Triple<Double, Double, Double>>()
        var x = -half
        for (i in dripW.indices) {
            val w = dripW[i] * k
            out.add(Triple(x, x + w, dripL[i] * (0.94 + 0.06 * sin(ph * 3 + i * 1.7))))
            x += w + dripGap * k
        }
        return out
    }

    /**
     * Outline of the dripping clock, shrunk inward by [e] pixels (e = 0 is the outer edge,
     * a larger e gives the face inside the rim, so the rim follows every drip).
     */
    private fun dripPath(e: Float): Path {
        val lay = dripLayout()
        val y0 = cy + (dripY0 * R).toFloat()
        val out = Path()
        out.moveTo((cx + lay[0].first * R).toFloat() + e, y0)
        for ((i, d) in lay.withIndex()) {
            val xl = (cx + d.first * R).toFloat() + e
            val xr = (cx + d.second * R).toFloat() - e
            val rOuter = ((d.second - d.first) / 2 * R).toFloat()
            val yc = maxOf(y0, y0 + (d.third * R).toFloat() - rOuter)     // centre of the round end
            val rd = (xr - xl) / 2f
            val xc = (xl + xr) / 2f
            out.lineTo(xl, yc)
            oval.set(xc - rd, yc - rd, xc + rd, yc + rd)
            out.arcTo(oval, 180f, -180f, false)
            out.lineTo(xr, y0)
            if (i < lay.size - 1) {
                val gl = (cx + d.second * R).toFloat()
                val gr = (cx + lay[i + 1].first * R).toFloat()
                val gc = (gl + gr) / 2f
                val grad = (gr - gl) / 2f + e
                oval.set(gc - grad, y0 - grad, gc + grad, y0 + grad)
                out.arcTo(oval, 180f, 180f, false)
            }
        }
        // right side straight up, round top, left side straight down
        val rc = R - e
        out.lineTo(cx + rc, cy)
        oval.set(cx - rc, cy - rc, cx + rc, cy + rc)
        out.arcTo(oval, 0f, -180f, false)
        out.lineTo(cx - rc, y0)
        out.close()
        return out
    }

    private var cx = 0f
    private var cy = 0f
    private var R = 1f
    private var ph = 0.0

    private fun wob(t: Double): Double =
        1 + 0.11 * sin(2 * t + 0.6 + ph) + 0.075 * sin(3 * t + 1.9 - ph * 0.6) + 0.035 * sin(5 * t + 0.4 + ph * 1.3)

    private fun sag(t: Double): Double = if (mode == "melt" || mode == "slime") 0.13 * (1 - cos(t)) / 2 else 0.0

    private fun shapeScale(t: Double): Double = when (mode) {
        "spiral" -> 1.0
        "drip" -> 1.0
        else -> wob(t)   // melt and slime
    }

    /** Shape point at clock angle t (0 = 12 o'clock, clockwise) and normalised radius r, before scaling. */
    private fun ux(t: Double, r: Double): Double = sin(t) * r * shapeScale(t) * (if (mode == "drip" || mode == "spiral") 1.0 else 0.86)
    private fun uy(t: Double, r: Double): Double = -cos(t) * r * shapeScale(t) + sag(t) * r * r
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
            for (d in dripLayout()) maxY = max(maxY, dripY0 + d.third)
            maxY += 0.16   // room for the falling drop
        }
        if (mode == "slime") {
            for (i in 0 until 180) {
                val t = i * 2 * PI / 180
                minX = min(minX, ux(t, 1.14)); maxX = max(maxX, ux(t, 1.14))
                minY = min(minY, uy(t, 1.14) - 0.04); maxY = max(maxY, uy(t, 1.14))
            }
            for ((deg, len, wd) in slimeDrips) maxY = max(maxY, uy(Math.toRadians(deg), 1.1) + len + wd)
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

        if (mode == "spiral") {
            drawDroste(cv)
            postInvalidateDelayed(if (showSeconds) 50L else 120L)
            return
        }
        if (mode == "slime") {
            drawSlime(cv, night)
            postInvalidateDelayed(if (showSeconds) 50L else 250L)
            return
        }

        // ---- rim (and drips) ----
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = rim
        cv.drawPath(if (mode == "drip") dripPath(0f) else shape(1.0), p)
        if (mode == "drip") fallingDrop(cv, rim, face)

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
        cv.drawPath(if (mode == "drip") dripPath(R * 0.055f) else shape(0.9), p)
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

    /** Every 7 seconds a drop lets go of the longest drip and falls away. */
    private fun fallingDrop(cv: Canvas, rim: Int, face: Int) {
        val lay = dripLayout()
        val d = lay.maxByOrNull { it.third } ?: return
        val f = (System.currentTimeMillis() % 7000L).toFloat() / 7000f
        if (f >= 0.5f) return
        val x = (cx + (d.first + d.second) / 2 * R).toFloat()
        val r = ((d.second - d.first) * R * 0.32).toFloat()
        val tip = cy + ((dripY0 + d.third) * R).toFloat()
        val fall = f / 0.5f
        val y = tip + r * 1.6f + fall * fall * R * 0.12f
        if (y + r > height) return
        path.reset()
        path.moveTo(x, y - r * 2.1f)
        path.cubicTo(x + r * 0.5f, y - r, x + r * 1.1f, y + r, x, y + r)
        path.cubicTo(x - r * 1.1f, y + r, x - r * 0.5f, y - r, x, y - r * 2.1f)
        val a = (255 * (1f - fall * 0.7f)).toInt()
        p.style = Paint.Style.FILL
        p.color = rim
        p.alpha = a
        cv.drawPath(path, p)
        p.color = face
        p.alpha = a
        cv.save()
        cv.translate(x, y)
        cv.scale(0.55f, 0.55f)
        cv.translate(-x, -y)
        cv.drawPath(path, p)
        cv.restore()
        p.alpha = 255
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

    // ---------------- slime face ----------------

    /** A warped gold-framed clock with glossy slime oozing over it and dripping off. */
    private fun drawSlime(cv: Canvas, night: Boolean) {
        val goo = if (night) nightFaint else SurrealOpts.slime(slimeKey)
        val gooLight = mix(goo, 0xFFFFFFFF.toInt(), 0.45f)
        val gooDark = mix(goo, 0xFF000000.toInt(), 0.3f)
        val ink = if (night) nightBright else 0xFF141414.toInt()

        // goo behind the frame, a little bigger and lumpier
        val gooPath = Path()
        val n = 180
        for (i in 0..n) {
            val t = i * 2 * PI / n
            val r = 1.1 + 0.03 * sin(5 * t + ph * 2) + 0.02 * sin(9 * t - ph)
            val x = px(t, r); val y = py(t, r) - (if (cos(t) > 0) (0.04 * cos(t) * R).toFloat() else 0f)
            if (i == 0) gooPath.moveTo(x, y) else gooPath.lineTo(x, y)
        }
        gooPath.close()
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = goo
        cv.drawPath(gooPath, p)
        for ((k, d) in slimeDrips.withIndex()) {
            val t = Math.toRadians(d.first)
            val len = d.second * (0.9 + 0.1 * sin(ph * 3 + k * 1.3))
            slimeDrip(cv, px(t, 1.03), py(t, 1.03), (d.third * R).toFloat(), (len * R).toFloat(), goo, gooDark, gooLight)
        }
        // glossy streak along the goo's upper left
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = R * 0.025f
        p.color = (gooLight and 0x00FFFFFF) or (0x99 shl 24)
        path.reset()
        for (i in 0..30) {
            val t = Math.toRadians(250.0 + i * 3.0)
            val x = px(t, 1.06); val y = py(t, 1.06)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        cv.drawPath(path, p)

        // gold frame
        val frame = shape(1.0)
        p.style = Paint.Style.FILL
        p.shader = if (night) null else android.graphics.LinearGradient(
            cx - R, cy - R, cx + R, cy + R,
            intArrayOf(0xFFFBE7A1.toInt(), 0xFFD4A23A.toInt(), 0xFF9A6A18.toInt(), 0xFFE8C063.toInt(), 0xFF8A5E14.toInt()),
            null, Shader.TileMode.CLAMP
        )
        p.color = if (night) mix(nightFaint, 0xFF000000.toInt(), 0.4f) else 0xFFD4A23A.toInt()
        cv.drawPath(frame, p)
        p.shader = null

        // glossy pale-blue face
        val faceP = shape(0.9)
        p.shader = if (night) null else RadialGradient(
            cx - R * 0.2f, cy - R * 0.3f, R * 1.1f,
            intArrayOf(0xFFE4F6FD.toInt(), 0xFFB9DFEF.toInt(), 0xFF8FC4DA.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        p.color = if (night) 0xFF0A0605.toInt() else 0xFFB9DFEF.toInt()
        cv.drawPath(faceP, p)
        p.shader = null

        // minute ticks
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.color = ink
        for (i in 0 until 60) {
            val t = i * PI / 30
            p.strokeWidth = R * (if (i % 5 == 0) 0.012f else 0.005f)
            cv.drawLine(px(t, 0.84), py(t, 0.84), px(t, if (i % 5 == 0) 0.79 else 0.815), py(t, if (i % 5 == 0) 0.79 else 0.815), p)
        }

        // Roman numerals, turned to face the centre like an old clock
        txt.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        txt.color = ink
        for (i in 0 until 12) {
            val t = i * PI / 6
            txt.textSize = R * 0.13f * wob(t).toFloat()
            cv.save()
            cv.translate(px(t, 0.66), py(t, 0.66))
            cv.rotate(Math.toDegrees(t).toFloat())
            cv.scale(1f, 1f + (sag(t) * 2.5).toFloat())
            cv.drawText(ROMAN[i], 0f, -(txt.descent() + txt.ascent()) / 2f, txt)
            cv.restore()
        }
        txt.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)

        // glass shine
        if (!night) {
            cv.save()
            cv.clipPath(faceP)
            p.style = Paint.Style.FILL
            p.color = 0x40FFFFFF
            cv.drawOval(cx - R * 0.75f, cy - R * 0.95f, cx + R * 0.2f, cy - R * 0.25f, p)
            cv.restore()
        }

        // hands
        val cal = Calendar.getInstance()
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000.0
        val mins = cal.get(Calendar.MINUTE) + sec / 60.0
        val hrs = (cal.get(Calendar.HOUR) % 12) + mins / 60.0
        val hx = px(0.0, 0.0); val hy = py(0.0, 0.0)
        hand(cv, hx, hy, hrs * PI / 6, R * 0.36f, R * 0.035f, ink, 0.08f)
        hand(cv, hx, hy, mins * PI / 30, R * 0.58f, R * 0.022f, ink, 0.1f)
        if (showSeconds) hand(cv, hx, hy, sec * PI / 30, R * 0.62f, R * 0.007f, if (night) nightFaint else goo, 0.05f)
        p.style = Paint.Style.FILL
        p.color = if (night) nightFaint else 0xFFD4A23A.toInt()
        cv.drawCircle(hx, hy, R * 0.04f, p)
        p.color = if (night) 0xFF000000.toInt() else 0xFF8A5E14.toInt()
        cv.drawCircle(hx, hy, R * 0.015f, p)
    }

    /** One glossy drip hanging straight down from an anchor point on the goo. */
    private fun slimeDrip(cv: Canvas, ax: Float, ay: Float, wd: Float, len: Float, goo: Int, dark: Int, light: Int) {
        val base = wd * 1.25f
        val neck = wd * 0.5f
        val bulb = wd * 0.68f
        val by = ay + len
        path.reset()
        path.moveTo(ax - base, ay - wd)
        path.cubicTo(ax - base * 0.85f, ay + len * 0.4f, ax - neck, by - len * 0.45f, ax - neck, by - bulb * 1.3f)
        path.cubicTo(ax - neck - bulb * 0.35f, by - bulb * 0.6f, ax - bulb * 1.05f, by + bulb * 0.9f, ax, by + bulb * 0.95f)
        path.cubicTo(ax + bulb * 1.05f, by + bulb * 0.9f, ax + neck + bulb * 0.35f, by - bulb * 0.6f, ax + neck, by - bulb * 1.3f)
        path.cubicTo(ax + neck, by - len * 0.45f, ax + base * 0.85f, ay + len * 0.4f, ax + base, ay - wd)
        path.close()
        p.style = Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(ax - base, 0f, ax + base, 0f,
            intArrayOf(goo, goo, dark), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, p)
        p.shader = null
        // highlights: a streak down the column and a dot on the drop
        p.color = (light and 0x00FFFFFF) or (0x88 shl 24)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = wd * 0.18f
        cv.drawLine(ax - neck * 0.45f, ay + wd * 0.5f, ax - neck * 0.45f, by - bulb * 1.6f, p)
        p.style = Paint.Style.FILL
        cv.drawOval(ax - bulb * 0.55f, by - bulb * 0.3f, ax - bulb * 0.15f, by + bulb * 0.3f, p)
    }

    // ---------------- endless spiral ("Droste") face ----------------

    private val stars = Random(77).let { r -> List(90) { floatArrayOf(r.nextFloat(), r.nextFloat() * 6.2832f, 0.4f + r.nextFloat(), r.nextFloat() * 6.28f) } }

    /** band, ink, sky for each scheme */
    private fun drosteColors(): IntArray {
        if (night) return intArrayOf(0xFF0A0605.toInt(), nightBright, 0xFF000000.toInt())
        return when (scheme) {
            "inverse" -> intArrayOf(0xFF1C1C1E.toInt(), 0xFFEDEDED.toInt(), 0xFF2E3D6B.toInt())
            "desert" -> intArrayOf(0xFFEFE3C8.toInt(), 0xFF3B2414.toInt(), 0xFF2A1A10.toInt())
            "ocean" -> intArrayOf(0xFFE3F1F7.toInt(), 0xFF0E2A40.toInt(), 0xFF06243F.toInt())
            "sunset" -> intArrayOf(0xFFFFE6C7.toInt(), 0xFF4A1028.toInt(), 0xFF2E0B24.toInt())
            else -> intArrayOf(0xFFF4F2EC.toInt(), 0xFF1A1A1A.toInt(), 0xFF0E1B3D.toInt())
        }
    }

    /** The dial winds inward forever, each turn about half the size of the last, over a starry sky. */
    private fun drawDroste(cv: Canvas) {
        val (band, ink, sky) = drosteColors().let { Triple(it[0], it[1], it[2]) }
        val q = 0.45                                  // size of each turn relative to the one outside it
        val b = -kotlin.math.ln(q) / (2 * PI)
        fun ro(th: Double) = (R * kotlin.math.exp(-b * th)).toFloat()
        fun x(th: Double, r: Float) = cx + (sin(th) * r).toFloat()
        fun y(th: Double, r: Float) = cy - (cos(th) * r).toFloat()
        val inner = 0.62f
        val turns = 6

        // sky with stars
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, R, intArrayOf(mix(sky, 0xFF4A6AB0.toInt(), if (night) 0f else 0.35f), sky, mix(sky, 0xFF000000.toInt(), 0.4f)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, R, p)
        p.shader = null
        val now = System.currentTimeMillis() / 1000.0
        for (st in stars) {
            val rr = st[0] * R
            val tw = (0.55 + 0.45 * sin(now * st[2] + st[3])).toFloat()
            p.color = if (night) nightFaint else 0xFFFFFFFF.toInt()
            p.alpha = (tw * 200).toInt()
            cv.drawCircle(cx + (cos(st[1].toDouble()) * rr).toFloat(), cy + (sin(st[1].toDouble()) * rr).toFloat(), R * 0.004f * st[2], p)
        }
        p.alpha = 255

        // the spiral band starts one turn outside the visible circle, so the dial fills the face
        cv.save()
        val clip = Path()
        clip.addCircle(cx, cy, R, Path.Direction.CW)
        cv.clipPath(clip)
        val minTh = -2 * PI
        val steps = 140 * (turns + 1)
        val maxTh = 2 * PI * turns
        fun thAt(i: Int) = minTh + (maxTh - minTh) * i / steps
        val bandPath = Path()
        for (i in 0..steps) {
            val th = thAt(i)
            val r = ro(th)
            if (i == 0) bandPath.moveTo(x(th, r), y(th, r)) else bandPath.lineTo(x(th, r), y(th, r))
        }
        for (i in steps downTo 0) {
            val th = thAt(i)
            val r = ro(th) * inner
            bandPath.lineTo(x(th, r), y(th, r))
        }
        bandPath.close()
        // shadow the band casts on the sky inside it
        cv.save()
        cv.translate(R * 0.012f, R * 0.02f)
        p.color = 0x66000000
        cv.drawPath(bandPath, p)
        cv.restore()
        p.color = band
        cv.drawPath(bandPath, p)

        // depth: darken toward the inner edge, as if the band curls under
        cv.save()
        cv.clipPath(bandPath)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        var lx = 0f; var ly = 0f
        for (i in 0..steps) {
            val th = thAt(i)
            val r = ro(th) * inner
            val xx = x(th, r); val yy = y(th, r)
            if (i > 0) {
                p.color = 0x2A000000
                p.strokeWidth = ro(th) * 0.14f
                cv.drawLine(lx, ly, xx, yy, p)
            }
            lx = xx; ly = yy
        }
        cv.restore()

        // inner track line
        p.style = Paint.Style.STROKE
        p.color = ink
        lx = 0f; ly = 0f
        for (i in 0..steps) {
            val th = thAt(i)
            val r = ro(th) * 0.665f
            val xx = x(th, r); val yy = y(th, r)
            if (i > 0) { p.strokeWidth = ro(th) * 0.006f; cv.drawLine(lx, ly, xx, yy, p) }
            lx = xx; ly = yy
        }

        // minute marks
        for (j in -60 until 60 * turns) {
            val th = j * PI / 30
            val r = ro(th)
            if (r < R * 0.03f) break
            val major = j % 5 == 0
            p.strokeWidth = r * (if (major) 0.014f else 0.006f)
            p.color = ink
            cv.drawLine(x(th, r * 0.93f), y(th, r * 0.93f), x(th, r * (if (major) 0.85f else 0.895f)), y(th, r * (if (major) 0.85f else 0.895f)), p)
        }

        // Roman numerals on every turn, shrinking into the centre
        txt.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        txt.color = ink
        loop@ for (n in -1 until turns) {
            for (i in 0 until 12) {
                val a = i * PI / 6
                val th = a + 2 * PI * n
                val r = ro(th)
                if (r < R * 0.04f) break@loop
                txt.textSize = r * 0.115f
                cv.save()
                cv.translate(x(th, r * 0.765f), y(th, r * 0.765f))
                cv.rotate(Math.toDegrees(a).toFloat())
                cv.drawText(ROMAN[i], 0f, -(txt.descent() + txt.ascent()) / 2f, txt)
                cv.restore()
            }
        }
        txt.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)

        // polished silver edge along the outside of the band
        lx = 0f; ly = 0f
        for (i in 0..steps) {
            val th = thAt(i)
            val r = ro(th)
            val xx = x(th, r); val yy = y(th, r)
            if (i > 0) {
                val shine = (0.5 + 0.5 * sin(th + 0.9)).toFloat()
                p.color = if (night) nightFaint else mix(0xFF6E7378.toInt(), 0xFFF2F4F6.toInt(), shine)
                p.strokeWidth = r * 0.035f
                cv.drawLine(lx, ly, xx, yy, p)
            }
            lx = xx; ly = yy
        }

        cv.restore()
        // polished bezel around the whole face
        p.style = Paint.Style.STROKE
        p.strokeWidth = R * 0.03f
        p.shader = if (night) null else android.graphics.SweepGradient(cx, cy,
            intArrayOf(0xFF8E9196.toInt(), 0xFFF2F4F6.toInt(), 0xFF6B6E73.toInt(), 0xFFD5D8DC.toInt(), 0xFF8E9196.toInt()), null)
        p.color = if (night) nightFaint else 0xFFB0B3B8.toInt()
        cv.drawCircle(cx, cy, R * 0.985f, p)
        p.shader = null

        // hands: light with a dark edge so they read on both the dial and the sky
        val cal = Calendar.getInstance()
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000.0
        val mins = cal.get(Calendar.MINUTE) + sec / 60.0
        val hrs = (cal.get(Calendar.HOUR) % 12) + mins / 60.0
        val light = if (night) nightBright else 0xFFF6F6F6.toInt()
        for ((ang, len, w) in listOf(Triple(hrs * PI / 6, 0.5f, 0.035f), Triple(mins * PI / 30, 0.8f, 0.024f))) {
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.color = 0xCC000000.toInt()
            p.strokeWidth = R * w * 1.6f
            cv.drawLine(cx, cy, x(ang, R * len), y(ang, R * len), p)
            p.color = light
            p.strokeWidth = R * w
            cv.drawLine(cx, cy, x(ang, R * len), y(ang, R * len), p)
        }
        if (showSeconds) {
            p.color = if (night) nightFaint else 0xFFC0392B.toInt()
            p.strokeWidth = R * 0.008f
            cv.drawLine(x(sec * PI / 30 + PI, R * 0.15f), y(sec * PI / 30 + PI, R * 0.15f), x(sec * PI / 30, R * 0.88f), y(sec * PI / 30, R * 0.88f), p)
        }
        p.style = Paint.Style.FILL
        p.color = light
        cv.drawCircle(cx, cy, R * 0.035f, p)
        p.color = 0xFF555555.toInt()
        cv.drawCircle(cx, cy, R * 0.013f, p)
    }
}
