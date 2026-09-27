package com.kzhovn.todoapp.recurrence

import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.RecurrenceType
import com.kzhovn.todoapp.data.Task
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatOptionsTest {
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun Long.date() = java.time.Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

    @Test
    fun `new options survive a round trip through the stored rule`() {
        listOf(
            RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.MONTH, monthlyNth = 1, monthlyWeekday = 6),
            RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.MONTH, monthlyNth = -1, monthlyWeekday = 5, count = 4),
            RecurrenceSelection(RecurrencePreset.CALENDAR, n = 2, unit = RecurrenceUnit.WEEK, weekdaysMask = 0b0100, until = day(2026, 12, 31)),
            RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 2, unit = RecurrenceUnit.WEEK),
            RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 3, unit = RecurrenceUnit.MONTH),
        ).forEach { selection ->
            val (type, rule) = selection.toTaskFields()
            assertEquals(rule, selection, recurrenceSelectionFromTask(type, rule))
        }
    }

    @Test
    fun `monthly on the first Saturday lands on first Saturdays`() {
        val (_, rule) = RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.MONTH, monthlyNth = 1, monthlyWeekday = 6).toTaskFields()
        val next = RecurrenceEngine.preview(RecurrenceType.RRULE, rule!!, anchor = day(2026, 10, 3), now = day(2026, 9, 27))
        assertEquals(listOf(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 11, 7), LocalDate.of(2026, 12, 5)), next.map { it.date() })
    }

    @Test
    fun `after N times counts down one per instance, and the last spawns nothing`() {
        val (type, rule) = RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.DAY, count = 2).toTaskFields()
        val first = Task(id = 1, title = "x", startDate = day(2026, 9, 1), recurrenceType = type, recurrenceRule = rule)
        val second = RecurrenceEngine.nextInstance(first, day(2026, 9, 1) + 1)!!
        assertEquals("FREQ=DAILY;COUNT=1", second.recurrenceRule)
        assertNull(RecurrenceEngine.nextInstance(second, second.startDate!! + 1))
    }

    @Test
    fun `ending on a date stops after it`() {
        val (type, rule) = RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.DAY, until = day(2026, 9, 2)).toTaskFields()
        val task = Task(id = 1, title = "x", startDate = day(2026, 9, 1), recurrenceType = type, recurrenceRule = rule)
        assertEquals(LocalDate.of(2026, 9, 2), RecurrenceEngine.nextInstance(task, day(2026, 9, 1) + 1)!!.startDate!!.date())
        assertNull(RecurrenceEngine.nextInstance(task, day(2026, 9, 2) + 1))
    }

    @Test
    fun `after completion counts weeks and calendar months`() {
        val done = day(2026, 1, 31)
        fun next(rule: String) = RecurrenceEngine.nextInstance(Task(id = 1, title = "x", recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = rule), done)!!.startDate!!.date()
        assertEquals(LocalDate.of(2026, 2, 3), next("3"))
        assertEquals(LocalDate.of(2026, 2, 14), next("2w"))
        assertEquals(LocalDate.of(2026, 2, 28), next("1m"))
    }

    @Test
    fun `pill text covers weekdays, nth weekday, ends and units`() {
        assertEquals("Every weekday", Labels.repeat(RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.WEEK, weekdaysMask = WEEKDAYS_MASK)))
        assertEquals("Every month · first Sat · 10 times", Labels.repeat(RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.MONTH, monthlyNth = 1, monthlyWeekday = 6, count = 10)))
        assertEquals("2 weeks after completion", Labels.repeat(RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 2, unit = RecurrenceUnit.WEEK)))
        assertEquals("Every day · until Dec 31", Labels.repeat(RecurrenceSelection(RecurrencePreset.CALENDAR, until = day(2026, 12, 31))))
    }
}
