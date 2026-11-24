package uk.tomlowndes.capture

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Door Ink (hangar-phase4): pushes the door screen to the Pi by the front door over home Wi-Fi. Sent when a Launch
 * Pad item is ticked or a Did I? check is stamped, and every 15 minutes on Wi-Fi so the leave view turns up on its
 * own. Nothing is sent when nothing changed, so the e-ink only redraws when it has to. Silent when the Pi isn't there.
 */
internal object DoorInkSend {
    private const val WORK = "door_ink"

    /** Blocking (vault and network). Returns the Pi's status code, or null when off or nothing changed. */
    fun send(ctx: Context, force: Boolean = false): Int? {
        val prefs = AppPrefs(ctx)
        val address = prefs.doorInkAddress.ifBlank { return null }
        val uri = prefs.vault ?: return null
        val v = appVault(ctx, uri)
        val now = LocalDateTime.now()
        val trip: Trip? = runCatching { TripLoader.load(ctx, v, now) }.getOrNull()
        // Every kit due today (kits-backpack-v1), with the kits tapped on or off on Launch Pad.
        val kits: List<Kit>? = runCatching { Kits.loadAll(v) }.getOrNull()
        val items = if (kits != null && trip != null)
            Kits.packs(Kits.active(kits, trip, prefs.launchKitsOn, prefs.launchKitsOff), trip, prefs.launchKitsOn).flatMap { it.items } else emptyList()
        val did = runCatching { DidI.today(v) }.getOrDefault(emptyList())
        val weather = runCatching { WeatherFetch.today(ctx) }.getOrNull()
        val body = DoorInk.body(trip, items, prefs.launchTicks + prefs.packedLastNight, did, weather, now)
        if (!force && !DoorInk.changed(body, prefs.doorInkLast)) return null
        val code = Piholegotchi.post(address, DoorInk.PATH, DoorInk.wrap(body, now))
        if (code in 200..299) prefs.doorInkLast = body
        return code
    }

    /** Sends in the background, after a tick or a stamp. */
    fun soon(ctx: Context) {
        val app = ctx.applicationContext
        if (AppPrefs(app).doorInkAddress.isBlank()) return
        thread(name = "door-ink") { runCatching { send(app) } }
    }

    /** For Settings: "Connected" or why not. Call off the main thread. */
    fun test(ctx: Context): String = runCatching {
        val code = send(ctx, force = true) ?: return "Add the address first."
        if (code in 200..299) "Connected" else "The Pi answered $code. Update Door Ink on the Pi."
    }.getOrElse { "Can't reach it. Use the IP address shown on the door screen, on home Wi-Fi." }

    fun keep(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork(WORK); return }
        val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DoorWorker>(15, TimeUnit.MINUTES).setConstraints(wifi).build())
    }
}

class DoorWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { DoorInkSend.send(applicationContext) }
        return Result.success()
    }
}
