package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.DAY_MS
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.DueDates
import org.junit.Assert.assertEquals
import org.junit.Test

// Due today or overdue, a task shows in Doing outside its time context's hours; a place (wifi) context
// still holds it back.
class UrgentContextsTest {
    private val now = 1_790_000_000_000L
    private val workHours = TaskContext(id = 1, name = "work", type = ContextType.TIME)
    private val home = TaskContext(id = 2, name = "home", type = ContextType.PLACE, isCurrentlySatisfied = false)
    // A window on no day of the week: never open.
    private val closed = ContextTimeWindow(contextId = 1, windowStartMinute = 0, windowEndMinute = 0, daysMask = 0)

    @Test fun `due today beats a time context, not a place one, and a later due date beats neither`() {
        val dueToday = Task(id = 10, title = "Submit report", dueDate = now)
        val overdue = Task(id = 11, title = "Pay invoice", dueDate = now - DAY_MS)
        val later = Task(id = 12, title = "Plan offsite", dueDate = now + 5 * DAY_MS)
        val atHome = Task(id = 13, title = "Water plants", dueDate = now)
        val all = listOf(dueToday, overdue, later, atHome)
        val contexts = mapOf(10L to setOf(1L), 11L to setOf(1L), 12L to setOf(1L), 13L to setOf(2L))
        val active = computeActiveTasks(all, contexts, listOf(workHours, home), listOf(closed), emptyList(), now)
        assertEquals(listOf("Submit report", "Pay invoice"), active.map { it.title })
        assertEquals(listOf("Pay invoice", "Submit report"), filterDoing(active, now, DueDates(all, emptyList())).map { it.title })
    }

    @Test fun `a filter-only context never holds a task back`() {
        val errands = TaskContext(id = 3, name = "errands", type = ContextType.LABEL)
        val stamps = Task(id = 14, title = "Buy stamps")
        val active = computeActiveTasks(listOf(stamps), mapOf(14L to setOf(3L)), listOf(errands), emptyList(), emptyList(), now)
        assertEquals(listOf("Buy stamps"), active.map { it.title })
    }

    @Test fun `a context set by hand overrides its wifi and clock, and off holds back even a task due today`() {
        val tasks = listOf(Task(id = 15, title = "Water plants"), Task(id = 16, title = "Submit report", dueDate = now))
        val contexts = mapOf(15L to setOf(2L), 16L to setOf(1L))
        fun active(home: TaskContext, work: TaskContext) =
            computeActiveTasks(tasks, contexts, listOf(work, home), listOf(closed), emptyList(), now).map { it.title }
        assertEquals(listOf("Water plants", "Submit report"), active(home.copy(forced = true), workHours))
        assertEquals(emptyList<String>(), active(home.copy(isCurrentlySatisfied = true, forced = false), workHours.copy(forced = false)))
    }
}
