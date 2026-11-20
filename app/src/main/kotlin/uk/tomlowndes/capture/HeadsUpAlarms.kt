package uk.tomlowndes.capture

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Heads Up's buzzes (hangar-phase1), set as alarms so the 1-minute warning lands on time. Planned again when the
 * app opens, when Focus starts or changes, after a buzz, and every 30 minutes in the background.
 */
object HeadsAlarms {
    private const val MAX = 24
    private const val WORK = "heads_up_plan"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Each buzz strength gets its own channel, because Android sets vibration per channel. */
    private val CHANNELS = listOf(
        Triple("heads_soft", "Heads Up: 10 minutes", longArrayOf(0, 120)),
        Triple("heads_double", "Heads Up: 5 minutes", longArrayOf(0, 120, 120, 120)),
        Triple("heads_strong", "Heads Up: 1 minute", longArrayOf(0, 300, 150, 300, 150, 300)),
    )

    private fun channelFor(minutes: Int) = when { minutes >= 10 -> CHANNELS[0]; minutes > 1 -> CHANNELS[1]; else -> CHANNELS[2] }

    fun targets(ctx: Context, prefs: AppPrefs, now: LocalDateTime = LocalDateTime.now()): List<HeadsTarget> {
        val out = mutableListOf<HeadsTarget>()
        val uri = prefs.vault
        if (uri != null && (prefs.headsLeave || prefs.headsEvents)) runCatching {
            val v = appVault(ctx, uri)
            val events = TripLoader.events(ctx, v).filter { !it.allDay && it.start.toLocalDate() == now.toLocalDate() && it.start.isAfter(now) }
            if (prefs.headsEvents) events.forEach { out += HeadsTarget(HeadsTarget.Kind.EVENT, it.title, it.start) }
            if (prefs.headsLeave) TravelFetch.line(ctx, events, now)?.let { out += HeadsTarget(HeadsTarget.Kind.LEAVE, it.event.title, it.leaveBy) }
        }
        if (prefs.headsFocus) Focus.state(ctx)?.takeIf { it.running }?.endAt?.let { end ->
            val at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(end), ZoneId.systemDefault())
            out += HeadsTarget(HeadsTarget.Kind.FOCUS, Focus.state(ctx)?.task ?: "Focus", at)
        }
        return out
    }

    /** Replaces every Heads Up alarm with a fresh plan. Call off the main thread. */
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = AppPrefs(app)
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        repeat(MAX) { i -> pending(app, i, null)?.let { am.cancel(it) } }
        if (!prefs.headsOn) { prefs.headsNext = 0; return }
        val plan = HeadsUp.plan(targets(app, prefs), LocalDateTime.now(), HeadsUp.parseStages(prefs.headsStages),
            quietFrom = if (prefs.headsQuiet) java.time.LocalTime.of(21, 0) else java.time.LocalTime.MIDNIGHT,
            quietTo = if (prefs.headsQuiet) java.time.LocalTime.of(7, 0) else java.time.LocalTime.MIDNIGHT)
            .let { p -> (p + listOfNotNull(nightNudge(app, prefs))).sortedBy { it.at } }.take(MAX)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        plan.forEachIndexed { i, a ->
            val at = a.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = pending(app, i, a) ?: return@forEachIndexed
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        prefs.headsNext = plan.firstOrNull()?.at?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0
    }

    /** The one 21:00 Night Pad buzz, when it's switched on and Night Pad is in Hangar. Soft, so it uses the 10-minute channel. */
    private fun nightNudge(ctx: Context, prefs: AppPrefs, now: LocalDateTime = LocalDateTime.now()): HeadsAlert? {
        if (!prefs.headsNight || Hangar.parse(prefs.hangarTools)?.contains(HangarTool.NIGHT_PAD) != true) return null
        val today = now.toLocalDate().atTime(21, 0)
        val at = if (now.isBefore(today)) today else today.plusDays(1)
        val uri = prefs.vault
        val label = uri?.let { u -> runCatching { NightPad.firstTomorrow(TripLoader.events(ctx, appVault(ctx, u)), at)?.title }.getOrNull() } ?: "Charge up"
        return HeadsAlert(at, 10, HeadsTarget(HeadsTarget.Kind.NIGHT, label, at))
    }

    fun changed(ctx: Context) { val app = ctx.applicationContext; scope.launch { runCatching { reschedule(app) } } }

    fun keepPlanning(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork(WORK); return }
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<HeadsWorker>(30, TimeUnit.MINUTES).build())
    }

    private fun pending(ctx: Context, i: Int, a: HeadsAlert?): PendingIntent? {
        val intent = Intent(ctx, HeadsUpReceiver::class.java).setAction("heads_$i")
        if (a != null) {
            val (title, text) = HeadsUp.words(a)
            intent.putExtra("title", title).putExtra("text", text).putExtra("minutes", a.minutes).putExtra("kind", a.target.kind.name)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (a == null) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT)
        return PendingIntent.getBroadcast(ctx, 300 + i, intent, flags)
    }

    internal fun notify(ctx: Context, title: String, text: String, minutes: Int, kind: HeadsTarget.Kind) {
        if (!Reminders.canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        CHANNELS.forEach { (id, name, pattern) ->
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); vibrationPattern = pattern; setSound(null, null)
            })
        }
        val tab = when (kind) { HeadsTarget.Kind.LEAVE -> "LAUNCH_PAD"; HeadsTarget.Kind.FOCUS -> "HEADS_SWITCH"; HeadsTarget.Kind.EVENT -> "DECK"; HeadsTarget.Kind.NIGHT -> "NIGHT_PAD" }
        val open = Intent(ctx, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val icon = when { minutes >= 10 -> "◔"; minutes > 1 -> "◑"; else -> "●" }
        val b = NotificationCompat.Builder(ctx, channelFor(minutes).first)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$icon $title").setContentText(text)
            .setColor(if (minutes > 1) 0xFF7AA2FF.toInt() else 0xFFF5C06A.toInt())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(15 * 60_000L)
            .setContentIntent(PendingIntent.getActivity(ctx, 400 + kind.ordinal, open, flags))
            .setAutoCancel(true)
        // One "5 more min" per Focus session, so it can't turn into a snooze loop.
        val state = Focus.state(ctx)
        if (kind == HeadsTarget.Kind.FOCUS && state != null && AppPrefs(ctx).headsExtendedFor != state.startedAt) {
            val more = Intent(ctx, HeadsUpReceiver::class.java).setAction("heads_more")
            b.addAction(0, "5 more min", PendingIntent.getBroadcast(ctx, 499, more, flags))
        }
        nm.notify(500 + kind.ordinal, b.build())
    }

    /** The one "5 more min" for this Focus session. Returns false if it's been used. */
    fun moreTime(ctx: Context): Boolean {
        val prefs = AppPrefs(ctx)
        val s = Focus.state(ctx) ?: return false
        if (prefs.headsExtendedFor == s.startedAt) return false
        prefs.headsExtendedFor = s.startedAt
        Focus.extend(ctx, 5)
        changed(ctx)
        return true
    }
}

class HeadsUpReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == "heads_more") {
            HeadsAlarms.moreTime(ctx)
            ctx.getSystemService(NotificationManager::class.java).cancel(500 + HeadsTarget.Kind.FOCUS.ordinal)
            return
        }
        val kind = runCatching { HeadsTarget.Kind.valueOf(intent.getStringExtra("kind").orEmpty()) }.getOrNull() ?: return
        HeadsAlarms.notify(ctx, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("text").orEmpty(), intent.getIntExtra("minutes", 10), kind)
        HeadsAlarms.changed(ctx)
    }
}

class HeadsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { runCatching { HeadsAlarms.reschedule(applicationContext) }; return Result.success() }
}
