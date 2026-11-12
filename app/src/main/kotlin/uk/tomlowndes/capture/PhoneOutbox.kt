package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * The phone's copy of [Outbox]. Failed saves and failed Jarvis sends are kept here and tried again
 * later, one at a time, without a second copy of the same thing.
 */
object PhoneOutbox {
    data class Report(val done: Int, val waiting: Int)

    private fun store(ctx: Context) = Outbox(File(ctx.filesDir, "outbox.json"))

    fun pendingCount(ctx: Context) = store(ctx).pending().size

    fun keepTask(ctx: Context, text: String, due: String?) {
        store(ctx).enqueue(Outbox.SAVE_TASK, Outbox.taskPayload(text, due))
    }

    fun keepLines(ctx: Context, lines: List<String>) {
        store(ctx).enqueue(Outbox.SAVE_LINES, Outbox.linesPayload(lines))
    }

    fun keepLink(ctx: Context, url: String, tags: List<String>) {
        store(ctx).enqueue(Outbox.SAVE_LINK, Outbox.linkPayload(url, tags))
    }

    fun keepSort(ctx: Context, result: SortResult) {
        store(ctx).enqueue(Outbox.SAVE_SORT, Sorting.toJson(result))
    }

    fun keepJarvis(ctx: Context, worker: String, text: String, chat: String, spoken: Boolean) {
        if (text.isBlank()) return
        store(ctx).enqueue(Outbox.JARVIS, Outbox.jarvisPayload(worker, text, chat, spoken))
    }

    /** Keeps one copy of a recording that could not be sent. [id] is the hash of the bytes. */
    fun keepVoice(ctx: Context, worker: String, chat: String, audio: File) {
        val bytes = runCatching { audio.readBytes() }.getOrNull() ?: return
        val id = Outbox.id(Outbox.JARVIS_VOICE, Outbox.sha1(bytes))
        val dest = File(ctx.filesDir, "outbox-voice/$id.ogg")
        if (!dest.exists()) {
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
        }
        store(ctx).enqueue(Outbox.JARVIS_VOICE, JSONObject().put("worker", worker).put("chat", chat).put("file", dest.absolutePath).toString(), id)
    }

    /** Lets a Retry tap run now. */
    fun nudgeJarvis(ctx: Context, worker: String, text: String, chat: String, spoken: Boolean) {
        store(ctx).nudge(Outbox.id(Outbox.JARVIS, Outbox.jarvisPayload(worker, text, chat, spoken)))
    }

    /** Tries everything that is due. Safe to call when the app opens. Blocking: call off the main thread. */
    fun retry(ctx: Context, prefs: AppPrefs): Report {
        val box = store(ctx)
        var done = 0
        for (job in box.due()) {
            if (!Outbox.begin(job.id)) continue
            val result = try { runCatching { runJob(ctx, prefs, box, job) }.getOrDefault(false) } finally { Outbox.end(job.id) }
            when (result) {
                null -> Unit
                true -> { box.succeed(job.id); done++ }
                false -> box.fail(job.id)
            }
        }
        return Report(done, box.pending().size)
    }

    private fun runJob(ctx: Context, prefs: AppPrefs, box: Outbox, job: Outbox.Job): Boolean? {
        val uri = prefs.vault
        return when (job.kind) {
            Outbox.SAVE_TASK -> uri?.let { Outbox.saveTask(VaultWriter(ctx, it), job.payload); true } ?: false
            Outbox.SAVE_LINES -> uri?.let { Outbox.saveLines(VaultWriter(ctx, it), appVault(ctx, it), job.payload); true } ?: false
            Outbox.SAVE_SORT -> uri?.let { saveSort(ctx, prefs, it, job.payload) } ?: false
            Outbox.SAVE_LINK -> uri?.let { saveLink(ctx, it, job.payload) } ?: false
            Outbox.JARVIS -> deliverText(ctx, prefs, box, job.payload)
            Outbox.JARVIS_VOICE -> deliverVoice(ctx, prefs, job.payload)
            else -> true
        }
    }

    private fun saveSort(ctx: Context, prefs: AppPrefs, uri: Uri, payload: String): Boolean {
        val result = Sorting.fromJson(payload) ?: return true
        Sorting.save(result, VaultWriter(ctx, uri), IdeaStore(ctx, uri, prefs))
        if (prefs.jarvisFound?.let { Sorting.toJson(it) } == payload) prefs.jarvisFound = null
        return true
    }

    private fun saveLink(ctx: Context, uri: Uri, payload: String): Boolean {
        val o = JSONObject(payload)
        val url = o.optString("url").trim()
        if (url.isEmpty() || Outbox.linkSaved(appVault(ctx, uri), url)) return true
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        ReadLater.save(ctx, uri, url, null, tags)
        return true
    }

