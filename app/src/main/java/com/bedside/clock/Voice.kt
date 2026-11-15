package com.bedside.clock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import java.util.Calendar

/**
 * Understands simple spoken commands, e.g.
 *  "set an alarm for 6 am", "wake me up at 6:30 every weekday", "alarm in 20 minutes",
 *  "cancel my 6 am alarm", "turn off all alarms", "when is my alarm",
 *  "play rain sounds", "stop the sounds".
 */
object Voice {
    sealed class Cmd {
        class SetAlarm(val hour: Int, val minute: Int, val days: Set<Int>, val label: String) : Cmd()
        class SetIn(val minutes: Int) : Cmd()
        class Cancel(val hour: Int?, val minute: Int?, val all: Boolean) : Cmd()
        object NextAlarm : Cmd()
        class Sleep(val type: String) : Cmd()
        object StopSleep : Cmd()
        object Unknown : Cmd()
    }

    private val WORD_NUMS = listOf(
        "twelve" to "12", "eleven" to "11", "ten" to "10", "nine" to "9", "eight" to "8", "seven" to "7",
        "six" to "6", "five" to "5", "four" to "4", "three" to "3", "two" to "2", "one" to "1"
    )
    private val DAY_WORDS = listOf(
        "sunday" to Calendar.SUNDAY, "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY
    )

    private fun normalize(raw: String): String {
        var t = " " + raw.lowercase().replace(Regex("[,!?]"), " ") + " "
        t = t.replace("a.m.", "am").replace("p.m.", "pm").replace(" a m ", " am ").replace(" p m ", " pm ")
        t = t.replace("o'clock", "").replace("o clock", "")
        t = t.replace(" noon", " 12 pm").replace(" midnight", " 12 am")
        t = t.replace("forty five", "45").replace("forty-five", "45").replace("thirty", "30")
            .replace("fifteen", "15").replace("an hour", "1 hour").replace("a minute", "1 minute")
        for ((w, d) in WORD_NUMS) t = t.replace(Regex("\\b$w\\b"), d)
        // "half past 6" / "quarter past 6"
        t = Regex("half past (\\d{1,2})").replace(t) { "${it.groupValues[1]}:30" }
        t = Regex("quarter past (\\d{1,2})").replace(t) { "${it.groupValues[1]}:15" }
        t = Regex("(\\d{1,2})\\s*(am|pm)\\b").replace(t) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        return t.replace(Regex("\\s+"), " ")
    }

    /** Finds a time of day. Returns (hour, minute, hadAmPm) or null. */
    private fun findTime(t: String): Triple<Int, Int, Boolean>? {
        val withAmPm = Regex("\\b(\\d{1,2})(?:[: ](\\d{2}))?\\s*(am|pm)\\b").find(t)
        if (withAmPm != null) {
            var h = withAmPm.groupValues[1].toInt()
            val m = withAmPm.groupValues[2].ifEmpty { "0" }.toInt()
            val pm = withAmPm.groupValues[3] == "pm"
            if (h == 12) h = 0
            if (pm) h += 12
            if (h in 0..23 && m in 0..59) return Triple(h, m, true)
        }
        val colon = Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(t)
        if (colon != null) {
            val h = colon.groupValues[1].toInt()
            val m = colon.groupValues[2].toInt()
            if (h in 0..23 && m in 0..59) return Triple(h, m, h > 12 || h == 0)
        }
        val after = Regex("\\b(?:at|for|to|alarm)\\s+(\\d{1,2})(?:\\s+(\\d{2}))?\\b").find(t)
        if (after != null) {
            val h = after.groupValues[1].toInt()
            val m = after.groupValues[2].ifEmpty { "0" }.toInt()
            if (h in 0..23 && m in 0..59) return Triple(h, m, h > 12 || h == 0)
        }
        return null
    }

    /** With no AM/PM, pick whichever of 6:00 / 18:00 comes up next. */
    private fun resolveHour(h: Int, m: Int, hadAmPm: Boolean): Int {
        if (hadAmPm || h > 12) return h
        val a = h % 12
        val b = a + 12
        val ta = Alarm(0, a, m, mutableSetOf(), "", "", 0, 0, true).nextTrigger() ?: 0L
        val tb = Alarm(0, b, m, mutableSetOf(), "", "", 0, 0, true).nextTrigger() ?: 0L
        return if (ta <= tb) a else b
    }

