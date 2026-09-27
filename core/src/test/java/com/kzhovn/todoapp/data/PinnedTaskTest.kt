package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedTaskTest {
    @Test
    fun `the newest open pin wins`() {
        val older = Task(id = 1, title = "A", pinnedAt = 100)
        val newer = Task(id = 2, title = "B", pinnedAt = 200)
        assertEquals(newer, pinnedTask(listOf(older, newer, Task(id = 3, title = "C"))))
        assertEquals(older, pinnedTask(listOf(older, newer.copy(isComplete = true))))
        assertNull(pinnedTask(listOf(Task(id = 3, title = "C"))))
    }

    @Test
    fun `a recurring task's next instance isn't pinned`() {
        val task = Task(id = 1, title = "Meds", recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "1", pinnedAt = 100)
        assertNull(RecurrenceEngine.nextInstance(task, 1_000_000)!!.pinnedAt)
    }
}
