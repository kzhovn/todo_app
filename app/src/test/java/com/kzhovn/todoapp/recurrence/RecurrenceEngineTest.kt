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
}