    private fun findDays(t: String): Set<Int> {
        if (Regex("\\b(every ?day|daily|each day)\\b").containsMatchIn(t)) return (1..7).toSet()
        if (t.contains("weekday") || t.contains("work day") || t.contains("workday")) return setOf(2, 3, 4, 5, 6)
        if (t.contains("weekend")) return setOf(1, 7)
        val repeating = t.contains("every") || DAY_WORDS.any { t.contains(it.first + "s") }
        if (!repeating) return emptySet()
        return DAY_WORDS.filter { t.contains(it.first) }.map { it.second }.toSet()
    }

    fun parse(raw: String): Cmd {
        val t = normalize(raw)
        val sleepTypes = listOf("rain" to "rain", "ocean" to "ocean", "wave" to "ocean", "fan" to "fan", "noise" to "brown")
        if (Regex("\\b(stop|turn off|end)\\b.*\\b(sound|sounds|music|noise|rain|ocean|fan|waves)\\b").containsMatchIn(t)) return Cmd.StopSleep
        if (t.contains("sleep sound") || Regex("\\bplay\\b").containsMatchIn(t)) {
            val type = sleepTypes.firstOrNull { t.contains(it.first) }?.second
            if (type != null || t.contains("sleep sound")) return Cmd.Sleep(type ?: "")
        }
        if ((t.contains("when") || t.contains("what time") || t.contains("next alarm")) && t.contains("alarm") &&
            !Regex("\\b(set|make|create)\\b").containsMatchIn(t)
        ) return Cmd.NextAlarm

        if (Regex("\\b(cancel|delete|remove|turn off|disable|clear|switch off)\\b").containsMatchIn(t) && t.contains("alarm")) {
            if (Regex("\\b(all|every)\\b").containsMatchIn(t)) return Cmd.Cancel(null, null, true)
            val tm = findTime(t) ?: return Cmd.Cancel(null, null, false)
            return Cmd.Cancel(tm.first, tm.second, false)
        }

        val inMatch = Regex("\\bin (\\d{1,3}) (minute|minutes|min|hour|hours)\\b").find(t)
        if (inMatch != null) {
            val n = inMatch.groupValues[1].toInt()
            val mins = if (inMatch.groupValues[2].startsWith("hour")) n * 60 else n
            if (mins in 1..(24 * 60)) return Cmd.SetIn(mins)
        }

        val tm = findTime(t) ?: return Cmd.Unknown
        val label = Regex("\\b(?:called|named|labeled|label it)\\s+(.+)$").find(t)?.groupValues?.get(1)?.trim()
            ?.replaceFirstChar { it.uppercase() } ?: ""
        return Cmd.SetAlarm(resolveHour(tm.first, tm.second, tm.third), tm.second, findDays(t), label)
    }

    /** Creates the alarm, or switches on an identical existing one. */
    fun addOrEnable(c: Context, h: Int, m: Int, days: Set<Int>, label: String): Alarm {
        val all = Store.alarms(c)
        val existing = all.firstOrNull { it.hour == h && it.minute == m && it.days == days }
        val a = existing ?: Alarm.newDefault().also {
            it.hour = h; it.minute = m; it.days = days.toMutableSet(); it.label = label
        }
        a.enabled = true
        if (label.isNotEmpty()) a.label = label
        if (existing == null) all.add(a)
        Store.saveAlarms(c, all)
        Scheduler.reschedule(c)
        return a
    }

    private fun spokenTime(c: Context, h: Int, m: Int): String = fmtStr(c, h, m)

