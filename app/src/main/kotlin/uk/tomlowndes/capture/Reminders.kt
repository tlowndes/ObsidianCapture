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
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Morning briefing, evening wrap-up, task due alerts, and the Sunday weekly review. */
enum class Slot { MORNING, EVENING, DUE, WEEKLY }

/** Reminders, scheduled with WorkManager (survives reboots, may fire a few minutes late). */
object Reminders {
    const val CHANNEL = "log_reminders"

    private fun name(slot: Slot) = "remind_${slot.name.lowercase()}"

    fun on(prefs: AppPrefs, slot: Slot) = when (slot) {
        Slot.MORNING -> prefs.morningOn
        Slot.EVENING -> prefs.eveningOn
        Slot.DUE -> prefs.dueOn
        Slot.WEEKLY -> prefs.weeklyOn
    }

    private fun minute(prefs: AppPrefs, slot: Slot) = when (slot) {
        Slot.MORNING -> prefs.morningMinute
        Slot.EVENING -> prefs.eveningMinute
        Slot.DUE -> prefs.dueMinute
        Slot.WEEKLY -> prefs.weeklyMinute
    }

    fun scheduleAll(ctx: Context, prefs: AppPrefs) = Slot.values().forEach { schedule(ctx, it, on(prefs, it), minute(prefs, it)) }

    private fun schedule(ctx: Context, slot: Slot, on: Boolean, minuteOfDay: Int) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork(name(slot)); return }
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
        if (!at.isAfter(now.plusMinutes(1))) at = at.plusDays(1)
        if (slot == Slot.WEEKLY) while (at.dayOfWeek != DayOfWeek.SUNDAY) at = at.plusDays(1)
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

    /** [dueToday] is only used for the due alert; it's skipped when nothing is due. */
    internal fun notify(ctx: Context, slot: Slot, dueToday: Int = 0) {
        if (!canNotify(ctx)) return
        if (slot == Slot.DUE && dueToday == 0) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val target = when (slot) {
            Slot.MORNING -> Tab.DECK.name
            Slot.EVENING, Slot.DUE -> Tab.TASKS.name
            Slot.WEEKLY -> "WEEKLY"
        }
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra("tab", target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val snooze = Intent(ctx, SnoozeReceiver::class.java).putExtra("slot", slot.name)
        val (title, text) = when (slot) {
            Slot.MORNING -> "☀ Flight Deck" to "Today's plan is ready."
            Slot.EVENING -> "🌙 Evening wrap-up" to "Tick off or move the rest."
            Slot.DUE -> "Tasks due today" to "$dueToday due"
            Slot.WEEKLY -> "Weekly review" to "Done, carried over, next week."
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(ctx, slot.ordinal, open, flags))
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
        if (Reminders.on(prefs, slot)) {
            val due = if (slot == Slot.DUE) prefs.vault?.let { v ->
                runCatching { Deck.dueCount(VaultWriter(applicationContext, v).listTasks(), LocalDate.now()) }.getOrDefault(0)
            } ?: 0 else 0
            Reminders.notify(applicationContext, slot, due)
        }
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
