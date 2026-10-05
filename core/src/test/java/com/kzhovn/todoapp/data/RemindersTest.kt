package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class RemindersTest {
    private fun at(day: Int, hour: Int, minute: Int = 0) = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private val task = Task(id = 1, title = "Renew passport")

    @Test
    fun `each kind rings at its own time, a date with no time at the reminder hour`() {
        val all = task.copy(startDate = at(6, 0), remindAtStart = true, dueDate = at(20, 17), reminderOffsetMinutes = 60, remindAt = at(10, 15, 30))
        assertEquals(mapOf(ReminderKind.START to at(6, 9), ReminderKind.DUE to at(20, 16), ReminderKind.AT to at(10, 15, 30)), reminderTimes(all, 9))
        // A date-only due date counts its "before" from the reminder hour, not midnight.
        assertEquals(at(8, 7), reminderTimes(task.copy(dueDate = at(8, 0), reminderOffsetMinutes = 60), 8)[ReminderKind.DUE])
        assertEquals("At start · 1h before due · Oct 10 3:30 PM", reminderSummary(all, 9))
        assertNull(reminderSummary(task, 9))
    }
}
