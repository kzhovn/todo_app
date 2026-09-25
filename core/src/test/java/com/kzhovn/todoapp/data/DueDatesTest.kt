package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class DueDatesTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0) =
        Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private val now = at(2026, Calendar.SEPTEMBER, 25, 10) // a Friday

    @Test
    fun `status is today until the day ends, then overdue, and a time makes it overdue at that time`() {
        assertEquals(DueStatus.TODAY, dueStatus(at(2026, Calendar.SEPTEMBER, 25), now))
        assertEquals(DueStatus.TODAY, dueStatus(at(2026, Calendar.SEPTEMBER, 25, 15), now))
        assertEquals(DueStatus.OVERDUE, dueStatus(at(2026, Calendar.SEPTEMBER, 25, 9), now))
        assertEquals(DueStatus.OVERDUE, dueStatus(at(2026, Calendar.SEPTEMBER, 24), now))
        assertEquals(DueStatus.LATER, dueStatus(at(2026, Calendar.SEPTEMBER, 26), now))
    }

    @Test
    fun `due text reads naturally`() {
        assertEquals("due today", dueText(at(2026, Calendar.SEPTEMBER, 25), now))
        assertEquals("due 3:00 PM", dueText(at(2026, Calendar.SEPTEMBER, 25, 15), now))
        assertEquals("due tomorrow", dueText(at(2026, Calendar.SEPTEMBER, 26), now))
        assertEquals("due yesterday", dueText(at(2026, Calendar.SEPTEMBER, 24), now))
        assertEquals("due Wed", dueText(at(2026, Calendar.SEPTEMBER, 30), now))
        assertEquals("due Oct 3", dueText(at(2026, Calendar.OCTOBER, 3), now))
        assertEquals("due Sep 20", dueText(at(2026, Calendar.SEPTEMBER, 20), now))
    }

    @Test
    fun `days compare by calendar day, across a year boundary`() {
        val newYearsEve = at(2025, Calendar.DECEMBER, 31, 23)
        assertEquals("due tomorrow", dueText(at(2026, Calendar.JANUARY, 1), newYearsEve))
    }
}
