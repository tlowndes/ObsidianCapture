package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtasksTest {
    private fun t(raw: String, path: String = "a.md") = TaskNote.parse(raw)!!.copy(path = path)

    @Test fun stepsSitUnderTheirTask() {
        val parent = t("- [ ] Sort shelves in garage #task")
        val a = t("    - [ ] Clear the floor #task ⏱ 10m")
        val b = t("    - [ ] Bin the broken bits #task ⏱ 5m")
        val other = t("- [ ] Ring the bank #task")
        val all = listOf(parent, a, b, other)
        val n = Subtasks.nest(all.reversed(), all)
        assertEquals(listOf(other, parent), n.top)
        assertEquals(listOf(a, b), n.children[parent])
    }

    @Test fun aStepWithoutItsTaskStaysOnItsOwn() {
        val parent = t("- [ ] Sort shelves #task")
        val a = t("    - [ ] Clear the floor #task")
        val n = Subtasks.nest(listOf(a), listOf(parent, a))
        assertEquals(listOf(a), n.top)
    }

    @Test fun notesAreKeptApart() {
        val parent = t("- [ ] Sort shelves #task", "a.md")
        val a = t("    - [ ] Clear the floor #task", "b.md")
        assertEquals(emptyMap<TaskItem, TaskItem>(), Subtasks.parents(listOf(parent, a)))
    }
}
