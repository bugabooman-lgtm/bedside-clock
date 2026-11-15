package com.bedside.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlin.math.roundToInt

/** Plays the alarm. Runs in the foreground so Android won't kill it while ringing. */
class AlarmService : Service() {

    companion object {
        const val ACTION_SNOOZE = "com.bedside.clock.SNOOZE"
        const val ACTION_DISMISS = "com.bedside.clock.DISMISS"
        private const val EXTRA_ID = "id"
        private const val EXTRA_SNOOZED = "snoozed"
        private const val CHANNEL = "alarm_ringing"
        private const val NOTIF_ID = 42

        @Volatile var running = false
        @Volatile var currentLabel = "Alarm"
        @Volatile var currentId = 0L

        fun ringNow(c: Context, id: Long, snoozed: Boolean) {
            val i = Intent(c, AlarmService::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_SNOOZED, snoozed)
            c.startForegroundService(i)
        }

        fun send(c: Context, action: String) {
            try {
                c.startService(Intent(c, AlarmService::class.java).setAction(action))
            } catch (_: Exception) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedVolume = -1
    private var rampStart = 0L
    private var rampMs = 0L

    private val autoSnooze = Runnable { snooze() }

    private val ramp = object : Runnable {
        override fun run() {
            val f = ((System.currentTimeMillis() - rampStart).toFloat() / rampMs).coerceIn(0f, 1f)
            val v = 0.08f + 0.92f * f
            track?.setVolume(v)
            player?.setVolume(v, v)
            if (f < 1f && running) handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SNOOZE -> { snooze(); return START_NOT_STICKY }
            ACTION_DISMISS -> { dismiss(); return START_NOT_STICKY }
        }
        val id = intent?.getLongExtra(EXTRA_ID, 0L) ?: 0L
        val snoozed = intent?.getBooleanExtra(EXTRA_SNOOZED, false) ?: false

        val alarms = Store.alarms(this)
        val found = alarms.firstOrNull { it.id == id }
        val alarm = found ?: Alarm.newDefault().apply { label = "Test alarm"; rampMin = 0 }
        if (found != null && found.days.isEmpty() && !snoozed) {
            found.enabled = false          // one-time alarm: switch it off once it rings
            Store.saveAlarms(this, alarms)
        }
        currentId = alarm.id
        currentLabel = alarm.label.ifBlank { "Alarm" }

        startInForeground()
        running = true
        SleepPlayer.stop()
        Scheduler.reschedule(this)

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bedside:ring").apply { acquire(15 * 60_000L) }
        }
        stopSound()
        startSound(alarm, if (snoozed) 0 else alarm.rampMin)
        handler.removeCallbacks(autoSnooze)
        handler.postDelayed(autoSnooze, 10 * 60_000L)   // nobody answered: snooze automatically

        try {
            startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Ringing alarm", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val full = PendingIntent.getActivity(
            this, 10,
            Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags
        )
        val snoozePi = PendingIntent.getService(this, 11, Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE), flags)
        val dismissPi = PendingIntent.getService(this, 12, Intent(this, AlarmService::class.java).setAction(ACTION_DISMISS), flags)
        val noIcon: Icon? = null
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(currentLabel)
            .setContentText("Alarm ringing — tap to open")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(noIcon, "Snooze", snoozePi).build())
            .addAction(Notification.Action.Builder(noIcon, "Turn off", dismissPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun startSound(alarm: Alarm, rampMin: Int) {
        val tone = alarm.tone
        val am = getSystemService(AudioManager::class.java)
        try {
            if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * Store.volume(this)).roundToInt().coerceIn(1, max), 0)
        } catch (_: Exception) {}

        rampMs = rampMin * 60_000L
        rampStart = System.currentTimeMillis()
        val startVol = if (rampMs > 0) 0.08f else 1f
        var ok = false
        if ((tone == "device" || tone == "file") && alarm.toneUri.isNotEmpty()) {
            ok = playUri(Uri.parse(alarm.toneUri), startVol)
        } else if (tone != "system") {
            try {
                val t = Synth.loopingTrack(tone, AudioAttributes.USAGE_ALARM)
                t.setVolume(startVol)
                t.play()
                track = t
                ok = true
            } catch (_: Exception) {}
        }
        if (!ok) playUri(null, startVol)
        if (rampMs > 0) handler.post(ramp)
    }

    /** Plays a sound file from the tablet; null = the tablet's default alarm. */
    private fun playUri(chosen: Uri?, vol: Float): Boolean {
        try {
            val uri = chosen ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.setVolume(vol, vol)
            mp.prepare()
            mp.start()
            player = mp
            return true
        } catch (_: Exception) {
            player?.release()
            player = null
            return false
        }
    }

    private fun stopSound() {
        handler.removeCallbacks(ramp)
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    private fun restoreVolume() {
        if (savedVolume >= 0) {
            try {
                getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: Exception) {}
            savedVolume = -1
        }
    }

    private fun snooze() {
        if (running) Store.setSnooze(this, currentId, System.currentTimeMillis() + Store.snoozeMin(this) * 60_000L)
        finishRinging()
    }

    private fun dismiss() {
        Store.clearSnooze(this)
        finishRinging()
    }

    private fun finishRinging() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopSound()
        restoreVolume()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Scheduler.reschedule(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        stopSound()
        restoreVolume()
        super.onDestroy()
    }
}
