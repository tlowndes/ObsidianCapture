package uk.tomlowndes.capture

/**
 * Subtasks: an open task indented under another open task in the same note (as Break it down writes them) is
 * drawn under that task instead of on its own.
 */
object Subtasks {
    data class Nested(val top: List<TaskItem>, val children: Map<TaskItem, List<TaskItem>>)

    fun depth(raw: String): Int = raw.takeWhile { it == ' ' || it == '\t' }.replace("\t", "    ").length

    /** Each task's parent: the nearest earlier task in the same note with a smaller indent. [tasks] keep note order. */
    fun parents(tasks: List<TaskItem>): Map<TaskItem, TaskItem> {
        val out = mutableMapOf<TaskItem, TaskItem>()
        tasks.groupBy { it.path }.values.forEach { inNote ->
            val stack = ArrayDeque<TaskItem>()
            for (t in inNote) {
                val d = depth(t.raw)
                while (stack.isNotEmpty() && depth(stack.last().raw) >= d) stack.removeLast()
                stack.lastOrNull()?.let { out[t] = it }
                stack.addLast(t)
            }
        }
        return out
    }

    /**
     * [shown] split into top-level rows and their subtasks. [all] is the full open list in note order, so a parent
     * is found even when [shown] is sorted. A subtask whose parent isn't shown stays top-level.
     */
    fun nest(shown: List<TaskItem>, all: List<TaskItem>): Nested {
        val parent = parents(all)
        val visible = shown.toSet()
        fun shownParent(t: TaskItem): TaskItem? {
            var p = parent[t]
            while (p != null && p !in visible) p = parent[p]
            return p
        }
        val kids = linkedMapOf<TaskItem, MutableList<TaskItem>>()
        val top = mutableListOf<TaskItem>()
        val order = all.withIndex().associate { it.value to it.index }
        shown.forEach { t -> shownParent(t)?.let { kids.getOrPut(it) { mutableListOf() } += t } ?: top.add(t) }
        return Nested(top, kids.mapValues { (_, v) -> v.sortedBy { order[it] ?: Int.MAX_VALUE } })
    }
}
