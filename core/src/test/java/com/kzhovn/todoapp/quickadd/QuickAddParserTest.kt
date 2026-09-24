package com.kzhovn.todoapp.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuickAddParserTest {
    @Test
    fun `plain title with no flags`() {
        val task = QuickAddParser.parse("Buy milk")
        assertEquals("Buy milk", task.title)
        assertNull(task.startDate)
        assertNull(task.dueDate)
    }

    @Test
    fun `start flag with today keyword`() {
        val task = QuickAddParser.parse("Water plants -s today")
        assertEquals("Water plants", task.title)
        assertNotNull(task.startDate)
    }

    @Test
    fun `due flag with iso date`() {
        val task = QuickAddParser.parse("File taxes -d 2026-04-15")
        assertEquals("File taxes", task.title)
        assertNotNull(task.dueDate)
    }

    @Test
    fun `both flags are stripped from the title`() {
        val task = QuickAddParser.parse("Plan trip -s tomorrow -d 2026-09-01")
        assertEquals("Plan trip", task.title)
        assertNotNull(task.startDate)
        assertNotNull(task.dueDate)
    }

    @Test
    fun `unparseable date value is ignored, flag stripped from title`() {
        val task = QuickAddParser.parse("Do thing -s whenever")
        assertEquals("Do thing", task.title)
        assertNull(task.startDate)
    }

    private fun dayOffset(date: Long?): Int {
        val today = java.util.Calendar.getInstance().startOfDay()
        return Math.round((date!! - today) / 86_400_000.0).toInt()
    }

    @Test
    fun `natural due and start phrases set dates and leave the title clean`() {
        val task = QuickAddParser.parse("update bug due today")
        assertEquals("update bug", task.title)
        assertEquals(0, dayOffset(task.dueDate))

        val later = QuickAddParser.parse("start tomorrow draft report due 2026-10-01")
        assertEquals("draft report", later.title)
        assertEquals(1, dayOffset(later.startDate))
        assertNotNull(later.dueDate)
    }

    @Test
    fun `weekdays resolve to the next such day, never today`() {
        val friday = QuickAddParser.parse("call bank due Fri").dueDate
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = friday!! }
        assertEquals(java.util.Calendar.FRIDAY, cal.get(java.util.Calendar.DAY_OF_WEEK))
        assert(dayOffset(friday) in 1..7)
        assertEquals(friday, QuickAddParser.parse("call bank due next friday").dueDate)
        assertEquals(friday, QuickAddParser.parse("call bank -d friday").dueDate)
    }

    @Test
    fun `due and start without a date stay in the title`() {
        val task = QuickAddParser.parse("pay the due bill, start today's workout")
        assertEquals("pay the due bill, start today's workout", task.title)
        assertNull(task.dueDate)
        assertNull(task.startDate)
    }
}
