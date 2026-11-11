package uk.tomlowndes.capture

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel

/**
 * Gemini Nano through ML Kit's Prompt API (AICore). Free, offline, small: [NanoRoute] decides what it answers.
 * One client for the app; [ensure] fetches the model the first time if the phone hasn't already.
 */
object PhoneNano {
    enum class State { CHECKING, READY, GETTING_READY, UNAVAILABLE }

    @Volatile var state = State.CHECKING; private set
    private var model: GenerativeModel? = null

    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    /** Works out whether Nano can answer now, starting the download when it's missing. Safe to call often. */
    suspend fun ensure(): State {
        if (state == State.READY) return state
        state = runCatching {
            when (client().checkStatus()) {
                FeatureStatus.AVAILABLE -> { runCatching { client().warmup() }; State.READY }
                FeatureStatus.DOWNLOADING -> State.GETTING_READY
                FeatureStatus.DOWNLOADABLE -> {
                    var done = false
                    client().download().collect { s -> if (s is DownloadStatus.DownloadCompleted) done = true }
                    if (done) State.READY else State.GETTING_READY
                }
                else -> State.UNAVAILABLE
            }
        }.getOrElse { State.UNAVAILABLE }
        return state
    }

    /** Streams Nano's answer to [prompt]; [onText] gets the whole answer so far each time. */
    suspend fun ask(prompt: String, onText: (String) -> Unit): String {
        val out = StringBuilder()
        client().generateContentStream(prompt).collect { chunk ->
            chunk.candidates.firstOrNull()?.text?.let { out.append(it); onText(out.toString()) }
        }
        return out.toString()
    }

    fun label(s: State) = when (s) {
        State.READY -> "ready on this phone"
        State.GETTING_READY -> "getting ready…"
        State.CHECKING -> "checking…"
        State.UNAVAILABLE -> "not available on this phone"
    }
}

/** What Nano is shown with each question: today's and tomorrow's calendar, tasks and weather, and matching notes. */
internal object NanoContext {
    fun gather(ctx: android.content.Context, vault: android.net.Uri?, question: String): Pair<String, String> {
        val today = java.time.LocalDate.now()
        val tasks = vault?.let { runCatching { VaultWriter(ctx, it).listTasks() }.getOrNull() }.orEmpty()
        val on = vault?.let { runCatching { CalendarConfig.load(appVault(ctx, it)) }.getOrNull() }?.filter { it.enabled }?.map { it.id }?.toSet()
        val events = runCatching { CalendarSync.cached(ctx).filter { on == null || it.id in on }.flatMap { it.events } }.getOrDefault(emptyList())
        val weather = runCatching { WeatherFetch.today(ctx) }.getOrNull()
        val day = NanoRoute.context(today, events, tasks, weather)
        val notes = vault?.let { v ->
            runCatching { NanoRoute.vaultContext(NoteIndex.build(appVault(ctx, v), java.io.File(ctx.cacheDir, "vault-index.json")), question) }.getOrNull()
        }.orEmpty()
        return day to notes
    }
}
