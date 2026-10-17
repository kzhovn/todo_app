package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

// The calendar (docs/superpowers/specs/2026-10-16-calendar-mockups.html): the dates tasks carry, on the days
// they fall, by kind. Due dates show unless turned off; the rest are turned on by hand.
enum class DateKind { DUE, START, REPEAT, WAITING, REMINDER, DONE }

val DEFAULT_DATE_KINDS = setOf(DateKind.DUE)

// `overdue`: a due date already passed (the overdue colour).
data class CalendarEntry(val task: Task, val day: LocalDate, val kind: DateKind, val at: Long, val overdue: Boolean = false)

// Under its heading: `day` null is Overdue.
data class AgendaDay(val day: LocalDate?, val entries: List<CalendarEntry>)

fun localDay(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
fun dayStart(day: LocalDate): Long = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

// Every date from `from` to `to` (both included) of the kinds asked for, by day; in folder mode, the mode's only.
// A waiting item's check-in already come round sits on today, where Doing shows it. Repeats are the times after
// the one open now (see RecurrenceEngine.upcoming); a maybe is listed like any task (the rows dim it).
fun calendarEntries(tasks: List<Task>, from: LocalDate, to: LocalDate, kinds: Set<DateKind>, now: Long, reminderHour: Int, modeFolderId: Long? = null): List<CalendarEntry> {
    val byId = tasks.associateBy { it.id }
    val first = dayStart(from)
    val end = dayStart(to.plusDays(1))
    val today = dayStart(localDay(now))
    return tasks.filter { !it.type.isContainer && inMode(it, modeFolderId, byId) }.flatMap { t ->
        buildList {
            fun put(kind: DateKind, at: Long?, overdue: Boolean = false) {
                if (at != null && kind in kinds && at >= first && at < end) add(CalendarEntry(t, localDay(at), kind, at, overdue))
            }
            if (t.isComplete) {
                put(DateKind.DONE, t.completedAt)
                return@buildList
            }
            if (t.type == TaskType.WAITING) {
                put(DateKind.WAITING, t.dueDate ?: maxOf(nextCheckIn(t, now), today))
            } else {
                t.dueDate?.let { put(DateKind.DUE, it, dueStatus(it, now) == DueStatus.OVERDUE) }
                put(DateKind.START, t.startDate)
                if (DateKind.REPEAT in kinds) RecurrenceEngine.upcoming(t, now, end).forEach { put(DateKind.REPEAT, it) }
            }
            reminderTimes(t, reminderHour).values.forEach { put(DateKind.REMINDER, it) }
        }
    }.sortedWith(compareBy({ it.day }, { it.kind }, { it.at }))
}

// Under the grid: one day (`selected`), or the month shown, from today on if it's this month. Whichever takes
// in today starts with everything overdue, wherever it was due. A task shows once a day, by its first kind.
fun agenda(tasks: List<Task>, month: YearMonth, selected: LocalDate?, kinds: Set<DateKind>, now: Long, reminderHour: Int, modeFolderId: Long? = null): List<AgendaDay> {
    val today = localDay(now)
    val from = selected ?: if (YearMonth.from(today) == month) today else month.atDay(1)
    val to = selected ?: month.atEndOfMonth()
    val withOverdue = DateKind.DUE in kinds && today in from..to
    val overdue = if (!withOverdue) emptyList() else {
        val byId = tasks.associateBy { it.id }
        tasks.filter { t -> !t.isComplete && t.type != TaskType.WAITING && !t.type.isContainer && inMode(t, modeFolderId, byId) && t.dueDate?.let { dueStatus(it, now) } == DueStatus.OVERDUE }
            .map { CalendarEntry(it, localDay(it.dueDate!!), DateKind.DUE, it.dueDate, overdue = true) }
            .sortedBy { it.at }
    }
    val shownOverdue = overdue.map { it.task.id }.toSet()
    val days = calendarEntries(tasks, from, to, kinds, now, reminderHour, modeFolderId)
        .filter { it.task.id !in shownOverdue }
        .groupBy { it.day }
        .map { (day, entries) -> AgendaDay(day, entries.distinctBy { it.task.id }) }
    return listOfNotNull(AgendaDay(null, overdue).takeIf { overdue.isNotEmpty() }) +
        days.ifEmpty { listOfNotNull(selected?.let { AgendaDay(it, emptyList()) }) }
}

// The six weeks a month grid shows (Monday first), to fetch dots or titles for.
fun gridRange(month: YearMonth): Pair<LocalDate, LocalDate> {
    val first = month.atDay(1).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
    return first to first.plusDays(41)
}
