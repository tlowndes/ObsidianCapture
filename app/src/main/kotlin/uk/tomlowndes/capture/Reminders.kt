package uk.tomlowndes.capture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

enum class Slot { MORNING, EVENING }

/** Morning and evening reminders, scheduled with WorkManager (survives reboots, may fire a few minutes late). */
object Reminders {
    const val CHANNEL = "log_reminders"

    private fun name(slot: Slot) = "remind_${slot.name.lowercase()}"

    fun scheduleAll(ctx: Context, prefs: AppPrefs) {
        schedule(ctx, Slot.MORNING, prefs.morningOn, prefs.morningMinute)
        schedule(ctx, Slot.EVENING, prefs.eveningOn, prefs.eveningMinute)
    }

    private fun schedule(ctx: Context, slot: Slot, on: Boolean, minuteOfDay: Int) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork(name(slot)); return }
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
        if (!at.isAfter(now.plusMinutes(1))) at = at.plusDays(1)
        enqueue(ctx, slot, Duration.between(now, at), snooze = false)
    }

    internal fun enqueue(ctx: Context, slot: Slot, delay: Duration, snooze: Boolean) {
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay)
            .setInputData(Data.Builder().putString("slot", slot.name).putBoolean("snooze", snooze).build())
            .build()
        val unique = if (snooze) name(slot) + "_snooze" else name(slot)
        WorkManager.getInstance(ctx).enqueueUniqueWork(unique, ExistingWorkPolicy.REPLACE, req)
    }

    fun canNotify(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun notify(ctx: Context, slot: Slot) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val morning = slot == Slot.MORNING
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra("tab", Tab.TASKS.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val snooze = Intent(ctx, SnoozeReceiver::class.java).putExtra("slot", slot.name)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (morning) "☀ Morning reminder" else "🌙 Evening reminder")
            .setContentText(if (morning) "Have a look at today's tasks." else "Anything to tick off or move to tomorrow?")
            .setContentIntent(PendingIntent.getActivity(ctx, slot.ordinal, open, flags))
            .addAction(0, "Open tasks", PendingIntent.getActivity(ctx, 10 + slot.ordinal, open, flags))
            .addAction(0, "Snooze 1h", PendingIntent.getBroadcast(ctx, 20 + slot.ordinal, snooze, flags))
            .setAutoCancel(true)
            .build()
        nm.notify(100 + slot.ordinal, n)
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val slot = runCatching { Slot.valueOf(inputData.getString("slot") ?: "") }.getOrNull() ?: return Result.success()
        val snooze = inputData.getBoolean("snooze", false)
        val prefs = AppPrefs(applicationContext)
        val enabled = if (slot == Slot.MORNING) prefs.morningOn else prefs.eveningOn
        if (enabled) Reminders.notify(applicationContext, slot)
        if (!snooze) Reminders.scheduleAll(applicationContext, prefs)
        return Result.success()
    }
}

class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val slot = runCatching { Slot.valueOf(intent.getStringExtra("slot") ?: "") }.getOrNull() ?: return
        ctx.getSystemService(NotificationManager::class.java).cancel(100 + slot.ordinal)
        Reminders.enqueue(ctx, slot, Duration.ofHours(1), snooze = true)
    }
}
