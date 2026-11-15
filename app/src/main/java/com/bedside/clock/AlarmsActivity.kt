package com.bedside.clock

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Calendar

class AlarmsActivity : Activity() {
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var previewTrack: AudioTrack? = null
    private var previewRing: Ringtone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Col.BG)
        root.setPadding(dp(40), dp(24), dp(40), dp(16))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(button("‹  Back") { finish() })
        val title = tv("Alarms", 30f)
        title.setPadding(dp(22), 0, 0, 0)
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(button("+  Add alarm", primary = true) { edit(null) })
        root.addView(header)

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(12) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        immersive()
        render()
    }

    override fun onPause() {
        stopPreview()
        super.onPause()
    }

    private fun describe(a: Alarm): String {
        val parts = mutableListOf(daysText(a.days), toneName(a))
        if (a.rampMin > 0) parts.add("${a.rampMin} min gentle wake")
        if (a.sunriseMin > 0) parts.add("${a.sunriseMin} min sunrise")
        return parts.joinToString(" · ")
    }

    private fun render() {
        list.removeAllViews()
        val alarms = Store.alarms(this).sortedBy { it.hour * 60 + it.minute }
        if (alarms.isEmpty()) {
            val empty = tv("No alarms yet. Tap “+ Add alarm” to set one.", 20f, Col.DIM)
            empty.setPadding(0, dp(40), 0, 0)
            list.addView(empty)
            return
        }
        for (a in alarms) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(6), dp(16), dp(6), dp(16))
            row.alpha = if (a.enabled) 1f else 0.45f

            val t = tv(fmtStr(this, a.hour, a.minute), 46f, light = true)
            row.addView(t, LinearLayout.LayoutParams(dp(250), WRAP))
            val meta = LinearLayout(this)
            meta.orientation = LinearLayout.VERTICAL
            meta.addView(tv(a.label.ifBlank { "Alarm" }, 20f))
            meta.addView(tv(describe(a), 15f, Col.DIM))
            row.addView(meta, LinearLayout.LayoutParams(0, WRAP, 1f))

            val sw = Switch(this)
            sw.isChecked = a.enabled
            sw.scaleX = 1.4f
            sw.scaleY = 1.4f
            sw.setOnCheckedChangeListener { _, on -> setEnabled(a.id, on) }
            row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(12) })
            row.setOnClickListener { edit(a) }
            list.addView(row)
            list.addView(divider(), LinearLayout.LayoutParams(MATCH, 1))
        }
    }

    private fun setEnabled(id: Long, on: Boolean) {
        val all = Store.alarms(this)
        val a = all.firstOrNull { it.id == id } ?: return
        a.enabled = on
        if (!on && Store.snoozeId(this) == id) Store.clearSnooze(this)
        Store.saveAlarms(this, all)
        Scheduler.reschedule(this)
        if (on) a.nextTrigger()?.let { toast("Alarm set for " + untilText(it)) }
        handler.post { render() }
    }

    private fun edit(existing: Alarm?) {
        val d = existing?.copy(days = existing.days.toMutableSet()) ?: Alarm.newDefault()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(28), dp(18), dp(28), dp(4))

        val timeView = tv("", 60f, Col.TEXT, light = true)
        timeView.background = roundBg(Col.PANEL2, dp(16).toFloat(), Col.LINE, dp(1))
        timeView.setPadding(dp(18), dp(2), dp(18), dp(2))
        fun showTime() { timeView.text = fmtStr(this, d.hour, d.minute) }
        showTime()
        timeView.setOnClickListener {
            TimePickerDialog(this, { _, hh, mm -> d.hour = hh; d.minute = mm; showTime() }, d.hour, d.minute, Store.is24(this)).show()
        }
        val labelEdit = EditText(this)
        labelEdit.setText(d.label)
        labelEdit.hint = "Label (optional)"
        labelEdit.setTextColor(Col.TEXT)
        labelEdit.setHintTextColor(Col.DIM)
        labelEdit.textSize = 20f
        labelEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        labelEdit.isSingleLine = true
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(timeView)
        top.addView(labelEdit, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(24) })
        box.addView(top)
        box.addView(tv("Tap the time to change it", 14f, Col.DIM).apply { setPadding(dp(4), dp(6), 0, dp(4)) })

        val daysRow = LinearLayout(this)
        daysRow.orientation = LinearLayout.HORIZONTAL
        val order = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        val letters = "MTWTFSS"
        order.forEachIndexed { i, day ->
            val v = tv(letters[i].toString(), 17f)
            v.gravity = Gravity.CENTER
            fun style(view: TextView) {
                val on = d.days.contains(day)
                view.background = roundBg(if (on) Col.ACCENT else Col.PANEL2, dp(26).toFloat())
                view.setTextColor(if (on) Col.ON_ACCENT else Col.TEXT)
            }
            style(v)
            v.setOnClickListener {
                if (!d.days.remove(day)) d.days.add(day)
                style(v)
            }
            daysRow.addView(v, LinearLayout.LayoutParams(dp(50), dp(50)).apply { rightMargin = dp(7) })
        }
        box.addView(settingRow("Repeat", "None picked = rings once", daysRow))
        lateinit var soundBtn: TextView
        soundBtn = button(toneName(d) + "  ▾") {
            pickSound(d) { soundBtn.text = toneName(d) + "  ▾" }
        }
        box.addView(settingRow("Sound", "Tap to hear and choose", soundBtn))
        box.addView(settingRow("Gentle wake", "Volume rises slowly",
            choice(listOf(0 to "Off", 1 to "1 min", 3 to "3 min", 5 to "5 min"), d.rampMin) { d.rampMin = it }))
        box.addView(settingRow("Sunrise", "Screen glows brighter before the alarm",
            choice(listOf(0 to "Off", 10 to "10 min", 20 to "20 min", 30 to "30 min"), d.sunriseMin) { d.sunriseMin = it }))

        val scroll = ScrollView(this)
        scroll.addView(box)
        val dlg = AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("Save") { _, _ -> save(d, labelEdit.text.toString()) }
            .setNeutralButton("Preview sound", null)
            .setNegativeButton(if (existing != null) "Delete" else "Cancel") { _, _ ->
                if (existing != null) delete(existing.id)
            }
            .create()
        dlg.setOnDismissListener { stopPreview() }
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { preview(d.tone, d.toneUri) }
    }

    private fun save(d: Alarm, label: String) {
        stopPreview()
        d.label = label.trim()
        d.enabled = true
        val all = Store.alarms(this)
        val i = all.indexOfFirst { it.id == d.id }
        if (i >= 0) all[i] = d else all.add(d)
        Store.saveAlarms(this, all)
        Scheduler.reschedule(this)
        render()
        d.nextTrigger()?.let { toast("Alarm set for " + untilText(it)) }
    }

    private fun delete(id: Long) {
        Store.saveAlarms(this, Store.alarms(this).filter { it.id != id })
        if (Store.snoozeId(this) == id) Store.clearSnooze(this)
        Scheduler.reschedule(this)
        render()
    }

    private fun preview(tone: String, uri: String = "") {
        stopPreview()
        try {
            if (tone == "system" || tone == "device" || tone == "file") {
                val u = if (tone != "system" && uri.isNotEmpty()) Uri.parse(uri)
                        else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val r = RingtoneManager.getRingtone(this, u)
                r?.play()
                previewRing = r
            } else {
                val t = Synth.loopingTrack(tone, AudioAttributes.USAGE_ALARM)
                t.play()
                previewTrack = t
            }
        } catch (_: Exception) {}
        handler.postDelayed({ stopPreview() }, 6000)
    }

    private fun stopPreview() {
        handler.removeCallbacksAndMessages(null)
        previewTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        previewTrack = null
        previewRing?.stop()
        previewRing = null
    }

    // ---------------- choosing a sound ----------------

    private var pickTarget: Alarm? = null
    private var pickDone: (() -> Unit)? = null

    /** Scrollable list of every sound; tapping one plays a preview. */
    private fun pickSound(d: Alarm, done: () -> Unit) {
        val keys = TONES.map { it.first }
        val names = TONES.map { it.second }.toTypedArray()
        var picked = keys.indexOf(d.tone).coerceAtLeast(0)
        val prevTone = d.tone
        val prevUri = d.toneUri
        val prevName = d.toneName
        AlertDialog.Builder(this)
            .setTitle("Alarm sound")
            .setSingleChoiceItems(names, picked) { dialog, which ->
                picked = which
                when (val key = keys[which]) {
                    "device" -> {
                        dialog.dismiss()
                        pickTarget = d; pickDone = done
                        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE or RingtoneManager.TYPE_NOTIFICATION)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                        if (d.tone == "device" && d.toneUri.isNotEmpty()) {
                            i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(d.toneUri))
                        }
                        try { startActivityForResult(i, REQ_RINGTONE) } catch (_: Exception) { toast("No ringtone picker on this tablet") }
                    }
                    "file" -> {
                        dialog.dismiss()
                        pickTarget = d; pickDone = done
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("audio/*")
                        try { startActivityForResult(i, REQ_FILE) } catch (_: Exception) { toast("Couldn't open your files") }
                    }
                    else -> {
                        d.tone = key
                        d.toneUri = ""
                        d.toneName = ""
                        preview(key)
                    }
                }
            }
            .setPositiveButton("Use this sound") { _, _ -> stopPreview(); done() }
            .setNegativeButton("Cancel") { _, _ ->
                stopPreview()
                d.tone = prevTone; d.toneUri = prevUri; d.toneName = prevName
                done()
            }
            .setOnDismissListener { stopPreview() }
            .show()
    }

    @Deprecated("Uses the platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val d = pickTarget ?: return
        val done = pickDone
        pickTarget = null
        pickDone = null
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_RINGTONE) {
            @Suppress("DEPRECATION")
            val uri = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return
            d.tone = "device"
            d.toneUri = uri.toString()
            d.toneName = try { RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Tablet sound" } catch (_: Exception) { "Tablet sound" }
        } else if (requestCode == REQ_FILE) {
            val uri = data.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            d.tone = "file"
            d.toneUri = uri.toString()
            d.toneName = fileName(uri)
        }
        done?.invoke()
        preview(d.tone, d.toneUri)
    }

    private fun fileName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0) ?: return "My music"
                    return name.substringBeforeLast('.')
                }
            }
        } catch (_: Exception) {}
        return "My music"
    }

    companion object {
        private const val REQ_RINGTONE = 21
        private const val REQ_FILE = 22
    }
}