    private fun deliverText(ctx: Context, prefs: AppPrefs, box: Outbox, payload: String): Boolean? {
        val o = JSONObject(payload)
        val text = o.optString("text").trim()
        val worker = o.optString("worker").ifBlank { "jarvis" }
        if (text.isEmpty()) return true
        val key = PhoneBrain.flightKey(worker, text)
        if (!PhoneBrain.beginFlight(key)) return null
        try {
            val chat = prefs.jarvisChat(worker)
            if (answeredAlready(chat, text)) return true
            val secrets = SecretStore(ctx)
            val claude = secrets.claudeKey.orEmpty()
            val direct = prefs.phoneBrain && claude.isNotBlank() && worker in API_WORKERS && !Jarvis.isDemo(prefs.jarvisAddress)
            val reply = if (direct) directReply(prefs, claude, worker, text) else boxReply(prefs, secrets, worker, text, chat.id)
            reply ?: return false
            remember(prefs, worker, text, reply.first, reply.second)
            reply.third.forEach { h -> box.enqueue(Outbox.JARVIS, Outbox.jarvisPayload(h.worker, h.request, prefs.jarvisChat(h.worker).id, false)) }
            return true
        } finally {
            PhoneBrain.endFlight(key)
        }
    }

    /** Reply text, anything to confirm, and jobs still passed to another worker. Null when it could not be sent. */
    private fun directReply(prefs: AppPrefs, key: String, worker: String, text: String): Triple<String, SortResult?, List<PhoneBrain.Handoff>>? {
        val today = LocalDate.now()
        val direct = when {
            worker == "research" -> PhoneBrain.research(key, text, today)
            worker == "obsidian" || PhoneBrain.worthSorting(text) -> PhoneBrain.sortOrAnswer(key, text, today)
            else -> null
        }
        if (direct != null) {
            prefs.brainSpend = prefs.brainSpend.add(PhoneBrain.costUsd(PhoneBrain.FAST, direct.inTok, direct.outTok))
            return Triple(direct.reply, direct.sorted, emptyList())
        }
        val answer = PhoneBrain.chat(key, PhoneBrain.model(prefs.brainClever), listOf(PhoneBrain.Turn(true, text)), today, listOf("#work", "#personal")) {}
        prefs.brainSpend = prefs.brainSpend.add(PhoneBrain.costUsd(answer.model, answer.inputTokens, answer.outputTokens))
        val sorted = answer.tasks.takeIf { it.isNotEmpty() }?.let { SortResult(it, emptyList(), null) }
        return Triple(answer.text.ifBlank { "Done." }, sorted, answer.handoffs)
    }

    private fun boxReply(prefs: AppPrefs, secrets: SecretStore, worker: String, text: String, chat: String): Triple<String, SortResult?, List<PhoneBrain.Handoff>>? {
        val address = prefs.jarvisAddress
        val token = secrets.jarvisToken.orEmpty()
        if (address.isBlank() || token.isBlank() || Jarvis.isDemo(address)) return null
        val reply = Jarvis.sendText(address, token, text, worker, prefs.jarvisVoice, chat = chat, speak = !prefs.phoneVoice)
        return Triple(reply.reply, reply.sorted, emptyList())
    }

    private fun deliverVoice(ctx: Context, prefs: AppPrefs, payload: String): Boolean? {
        val o = JSONObject(payload)
        val file = File(o.optString("file"))
        if (!file.isFile) return true
        val worker = o.optString("worker").ifBlank { "jarvis" }
        val address = prefs.jarvisAddress
        val token = SecretStore(ctx).jarvisToken.orEmpty()
        if (address.isBlank() || token.isBlank() || Jarvis.isDemo(address)) return false
        val reply = Jarvis.send(address, token, file, worker, prefs.jarvisVoice, chat = o.optString("chat"))
        remember(prefs, worker, reply.transcript.ifBlank { "(voice message)" }, reply.reply, reply.sorted)
        file.delete()
        return true
    }

    private fun remember(prefs: AppPrefs, worker: String, said: String, reply: String, sorted: SortResult?) {
        val now = System.currentTimeMillis()
        val chat = prefs.jarvisChat(worker)
        val add = listOfNotNull(
            Jarvis.ChatMsg(true, said, now).takeIf { said.isNotBlank() },
            Jarvis.ChatMsg(false, reply, now + 1).takeIf { reply.isNotBlank() },
        )
        prefs.setJarvisChat(worker, chat.copy(messages = (chat.messages + add).takeLast(Jarvis.CHAT_KEEP)))
        sorted?.let { prefs.jarvisFound = merge(prefs.jarvisFound, it) }
    }

    private fun answeredAlready(chat: Jarvis.Chat, text: String): Boolean {
        val mine = chat.messages.indexOfLast { it.mine && it.text.trim() == text.trim() }
        return mine >= 0 && chat.messages.drop(mine + 1).any { !it.mine && it.text.isNotBlank() }
    }

    private fun merge(old: SortResult?, next: SortResult): SortResult =
        if (old != null && old.note == null && next.note == null) old.copy(tasks = old.tasks + next.tasks, ideas = old.ideas + next.ideas) else old ?: next

    private val API_WORKERS = setOf("jarvis", "obsidian", "research")
}
