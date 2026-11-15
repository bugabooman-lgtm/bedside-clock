package com.bedside.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object Scheduler {
    const val ACTION_RING = "com.bedside.clock.RING"

    class Next(val alarm: Alarm, val at: Long, val snoozed: Boolean)

    /** The soonest thing that will ring: an enabled alarm or a pending snooze. */
    fun next(c: Context): Next? {
        val alarms = Store.alarms(c)
        var best: Next? = null
        for (a in alarms) {
            if (!a.enabled) continue
            val t = a.nextTrigger() ?: continue
            val b = best
            if (b == null || t < b.at) best = Next(a, t, false)
        }
        val sid = Store.snoozeId(c)
        val sat = Store.snoozeAt(c)
        if (sid != 0L && sat > System.currentTimeMillis() - 60_000L) {
            val a = alarms.firstOrNull { it.id == sid }
            val b = best
            if (a != null && (b == null || sat < b.at)) best = Next(a, sat, true)
        }
        return best
    }

    /** Arms Android's alarm clock for whatever rings next. Call after any change. */
    fun reschedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val ring = PendingIntent.getBroadcast(
            c, 0,
            Intent(c, AlarmReceiver::class.java).setAction(ACTION_RING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.cancel(ring)
        val n = next(c)
        if (n == null) {
            Store.put(c, "pendingId", 0L)
            return
        }
        Store.put(c, "pendingId", n.alarm.id)
        Store.put(c, "pendingSnooze", n.snoozed)
        val show = PendingIntent.getActivity(
            c, 1, Intent(c, ClockActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val at = maxOf(n.at, System.currentTimeMillis() + 1000L)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), ring)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = Store.getLong(c, "pendingId", 0L)
        val snoozed = Store.getBool(c, "pendingSnooze", false)
        if (snoozed) Store.clearSnooze(c)
        AlarmService.ringNow(c, id, snoozed)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Scheduler.reschedule(c)
    }
}
