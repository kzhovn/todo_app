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

    @Test
    fun `a question mark ending the title marks a maybe, one inside it does not`() {
        val maybe = QuickAddParser.parse("update bug? due today")
        assertEquals("update bug", maybe.title)
        assert(maybe.isMaybe)
        assertNotNull(maybe.dueDate)

        val notMaybe = QuickAddParser.parse("update(?) bug due today")
        assertEquals("update(?) bug", notMaybe.title)
        assert(!notMaybe.isMaybe)
    }

    private fun hourMinute(ms: Long?) = java.util.Calendar.getInstance().apply { timeInMillis = ms!! }
        .let { it.get(java.util.Calendar.HOUR_OF_DAY) to it.get(java.util.Calendar.MINUTE) }

    @Test
    fun `times attach to dates in words and flags`() {
        val task = QuickAddParser.parse("call dentist due today 5pm")
        assertEquals("call dentist", task.title)
        assertEquals(0, dayOffset(com.kzhovn.todoapp.data.atTime(task.dueDate!!, 0, 0)))
        assertEquals(17 to 0, hourMinute(task.dueDate))

        assertEquals(9 to 30, hourMinute(QuickAddParser.parse("x due fri at 9:30am").dueDate))
        assertEquals(14 to 0, hourMinute(QuickAddParser.parse("x -d tomorrow 14:00").dueDate))
        assertEquals(1, dayOffset(QuickAddParser.parse("x -d tomorrow 14:00").dueDate?.let { com.kzhovn.todoapp.data.atTime(it, 0, 0) }))
        assertEquals(0 to 0, hourMinute(QuickAddParser.parse("x due 12am").dueDate))
        assertEquals(12 to 15, hourMinute(QuickAddParser.parse("x start 12:15pm").startDate))
    }

    @Test
    fun `a time alone means today, and bare numbers stay in the title`() {
        val task = QuickAddParser.parse("pick up kids due 3pm")
        assertEquals("pick up kids", task.title)
        assertEquals(0, dayOffset(com.kzhovn.todoapp.data.atTime(task.dueDate!!, 0, 0)))
        assertEquals(15 to 0, hourMinute(task.dueDate))

        val apples = QuickAddParser.parse("buy 3 apples due today")
        assertEquals("buy 3 apples", apples.title)
        assert(!com.kzhovn.todoapp.data.hasTime(apples.dueDate!!))
    }
}
