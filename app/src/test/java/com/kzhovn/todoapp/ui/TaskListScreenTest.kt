package com.kzhovn.todoapp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TaskListScreenTest {
    private fun millisFor(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `isSameDay is true for same day same time`() {
        val t = millisFor(2026, Calendar.AUGUST, 25)
        assertTrue(isSameDay(t, t))
    }

    @Test
    fun `isSameDay is true for same day different hour`() {
        val a = millisFor(2026, Calendar.AUGUST, 25, hour = 1)
        val b = millisFor(2026, Calendar.AUGUST, 25, hour = 23)
        assertTrue(isSameDay(a, b))
    }

    @Test
    fun `isSameDay is false for different days`() {
        val a = millisFor(2026, Calendar.AUGUST, 25)
        val b = millisFor(2026, Calendar.AUGUST, 26)
        assertFalse(isSameDay(a, b))
    }

    @Test
    fun `isSameDay is false across a year boundary`() {
        val a = millisFor(2025, Calendar.DECEMBER, 31)
        val b = millisFor(2026, Calendar.JANUARY, 1)
        assertFalse(isSameDay(a, b))
    }

    @Test
    fun `isOverdue is false for a complete task with a past due date`() {
        val now = millisFor(2026, Calendar.AUGUST, 25)
        assertFalse(isOverdue(isComplete = true, dueDate = now - 1000, now = now))
    }

    @Test
    fun `isOverdue is true for an incomplete task with a past due date`() {
        val now = millisFor(2026, Calendar.AUGUST, 25)
        assertTrue(isOverdue(isComplete = false, dueDate = now - 1000, now = now))
    }

    @Test
    fun `isOverdue is false for an incomplete task with a future due date`() {
        val now = millisFor(2026, Calendar.AUGUST, 25)
        assertTrue(!isOverdue(isComplete = false, dueDate = now + 1000, now = now))
    }

    @Test
    fun `isOverdue is false for an incomplete task with no due date`() {
        val now = millisFor(2026, Calendar.AUGUST, 25)
        assertFalse(isOverdue(isComplete = false, dueDate = null, now = now))
    }
}
