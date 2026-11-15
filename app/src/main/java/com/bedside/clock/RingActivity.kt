package com.bedside.clock

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/** Full-screen "wake up" screen: big Snooze, hold-to-turn-off. */
class RingActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timeView: TextView

    private val watch = object : Runnable {
        override fun run() {
            if (!AlarmService.running) {
                finish()
                return
            }
            updateTime()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 1f
        window.attributes = lp

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF120B06.toInt())
        root.setPadding(dp(28), dp(16), dp(28), dp(24))

        timeView = tv("", 96f, 0xFFFFF2DF.toInt(), light = true)
        timeView.gravity = Gravity.CENTER
        val label = tv(AlarmService.currentLabel, 30f, 0xFFFFCF9A.toInt())
        label.gravity = Gravity.CENTER
        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.gravity = Gravity.CENTER
        top.addView(timeView)
        top.addView(label)
        root.addView(top, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val snoozeText = "Snooze\n${Store.snoozeMin(this)} minutes"
        val span = SpannableString(snoozeText)
        span.setSpan(RelativeSizeSpan(0.4f), snoozeText.indexOf('\n'), snoozeText.length, 0)
        val snooze = tv(span, 48f, 0xFF2A1606.toInt())
        snooze.typeface = Typeface.DEFAULT_BOLD
        snooze.gravity = Gravity.CENTER
        snooze.background = roundBg(Col.ACCENT, dp(28).toFloat())
        snooze.setOnClickListener {
            AlarmService.send(this, AlarmService.ACTION_SNOOZE)
            finish()
        }
        root.addView(snooze, LinearLayout.LayoutParams(MATCH, 0, 1.1f).apply { bottomMargin = dp(18) })

        val hold = HoldButton(this, "Hold to turn off") {
            AlarmService.send(this, AlarmService.ACTION_DISMISS)
            finish()
        }
        root.addView(hold, LinearLayout.LayoutParams(MATCH, dp(90)))
        setContentView(root)
        updateTime()
    }

    private fun updateTime() {
        val c = Calendar.getInstance()
        timeView.text = fmtStr(this, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    override fun onResume() {
        super.onResume()
        immersive()
        handler.post(watch)
    }

    override fun onPause() {
        handler.removeCallbacks(watch)
        super.onPause()
    }

    @Deprecated("Back is ignored while ringing")
    override fun onBackPressed() {
        // ignore: use Snooze or hold to turn off
    }
}

/** A long pill that must be held for 1.5 s, so the alarm can't be switched off by a sleepy tap. */
class HoldButton(c: Context, private val caption: String, private val onDone: () -> Unit) : View(c) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B1A10.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5A3A22.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FF9A5A.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFCF9A.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 26f * resources.displayMetrics.density
    }
    private var downAt = 0L
    private var done = false
    private val holdMs = 1500L

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        canvas.drawRoundRect(0f, 0f, w, h, r, r, bg)
        canvas.drawRoundRect(1f, 1f, w - 1f, h - 1f, r, r, edge)
        if (downAt > 0 && !done) {
            val p = ((System.currentTimeMillis() - downAt).toFloat() / holdMs).coerceIn(0f, 1f)
            canvas.save()
            canvas.clipRect(0f, 0f, w * p, h)
            canvas.drawRoundRect(0f, 0f, w, h, r, r, fill)
            canvas.restore()
            if (p >= 1f) {
                done = true
                post { onDone() }
            } else {
                postInvalidateOnAnimation()
            }
        }
        canvas.drawText(caption, w / 2f, h / 2f - (txt.descent() + txt.ascent()) / 2f, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downAt = System.currentTimeMillis(); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { downAt = 0L; invalidate() }
        }
        return true
    }
}
