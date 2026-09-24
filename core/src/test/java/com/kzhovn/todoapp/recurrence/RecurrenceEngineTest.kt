package com.kzhovn.todoapp.recurrence

import com.kzhovn.todoapp.data.RecurrenceType
import com.kzhovn.todoapp.data.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class RecurrenceEngineTest {
    @Test
    fun `after-completion recurrence schedules N days after completion`() {
        val completedAt = 1_700_000_000_000L
        val task = Task(
            id = 1, title = "Water plants",
            recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "3"
        )

        val next = RecurrenceEngine.nextInstance(task, completedAt)

        assertEquals(completedAt + TimeUnit.DAYS.toMillis(3), next?.startDate)
        assertEquals(false, next?.isComplete)
    }

    @Test
    fun `rrule recurrence schedules the next occurrence after completion`() {
        val dtStart = 1_700_000_000_000L
        val completedAt = dtStart + TimeUnit.DAYS.toMillis(1)
        val task = Task(
            id = 1, title = "Every 4 days", startDate = dtStart,
            recurrenceType = RecurrenceType.RRULE, recurrenceRule = "FREQ=DAILY;INTERVAL=4"
        )

        val next = RecurrenceEngine.nextInstance(task, completedAt)

        assertEquals(dtStart + TimeUnit.DAYS.toMillis(4), next?.startDate)
    }

    @Test
    fun `non-recurring task produces no next instance`() {
        val task = Task(id = 1, title = "One-off")
        assertNull(RecurrenceEngine.nextInstance(task, 1_700_000_000_000L))
    }

    @Test
    fun `after-completion recurrence shifts the due date by the same amount as the start date`() {
        val completedAt = 1_700_000_000_000L
        val originalDue = completedAt + TimeUnit.DAYS.toMillis(1) // due 1 day after this completion
        val task = Task(
            id = 1, title = "Water plants", dueDate = originalDue,
            recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "3"
        )

        val next = RecurrenceEngine.nextInstance(task, completedAt)

        // No original startDate, so the anchor is completedAt: shift = (completedAt + 3 days) - completedAt = 3 days.
        assertEquals(originalDue + TimeUnit.DAYS.toMillis(3), next?.dueDate)
    }

    @Test
    fun `rrule recurrence shifts the due date by the same amount as the start date`() {
        val dtStart = 1_700_000_000_000L
        val originalDue = dtStart + TimeUnit.DAYS.toMillis(2) // due 2 days after the original start
        val completedAt = dtStart + TimeUnit.DAYS.toMillis(1)
        val task = Task(
            id = 1, title = "Every 4 days", startDate = dtStart, dueDate = originalDue,
            recurrenceType = RecurrenceType.RRULE, recurrenceRule = "FREQ=DAILY;INTERVAL=4"
        )

        val next = RecurrenceEngine.nextInstance(task, completedAt)

        // Anchor is the original startDate (dtStart): shift = (dtStart + 4 days) - dtStart = 4 days.
        assertEquals(originalDue + TimeUnit.DAYS.toMillis(4), next?.dueDate)
    }

    @Test
    fun `a task with no due date still spawns a next instance with no due date`() {
        val task = Task(
            id = 1, title = "No due date", recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "3"
        )
        val next = RecurrenceEngine.nextInstance(task, 1_700_000_000_000L)
        assertNull(next?.dueDate)
    }

    @Test
    fun `untouchedSuccessor finds the spawned instance but not an edited one`() {
        val completed = Task(
            id = 1, title = "Water plants", isComplete = true, completedAt = 1_700_000_000_000L,
            recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "3"
        )
        val spawned = RecurrenceEngine.nextInstance(completed, completed.completedAt!!)!!

        assertEquals(spawned, RecurrenceEngine.untouchedSuccessor(completed, listOf(completed, spawned)))
        assertNull(RecurrenceEngine.untouchedSuccessor(completed, listOf(completed, spawned.copy(title = "Edited"))))
    }
}
