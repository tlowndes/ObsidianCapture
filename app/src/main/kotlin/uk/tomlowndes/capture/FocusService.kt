package uk.tomlowndes.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * A running or paused Focus session, kept in preferences so the screen and the notification agree after the app
 * is closed. [endAt] is set while running; [remainingMs] while paused. [raw]/[path] point at the task, if any.
 */
data class FocusState(
    val task: String, val raw: String?, val path: String?, val totalMs: Long, val startedAt: Long,
    val endAt: Long?, val remainingMs: Long
) {
    fun remaining(now: Long = System.currentTimeMillis()) = if (endAt != null) (endAt - now).coerceAtLeast(0) else remainingMs
    val running get() = endAt != null
    fun elapsedMinutes(now: Long = System.currentTimeMillis()) = (((totalMs - remaining(now)) + 30_000) / 60_000).toInt().coerceAtLeast(1)
}

object Focus {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("focus", Context.MODE_PRIVATE)

    fun state(ctx: Context): FocusState? = p(ctx).run {
        val task = getString("task", null) ?: return null
        FocusState(
            task, getString("raw", null), getString("path", null), getLong("total", 0), getLong("started", 0),
            getLong("end", 0).takeIf { it > 0 }, getLong("remaining", 0)
        )
    }

    private fun save(ctx: Context, s: FocusState?) = p(ctx).edit().apply {
        if (s == null) clear() else {
            putString("task", s.task).putString("raw", s.raw).putString("path", s.path)
            putLong("total", s.totalMs).putLong("started", s.startedAt)
            putLong("end", s.endAt ?: 0).putLong("remaining", s.remainingMs)
        }
    }.apply()

    fun start(ctx: Context, task: String, raw: String?, path: String?, minutes: Int) {
        val now = System.currentTimeMillis()
        val total = minutes * 60_000L
        save(ctx, FocusState(task, raw, path, total, now, now + total, total))
        kick(ctx)
    }

    fun pause(ctx: Context) {
        val s = state(ctx) ?: return
        if (!s.running) return
        save(ctx, s.copy(endAt = null, remainingMs = s.remaining()))
        kick(ctx)
    }

    fun resume(ctx: Context) {
        val s = state(ctx) ?: return
        if (s.running) return
        save(ctx, s.copy(endAt = System.currentTimeMillis() + s.remainingMs))
        kick(ctx)
    }

    /** Ends the session and returns it, for logging. */
    fun stop(ctx: Context): FocusState? {
        val s = state(ctx)
        save(ctx, null)
        ctx.stopService(Intent(ctx, FocusService::class.java))
        return s
    }

    private fun kick(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, FocusService::class.java))
}

/** Keeps the Focus countdown in a notification and says when time is up. */
class FocusService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val done = Runnable { timeUp() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = Focus.state(this) ?: run { stopSelf(); return START_NOT_STICKY }
        channels()
        ServiceCompat.startForeground(this, ONGOING_ID, ongoing(s), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        handler.removeCallbacks(done)
        if (s.running) handler.postDelayed(done, s.remaining())
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(done)
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 30, Intent(this, MainActivity::class.java).putExtra("tab", "FOCUS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun ongoing(s: FocusState): Notification = NotificationCompat.Builder(this, ONGOING)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle(s.task)
        .setContentText(if (s.running) "Focus" else "Paused · ${s.remaining() / 60_000} min left")
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .setContentIntent(openApp())
        .apply {
            if (s.running) setUsesChronometer(true).setChronometerCountDown(true).setWhen(s.endAt!!).setShowWhen(true)
        }
        .build()

    private fun timeUp() {
        val s = Focus.state(this) ?: return
        val nm = getSystemService(NotificationManager::class.java)
        if (Reminders.canNotify(this)) nm.notify(
            DONE_ID, NotificationCompat.Builder(this, DONE)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Time's up")
                .setContentText(s.task)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ONGOING, "Focus timer", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(DONE, "Focus finished", NotificationManager.IMPORTANCE_HIGH))
    }

    private companion object {
        const val ONGOING = "focus"
        const val DONE = "focus_done"
        const val ONGOING_ID = 41
        const val DONE_ID = 42
    }
}
