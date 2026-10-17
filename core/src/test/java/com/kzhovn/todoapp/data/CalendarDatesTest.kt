package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CalendarDatesTest {
    private val today = LocalDate.of(2026, 10, 16) // a Friday
    private val now = dayStart(today) + 12 * 3_600_000L
    private val october = YearMonth.of(2026, 10)
    private fun on(day: Int) = dayStart(LocalDate.of(2026, 10, day))
    private val all = DateKind.entries.toSet()

    private val passport = Task(id = 1, title = "Renew passport", dueDate = on(20), startDate = on(12))
    private val dentist = Task(id = 2, title = "Call dentist", dueDate = on(14))
    private val plants = Task(id = 3, title = "Water plants", startDate = on(11), recurrenceType = RecurrenceType.RRULE, recurrenceRule = "FREQ=WEEKLY;BYDAY=SU")
    private val haircut = Task(id = 4, title = "Haircut", isComplete = true, completedAt = on(9) + 3_600_000L)
    private val refund = Task(id = 5, title = "Airline refund", type = TaskType.WAITING, dueDate = on(22))
    private val folder = Task(id = 6, title = "Personal", type = TaskType.FOLDER, dueDate = on(20))
    private val tasks = listOf(passport, dentist, plants, haircut, refund, folder)

    private fun kinds(entries: List<CalendarEntry>) = entries.map { it.task.title to it.kind }

    @Test fun dueDatesOnlyByDefault() {
        val entries = calendarEntries(tasks, october.atDay(1), october.atEndOfMonth(), DEFAULT_DATE_KINDS, now, 9)
        assertEquals(listOf("Call dentist" to DateKind.DUE, "Renew passport" to DateKind.DUE), kinds(entries))
        assertEquals(listOf(true, false), entries.map { it.overdue })
    }

    @Test fun repeatsAreTheTimesAfterTheOpenOne() {
        val entries = calendarEntries(listOf(plants), october.atDay(1), october.atEndOfMonth(), setOf(DateKind.REPEAT), now, 9)
        assertEquals(listOf(18, 25), entries.map { it.day.dayOfMonth })
    }

    @Test fun everyKindLandsOnItsDay() {
        val entries = calendarEntries(tasks, october.atDay(1), october.atEndOfMonth(), all, now, 9)
        val byDay = entries.groupBy({ it.day.dayOfMonth }, { it.task.title to it.kind })
        assertEquals(listOf("Haircut" to DateKind.DONE), byDay[9])
        assertEquals(listOf("Water plants" to DateKind.START), byDay[11])
        assertEquals(listOf("Renew passport" to DateKind.START), byDay[12])
        assertEquals(listOf("Airline refund" to DateKind.WAITING), byDay[22])
    }

    @Test fun thisMonthsAgendaRunsFromTodayWithOverdueFirst() {
        val days = agenda(tasks, october, null, DEFAULT_DATE_KINDS, now, 9)
        assertEquals(listOf(null, LocalDate.of(2026, 10, 20)), days.map { it.day })
        assertEquals(listOf("Call dentist"), days[0].entries.map { it.task.title })
    }

    @Test fun anotherMonthStartsOnItsFirstAndASelectedDayShowsAlone() {
        assertEquals(LocalDate.of(2026, 9, 28), gridRange(october).first)
        val past = agenda(tasks, october, LocalDate.of(2026, 10, 14), DEFAULT_DATE_KINDS, now, 9)
        assertEquals(listOf(AgendaDay(LocalDate.of(2026, 10, 14), listOf(CalendarEntry(dentist, LocalDate.of(2026, 10, 14), DateKind.DUE, on(14), overdue = true)))), past)
        val empty = agenda(tasks, october, LocalDate.of(2026, 10, 15), DEFAULT_DATE_KINDS, now, 9)
        assertEquals(listOf(AgendaDay(LocalDate.of(2026, 10, 15), emptyList())), empty)
        val november = agenda(tasks + passport.copy(id = 7, dueDate = dayStart(LocalDate.of(2026, 11, 3))), YearMonth.of(2026, 11), null, DEFAULT_DATE_KINDS, now, 9)
        assertEquals(listOf(LocalDate.of(2026, 11, 3)), november.map { it.day })
    }

    @Test fun headingsNameTheNearDays() {
        assertEquals(listOf("Overdue", "Today · Oct 16", "Tomorrow · Oct 17", "Mon · Oct 19"), listOf(null, today, today.plusDays(1), today.plusDays(3)).map { Labels.agendaHeading(it, today) })
    }
}
