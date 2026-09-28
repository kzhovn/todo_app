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
        val flagged = QuickAddParser.parse("taxes -s next fri 9am")
        assertEquals("taxes", flagged.title)
        val start = java.util.Calendar.getInstance().apply { timeInMillis = flagged.startDate!! }
        assertEquals(dayOffset(friday), dayOffset(flagged.startDate!!))
        assertEquals(9, start.get(java.util.Calendar.HOUR_OF_DAY))
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

    @Test
    fun `a leading duration with "of" makes a timed task`() {
        fun parsed(text: String) = QuickAddParser.parse(text).let { it.title to it.durationMinutes }
        assertEquals("ticket work" to 60, parsed("1 hour of ticket work"))
        assertEquals("research" to 30, parsed("30 minutes of research"))
        assertEquals("taxes" to 90, parsed("1.5 hours of taxes"))
        assertEquals("taxes" to 90, parsed("1h 30m of taxes"))
        assertEquals("reading" to 60, parsed("an hour of reading"))
        assertEquals("tidying" to 30, parsed("half an hour of tidying"))
        assertEquals("practice" to 45, parsed("45 min of practice -d fri"))
        // "of" is required, and only a leading duration counts.
        assertEquals("2 hours drive to Bath" to null, parsed("2 hours drive to Bath"))
        assertEquals("book 1 hour of massage" to null, parsed("book 1 hour of massage"))
        val maybe = QuickAddParser.parse("20 minutes of stretching?")
        assertEquals(Triple("stretching", 20, true), Triple(maybe.title, maybe.durationMinutes, maybe.isMaybe))
    }

    // Wednesday 30 Sep 2026, 10:00.
    private val wed = java.util.Calendar.getInstance().apply { set(2026, 8, 30, 10, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    private fun date(ms: Long?) = java.text.SimpleDateFormat("EEE yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms!!))
    private fun read(text: String) = QuickAddParser.read(text, wed)

    @Test
    fun `relative and month dates`() {
        assertEquals("Sat 2026-10-03 00:00", date(read("x -d +3d").task!!.dueDate))
        assertEquals("Wed 2026-10-14 00:00", date(read("x start in 2 weeks").task!!.startDate))
        assertEquals("Fri 2026-10-30 00:00", date(read("x -s +1m").task!!.startDate))
        assertEquals("Wed 2026-09-30 12:00", date(read("x -s +2h").task!!.startDate))
        assertEquals("Mon 2026-10-12 17:00", date(read("x due oct 12 5pm").task!!.dueDate))
        assertEquals("Mon 2026-10-12 00:00", date(read("x -d 12th october").task!!.dueDate))
        assertEquals("Mon 2027-03-01 00:00", date(read("x due mar 1").task!!.dueDate)) // already past this year
        assertEquals("Mon 2026-10-05 00:00", date(read("x -s next week").task!!.startDate))
        assertEquals("Sat 2026-10-03 00:00", date(read("x start weekend").task!!.startDate))
        assertEquals("Sat 2026-10-03 00:00", date(read("x -s this weekend").task!!.startDate))
        assertEquals("x", read("x -s this weekend").task!!.title)
        // Words that only look like dates stay put.
        assertEquals("may the force be with you", read("may the force be with you").task!!.title)
    }

    @Test
    fun `repeats on a schedule start on their first day, repeats after done don't need one`() {
        val mon = read("water plants every mon, thu").task!!
        assertEquals("water plants", mon.title)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TH", mon.recurrenceRule)
        assertEquals("Thu 2026-10-01 00:00", date(mon.startDate))
        assertEquals("FREQ=DAILY", read("stretch every day").task!!.recurrenceRule)
        assertEquals("Wed 2026-09-30 00:00", date(read("stretch every day").task!!.startDate))
        assertEquals("FREQ=WEEKLY;INTERVAL=2", read("bins every 2 weeks").task!!.recurrenceRule)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", read("standup every weekday").task!!.recurrenceRule)
        val sat = read("market every 1st sat").task!!
        assertEquals("FREQ=MONTHLY;BYDAY=1SA", sat.recurrenceRule)
        assertEquals("Sat 2026-10-03 00:00", date(sat.startDate))
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", read("payday every last friday").task!!.recurrenceRule)
        val after = read("haircut every 4 weeks after done").task!!
        assertEquals(com.kzhovn.todoapp.data.RecurrenceType.AFTER_COMPLETION to "4w", after.recurrenceType to after.recurrenceRule)
        assertEquals("haircut", after.title)
        assertNull(after.startDate)
        assertEquals("read every book", read("read every book").task!!.title)
    }

    @Test
    fun `reminders, star, pin, focus, tilde durations and new checklists`() {
        assertEquals(30, read("call dentist due fri 3pm remind 30m").task!!.reminderOffsetMinutes)
        assertEquals(60, read("call dentist due fri remind me 1h before").task!!.reminderOffsetMinutes)
        assertNull(read("call dentist remind 30m").task!!.reminderOffsetMinutes) // no due date
        assertEquals("call dentist", read("call dentist remind 30m").task!!.title)

        val starred = read("call mom*")
        assertEquals("call mom" to true, starred.task!!.title to starred.task!!.isStarred)
        val maybeStar = read("call mom?*").task!!
        assert(maybeStar.isMaybe && !maybeStar.isStarred)

        val pf = read("write report -p")
        assertEquals("write report", pf.task!!.title)
        assert(pf.pin && !pf.focus)
        assert(read("write -f report").focus)

        assertEquals("taxes" to 30, read("taxes ~30m").task!!.let { it.title to it.durationMinutes })
        assertEquals(90, read("~1h 30m taxes").task!!.durationMinutes)
        assertEquals(90, read("taxes ~1.5h").task!!.durationMinutes)

        val packing = read("packing [passport, charger, toothbrush] -d fri")
        assertEquals("packing", packing.task!!.title)
        assertEquals(com.kzhovn.todoapp.data.TaskType.CHECKLIST, packing.task!!.type)
        assertEquals(listOf("passport", "charger", "toothbrush"), packing.items)
        assertNotNull(packing.task!!.dueDate)

        // A star or maybe after the items or a duration still counts.
        val starredList = read("groceries [milk, eggs]*")
        assertEquals(Triple("groceries", true, 2), Triple(starredList.task!!.title, starredList.task!!.isStarred, starredList.items.size))
        assertEquals(Triple("read book", 30, true), read("read book ~30m*").task!!.let { Triple(it.title, it.durationMinutes, it.isStarred) })
    }

    @Test
    fun `a note follows a double slash or the first line, and a URL does not count`() {
        val slash = read("call bank -d fri // ask about the fee")
        assertEquals("call bank" to "ask about the fee", slash.task!!.title to slash.task!!.notes)
        assertNotNull(slash.task!!.dueDate)
        val lines = read("call bank\nask about the fee\nref 4417-22 due fri")
        assertEquals("call bank" to "ask about the fee\nref 4417-22 due fri", lines.task!!.title to lines.task!!.notes)
        assertNull(lines.task!!.dueDate) // dates in the note stay text
        assertEquals("read https://example.com/a" to null, read("read https://example.com/a").task!!.let { it.title to it.notes })
    }
}
