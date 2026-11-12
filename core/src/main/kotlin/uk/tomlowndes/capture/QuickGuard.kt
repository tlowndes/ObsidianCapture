package uk.tomlowndes.capture

import java.io.File

/**
 * Quick Task guard (quick-task-guard-v1): each device keeps its own copies of ⚡ Quick Task (the last [KEEP], outside
 * the vault, so Sync can't touch them). Every read is compared with the last good copy; changes the app wouldn't make
 * (steps losing their indent, several open tasks gone, the note emptied or missing) raise an [Alert]. Nothing in the
 * note changes until the person picks Fix it or Restore.
 */
object QuickGuard {
    const val KEEP = 10
    /** Open tasks that can vanish at once before it counts as odd. */
    const val MISSING_AT = 3

    /** A step that was indented under [parent] and now isn't: [was] is its old line, [now] its line today. */
    data class Flat(val parent: String, val was: String, val now: String)

    data class Alert(val flattened: List<Flat> = emptyList(), val missing: List<String> = emptyList(), val emptied: Boolean = false) {
        val isEmpty get() = flattened.isEmpty() && missing.isEmpty() && !emptied
        /** Only steps flattened: Fix it can put them back without a restore. */
        val fixable get() = flattened.isNotEmpty() && missing.isEmpty() && !emptied

        /** One line for the banner, e.g. "5 steps of Sort shelves in garage lost their indent". */
        fun headline(): String {
            val parts = mutableListOf<String>()
            if (emptied) parts += "Quick Task is empty or missing"
            if (flattened.isNotEmpty()) {
                val parents = flattened.map { it.parent }.distinct()
                val n = flattened.size
                parts += "$n step${if (n == 1) "" else "s"}" +
                    (if (parents.size == 1) " of ${TaskNote.parse(parents[0])?.text?.let { Tags.strip(it) } ?: "a task"}" else "") +
                    " lost ${if (n == 1) "its" else "their"} indent"
            }
            if (missing.isNotEmpty()) parts += "${missing.size} open tasks disappeared"
            return parts.joinToString(" · ")
        }
    }

    private fun depth(line: String) = Subtasks.depth(line)
    private fun lines(text: String) = text.replace("\r\n", "\n").split("\n")

    /** What looks off in [now] compared with [good]. Normal edits (new tasks, ticks, a re-worded task) don't count. */
    fun check(good: String, now: String?): Alert {
        val before = lines(good)
        val goodOpen = TaskNote.list(before)
        if (now == null || now.isBlank()) return Alert(emptied = goodOpen.isNotEmpty())
        val after = lines(now)
        if (goodOpen.isNotEmpty() && after.none { it.trim() == "## Active" } && TaskNote.list(after).isEmpty()) return Alert(emptied = true)

        // Steps: same line, indent gone.
        val parents = Subtasks.parents(goodOpen.map { it.copy(path = VaultTasks.QUICK_TASK) })
        val afterSet = after.toSet()
        val flat = mutableListOf<Flat>()
        for (t in goodOpen) {
            val p = parents[t] ?: continue
            if (t.raw in afterSet) continue
            val bare = t.raw.trimStart()
            val hit = after.firstOrNull { it.trimStart() == bare && depth(it) <= depth(p.raw) } ?: continue
            flat += Flat(p.raw, t.raw, hit)
        }

        // Open tasks gone: not open any more (by wording) and not ticked into the Archive either.
        val openNow = TaskNote.list(after).map { it.text }.toSet()
        val doneNow = TaskNote.listDone(after).map { it.text }.toSet() +
            after.mapNotNull { TaskNote.parseDone(it)?.text }
        val missing = goodOpen.filter { it.text !in openNow && it.text !in doneNow }.map { it.text }
        return Alert(flattened = flat, missing = if (missing.size >= MISSING_AT) missing else emptyList())
    }

    /** [now] with the flattened steps put back, indented, under their parents (after any steps already there). */
    fun fix(now: List<String>, a: Alert): List<String> {
        val out = now.toMutableList()
        a.flattened.forEach { f -> out.indexOf(f.now).takeIf { it >= 0 }?.let { out.removeAt(it) } }
        a.flattened.groupBy { it.parent }.forEach { (parent, steps) ->
            val i = out.indexOf(parent).takeIf { it >= 0 }
                ?: out.indexOfFirst { l -> TaskNote.parse(l)?.text == TaskNote.parse(parent)?.text && TaskNote.parse(parent) != null }
            if (i < 0) {
                steps.forEach { TaskNote.insertActive(out, it.now) } // parent gone: keep the steps, as they were
                return@forEach
            }
            val d = depth(out[i])
            var end = i + 1
            while (end < out.size && out[end].isNotBlank() && depth(out[end]) > d) end++
            out.addAll(end, steps.map { it.was })
        }
        return out
    }

    /** One saved copy. [good] is false for a copy that raised an alert. */
    data class Copy(val file: File, val at: Long, val good: Boolean) {
        fun text(): String = file.readText()
    }

    /** The copies on this device, in [dir], newest first. */
    class Store(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
        private val name = Regex("^qt-(\\d+)-(good|odd)\\.md$")

        fun copies(): List<Copy> = (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
            name.matchEntire(f.name)?.let { Copy(f, it.groupValues[1].toLong(), it.groupValues[2] == "good") }
        }.sortedByDescending { it.at }

        fun lastGood(): Copy? = copies().firstOrNull { it.good }

        fun save(text: String, good: Boolean) {
            val latest = copies().firstOrNull()
            if (latest != null && latest.good == good && runCatching { latest.text() }.getOrNull() == text) return
            dir.mkdirs()
            var t = now()
            if (latest != null && t <= latest.at) t = latest.at + 1
            File(dir, "qt-$t-${if (good) "good" else "odd"}.md").writeText(text)
            copies().drop(KEEP).forEach { it.file.delete() }
        }

        /** Compares [text] (null: note missing) with the last good copy, saves it, and returns what's off, if anything. */
        fun seen(text: String?): Alert? {
            val good = lastGood()
            if (good == null) { if (text != null) save(text, true); return null }
            val goodText = runCatching { good.text() }.getOrNull() ?: return null
            if (text == goodText) return null
            val a = check(goodText, text)
            if (a.isEmpty) { text?.let { save(it, true) }; return null }
            text?.let { save(it, false) }
            return a
        }

        /** The alert still waiting, if the newest copy raised one. */
        fun pending(): Alert? {
            val c = copies()
            val latest = c.firstOrNull() ?: return null
            if (latest.good) return null
            val good = c.firstOrNull { it.good } ?: return null
            return check(good.text(), latest.text()).takeUnless { it.isEmpty }
        }
    }
}