    /** Carries out a command and returns what to say back. */
    fun run(c: Context, cmd: Cmd): String {
        return when (cmd) {
            is Cmd.SetAlarm -> {
                val a = addOrEnable(c, cmd.hour, cmd.minute, cmd.days, cmd.label)
                val repeat = if (cmd.days.isEmpty()) "" else ", " + daysText(cmd.days).lowercase()
                val whenText = a.nextTrigger()?.let { " — that's " + untilText(it) } ?: ""
                return "Alarm set for ${spokenTime(c, cmd.hour, cmd.minute)}$repeat$whenText."
            }
            is Cmd.SetIn -> {
                val cal = Calendar.getInstance()
                cal.add(Calendar.MINUTE, cmd.minutes)
                val h = cal.get(Calendar.HOUR_OF_DAY)
                val m = cal.get(Calendar.MINUTE)
                addOrEnable(c, h, m, emptySet(), "")
                return "Alarm set for ${spokenTime(c, h, m)}, in ${cmd.minutes} minutes."
            }
            is Cmd.Cancel -> {
                val all = Store.alarms(c)
                val targets = when {
                    cmd.all -> all.filter { it.enabled }
                    cmd.hour != null -> all.filter { it.enabled && it.hour == cmd.hour && it.minute == (cmd.minute ?: 0) }
                        .ifEmpty { all.filter { it.enabled && it.hour % 12 == cmd.hour % 12 && it.minute == (cmd.minute ?: 0) } }
                    else -> listOfNotNull(Scheduler.next(c)?.alarm?.let { n -> all.firstOrNull { it.id == n.id } })
                }
                if (targets.isEmpty()) return "I couldn't find that alarm."
                targets.forEach { it.enabled = false }
                Store.clearSnooze(c)
                Store.saveAlarms(c, all)
                Scheduler.reschedule(c)
                return if (targets.size == 1) "Turned off your ${spokenTime(c, targets[0].hour, targets[0].minute)} alarm."
                else "Turned off ${targets.size} alarms."
            }
            is Cmd.NextAlarm -> {
                val n = Scheduler.next(c) ?: return "You don't have any alarms on."
                return "Your next alarm is at ${fmtMillis(c, n.at)}, ${untilText(n.at)}."
            }
            is Cmd.Sleep -> {
                val type = cmd.type.ifEmpty { Store.getStr(c, "sleepType", "rain") }
                val timer = Store.getInt(c, "sleepTimer", 30)
                SleepPlayer.play(type, Store.getFloat(c, "sleepVol", 0.25f), timer)
                Store.put(c, "sleepType", type)
                val name = SleepPlayer.TYPES.firstOrNull { it.first == type }?.second ?: "Sleep sounds"
                return if (timer > 0) "Playing $name for $timer minutes." else "Playing $name."
            }
            is Cmd.StopSleep -> {
                SleepPlayer.stop()
                return "Stopped."
            }
            is Cmd.Unknown -> return "Sorry, I didn't catch a time. Try “set an alarm for 6 AM”."
        }
    }
}

/**
 * Receives alarm requests from Google Assistant / Gemini and other apps
 * ("Hey Google, set an alarm for 6 AM" when Bedside Clock is the chosen clock app).
 */
class VoiceIntentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { handle(intent) } catch (_: Exception) {}
        finish()
    }

    private fun handle(i: Intent) {
        when (i.action) {
            AlarmClock.ACTION_SET_ALARM -> {
                if (!i.hasExtra(AlarmClock.EXTRA_HOUR)) {
                    startActivity(Intent(this, AlarmsActivity::class.java))
                    return
                }
                val h = i.getIntExtra(AlarmClock.EXTRA_HOUR, 7).coerceIn(0, 23)
                val m = i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59)
                val days = i.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)?.toSet() ?: emptySet()
                val label = i.getStringExtra(AlarmClock.EXTRA_MESSAGE) ?: ""
                val reply = Voice.run(this, Voice.Cmd.SetAlarm(h, m, days, label))
                toast("Bedside Clock: $reply")
            }
            AlarmClock.ACTION_SNOOZE_ALARM -> {
                if (AlarmService.running) AlarmService.send(this, AlarmService.ACTION_SNOOZE)
            }
            AlarmClock.ACTION_DISMISS_ALARM -> {
                if (AlarmService.running) {
                    AlarmService.send(this, AlarmService.ACTION_DISMISS)
                } else {
                    val mode = i.getStringExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE)
                    val cmd = when {
                        mode == AlarmClock.ALARM_SEARCH_MODE_ALL -> Voice.Cmd.Cancel(null, null, true)
                        i.hasExtra(AlarmClock.EXTRA_HOUR) -> Voice.Cmd.Cancel(
                            i.getIntExtra(AlarmClock.EXTRA_HOUR, 0), i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0), false
                        )
                        else -> Voice.Cmd.Cancel(null, null, false)
                    }
                    toast("Bedside Clock: " + Voice.run(this, cmd))
                }
            }
            AlarmClock.ACTION_SHOW_ALARMS -> startActivity(Intent(this, AlarmsActivity::class.java))
        }
    }
}
