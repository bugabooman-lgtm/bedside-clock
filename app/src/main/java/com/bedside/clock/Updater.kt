package com.bedside.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Checks GitHub for a newer build and installs it.
 * Automatic installs only happen in the daytime, never during night hours,
 * never while an alarm or sleep sound is playing, and never within 90 minutes of an alarm.
 */
object Updater {
    private const val API = "https://api.github.com/repos/bugabooman-lgtm/bedside-clock/releases/latest"
    private const val CHECK_EVERY_MS = 6 * 3600_000L

    class Info(val build: Long, val version: String, val url: String)

    @Volatile var available: Info? = null
    @Volatile var busy = false
    @Volatile var status = ""
    /** Android asked the person to confirm; stop auto-installing until they tap Install themselves. */
    @Volatile var needsConfirm = false

    fun currentBuild(c: Context): Long = try {
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
    } catch (_: Exception) { 0L }

    fun currentVersion(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    fun autoOn(c: Context) = Store.getBool(c, "autoUpdate", true)
    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    /** Asks GitHub for the newest release. Network call: run off the main thread. */
    fun fetchLatest(): Info? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "BedsideClock")
        try {
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name", "")                 // e.g. v1.3.1-build6
            val build = Regex("build(\\d+)").find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val version = tag.removePrefix("v").substringBefore("-build")
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) return Info(build, version, a.getString("browser_download_url"))
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Checks now; calls [done] on the main thread with the newer release, or null if up to date / offline. */
    fun check(c: Context, done: (Info?, Boolean) -> Unit) {
        val app = c.applicationContext
        Thread {
            var ok = true
            val info = try { fetchLatest() } catch (_: Exception) { ok = false; null }
            Store.put(app, "updLastCheck", System.currentTimeMillis())
            val newer = if (info != null && info.build > currentBuild(app)) info else null
            available = newer
            Handler(Looper.getMainLooper()).post { done(newer, ok) }
        }.start()
    }

    /** True when installing now can't get in the way of sleep or an alarm. */
    fun safeTime(c: Context): Boolean {
        if (AlarmService.running || SleepPlayer.isPlaying) return false
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (inRange(nowMin, Store.nightFrom(c), Store.nightTo(c))) return false
        if (nowMin < 9 * 60 || nowMin >= 21 * 60) return false
        val next = Scheduler.next(c)
        if (next != null && next.at - System.currentTimeMillis() < 90 * 60_000L) return false
        return true
    }

    /** Called every minute by the clock screen: checks now and then, installs when it's a good time. */
    fun tick(c: Context) {
        if (busy) return
        val last = Store.getLong(c, "updLastCheck", 0L)
        if (System.currentTimeMillis() - last > CHECK_EVERY_MS) {
            busy = true
            check(c) { _, _ -> busy = false }
            return
        }
        val info = available ?: return
        if (autoOn(c) && canInstall(c) && safeTime(c) && !needsConfirm) install(c, info)
    }

    /** Downloads the APK and hands it to Android's installer. */
    fun install(c: Context, info: Info) {
        if (busy) return
        busy = true
        status = "Downloading version ${info.version}…"
        val app = c.applicationContext
        Thread {
            try {
                val file = File(app.cacheDir, "update.apk")
                download(info.url, file)
                status = "Installing version ${info.version}…"
                Store.put(app, "updPendingVersion", info.version)
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("BedsideClock.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val result = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flags)
                    session.commit(result.intentSender)
                }
            } catch (e: Exception) {
                status = "Update failed: ${e.message ?: "download error"}. Will try again later."
                busy = false
            }
        }.start()
    }

    private fun download(url: String, dest: File) {
        var link = url
        repeat(5) {
            val conn = URL(link).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "BedsideClock")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    link = conn.getHeaderField("Location") ?: throw IllegalStateException("bad redirect")
                    return@repeat
                }
                if (code != 200) throw IllegalStateException("HTTP $code")
                conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("too many redirects")
    }

    fun notifyUpdated(c: Context, version: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("updates") == null) {
            nm.createNotificationChannel(NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            c, 3, Intent(c, ClockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, "updates")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Bedside Clock updated to $version")
            .setContentText("Tap to open the clock")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(7, n) } catch (_: Exception) {}
    }
}

/** Hears back from Android's installer. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants the person to confirm (usually only the first time)
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { c.startActivity(confirm) } catch (_: Exception) {}
                }
                Updater.status = "Tap “Update” on the screen Android showed to finish."
                Updater.needsConfirm = true
                Updater.busy = false
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Updater.status = "Updated."
                Updater.needsConfirm = false
                Updater.busy = false
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Updater.status = "Update didn't install ($msg). Will try again later."
                Updater.busy = false
                // don't retry the same build automatically until the next check
                Updater.available = null
            }
        }
    }
}
