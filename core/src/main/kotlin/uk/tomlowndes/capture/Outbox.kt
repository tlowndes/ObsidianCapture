package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/**
 * A small queue of saves and Jarvis sends that failed. It lives in a file on the phone, outside the vault.
 * The same payload is kept once. A later retry does the work again only when it is due, and a save that
 * already landed is not written a second time.
 */
class Outbox(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    data class Job(val id: String, val kind: String, val payload: String, val attempts: Int, val notBefore: Long, val auto: Boolean)

    @Synchronized
    fun pending(): List<Job> = read()

    /** Jobs an automatic retry may run now. */
    @Synchronized
    fun due(at: Long = now()): List<Job> = read().filter { it.auto && it.attempts < MAX_ATTEMPTS && it.notBefore <= at }

    /** Keeps one job per [id]. A second enqueue of the same id leaves the existing job as it is. */
    @Synchronized
    fun enqueue(kind: String, payload: String, id: String = id(kind, payload)): Job {
        val jobs = read().toMutableList()
        jobs.firstOrNull { it.id == id }?.let { return it }
        val job = Job(id, kind, payload, 0, now(), true)
        jobs += job
        write(jobs)
        return job
    }

    @Synchronized
    fun succeed(id: String) { write(read().filter { it.id != id }) }

    /** Puts the job off for a while. After [MAX_ATTEMPTS] it is dropped, so a broken job does not sit there forever. */
    @Synchronized
    fun fail(id: String, at: Long = now()) {
        write(read().mapNotNull { job ->
            if (job.id != id) job
            else {
                val n = job.attempts + 1
                if (n >= MAX_ATTEMPTS) null else job.copy(attempts = n, notBefore = at + backoffMs(n), auto = true)
            }
        })
    }

    /** A tap on Retry may run the job now, even if an automatic retry is still waiting. */
    @Synchronized
    fun nudge(id: String) {
        write(read().map { if (it.id == id) it.copy(notBefore = 0, auto = true) else it })
    }

    private fun read(): List<Job> {
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return emptyList()
        val a = runCatching { JSONObject(text).optJSONArray("jobs") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            Job(id, o.optString("kind"), o.optString("payload"), o.optInt("attempts"), o.optLong("notBefore"), o.optBoolean("auto", true))
        }
    }

    private fun write(jobs: List<Job>) {
        val json = JSONObject().put("jobs", JSONArray(jobs.map { j ->
            JSONObject().put("id", j.id).put("kind", j.kind).put("payload", j.payload)
                .put("attempts", j.attempts).put("notBefore", j.notBefore).put("auto", j.auto)
        })).toString()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.capture-tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        const val SAVE_SORT = "save-sort"
        const val SAVE_TASK = "save-task"
        const val SAVE_LINES = "save-lines"
        const val SAVE_LINK = "save-link"
        const val JARVIS = "jarvis-send"
        const val JARVIS_VOICE = "jarvis-voice"
        const val MAX_ATTEMPTS = 8

        private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** True the first time. A second caller, including a Retry tap, waits rather than doing the work twice. */
        fun begin(id: String) = running.add(id)
        fun end(id: String) { running.remove(id) }

        fun id(kind: String, payload: String) = sha1("$kind\n$payload".toByteArray())

        fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

        fun backoffMs(attempts: Int) = when {
            attempts <= 1 -> 60_000L
            attempts == 2 -> 5 * 60_000L
            else -> 15 * 60_000L
        }

        fun taskPayload(text: String, due: String?) = JSONObject().put("text", text).put("due", due ?: JSONObject.NULL).toString()

        fun linesPayload(lines: List<String>) = JSONObject().put("lines", JSONArray(lines)).toString()

        fun linkPayload(url: String, tags: List<String>) = JSONObject().put("url", url).put("tags", JSONArray(tags)).toString()

        fun jarvisPayload(worker: String, text: String, chat: String, spoken: Boolean) =
            JSONObject().put("worker", worker).put("text", text).put("chat", chat).put("spoken", spoken).toString()

        /** Adds the task when Quick Task does not already have that wording and due date. */
        fun saveTask(writer: VaultWriter, payload: String) {
            val o = JSONObject(payload)
            val text = o.optString("text").trim()
            if (text.isEmpty()) return
            val due = o.optString("due").trim().takeIf { it.isNotEmpty() && it != "null" }
            val dueDate = due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (writer.listQuickTasks().any { it.text == text && it.due == dueDate }) return
            writer.addTask(text, due)
        }

        /** Appends inbox lines that are not already in the inbox note. */
        fun saveLines(writer: VaultWriter, vault: Vault, payload: String) {
            val a = JSONObject(payload).optJSONArray("lines") ?: return
            val lines = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return
            val have = vault.read("${VaultWriter.INBOX}/${VaultWriter.INBOX_NOTE}").orEmpty().lines().toSet()
            val fresh = lines.filter { it !in have }
            if (fresh.isNotEmpty()) writer.appendInbox(fresh)
        }

        fun linkSaved(vault: Vault, url: String): Boolean {
            if (url.isBlank()) return true
            val dir = "${VaultWriter.INBOX}/${VaultWriter.RIL_DIR}"
            return vault.list(dir).any { !it.isDir && it.name.endsWith(".md") && vault.read("$dir/${it.name}", 4_000)?.contains(url) == true }
        }
    }
}
