package uk.tomlowndes.capture

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Home Did I? (hangar-phase4): asks Home Assistant (through Sensory Dial's connection) about every Did I? check that's
 * linked to the house, logs the ones that came true today as auto stamps, and buzzes once if one is still not done
 * after you've left home. Runs when Did I? opens and every 15 minutes in the background.
 */
internal object HomeHouse {
    private const val WORK = "home_did_i"

    class Live(val states: Map<String, HaState>, val added: List<DidStamp>)

    /** Blocking (network and vault). Null when no check is linked or Home Assistant isn't set up. */
    fun check(ctx: Context, v: Vault, alert: Boolean): Live? {
        val checks = DidI.loadChecks(v)
        if (checks.none { it.link != null }) return null
        val s = SensoryHa.setup(ctx) ?: return null
        val states = SensoryHa.states(s)
        val today = LocalDate.now()
        val added = HomeDidI.autoStamps(checks, states, DidI.loadStamps(v, today), today)
        added.forEach { DidI.stampAt(v, today, it) }
        if (added.isNotEmpty()) { DidWidgets.changed(ctx); DoorInkSend.soon(ctx) }
        if (alert) alert(ctx, checks, states)
        return Live(states, added)
    }

    /** One buzz per check each time you leave with it still not done. */
    private fun alert(ctx: Context, checks: List<DidCheck>, states: Map<String, HaState>) {
        val prefs = AppPrefs(ctx)
        val person = states[prefs.haPerson.ifBlank { return }] ?: return
        if (!HomeDidI.away(person)) return
        val sent = prefs.homeAlerted
        HomeDidI.stillOn(checks, states).forEach { (c, st) ->
            val key = HomeDidI.alertKey(person, c)
            if (key in sent) return@forEach
            val (title, text) = HomeDidI.alertWords(c, st)
            HeadsAlarms.notify(ctx, title, text, 1, HeadsTarget.Kind.HOME)
            prefs.homeAlerted = prefs.homeAlerted + key
        }
    }

    fun keep(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork(WORK); return }
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HomeWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
    }
}

class HomeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val prefs = AppPrefs(app)
        if (Hangar.parse(prefs.hangarTools)?.contains(HangarTool.DID_I) != true) return Result.success()
        val uri = prefs.vault ?: return Result.success()
        runCatching { HomeHouse.check(app, appVault(app, uri), alert = true) }
        return Result.success()
    }
}
