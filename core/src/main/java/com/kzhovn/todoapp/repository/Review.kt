package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.deadline
import com.kzhovn.todoapp.data.inMode
import com.kzhovn.todoapp.data.isChecklistItem
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.outermostFolder
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Review's zoom levels, each a rolling window ending on a chosen day (today by default).
enum class Zoom(val days: Int) { WEEK(7), MONTH(30), YEAR(364), ALL(0) }

// One bar: the days first..last, and what was completed on them.
data class ReviewBar(val first: LocalDate, val last: LocalDate, val tasks: List<Task>)

// A task with a length of time: how long it waited, or how late it was done.
data class Aged(val task: Task, val ms: Long)

private const val DAY_MS = 24L * 60 * 60 * 1000

val DONE_BUCKETS = listOf("Same day" to 1, "1–2 days" to 3, "3–6 days" to 7, "1–3 weeks" to 21, "3 wk–3 mo" to 90, "3+ months" to Int.MAX_VALUE)
val WAITING_BUCKETS = listOf("Under a week" to 7, "1–3 weeks" to 21, "3 wk–3 mo" to 90, "3+ months" to Int.MAX_VALUE)

// Days follow the rollover hour, so a task finished at 1am (with a 4am rollover) counts toward the
// previous day, matching "Today only".
fun appDay(ms: Long, rolloverHour: Int): LocalDate =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).minusHours(rolloverHour.toLong()).toLocalDate()

// Ids are the creation time shifted left 11 bits (see newId); tasks from before sync have small
// autoincrement ids and no known creation time.
fun createdAt(task: Task): Long? = (task.id shr 11).takeIf { it >= 1_577_836_800_000L } // 2020-01-01

// When a task could first be worked on: created, or its start date if that's later.
// ponytail: the task's own start date; an inherited one (a parent's) isn't looked up.
fun readyAt(task: Task): Long? = listOfNotNull(createdAt(task), task.startDate).maxOrNull()

fun bucketCounts(ages: List<Aged>, buckets: List<Pair<String, Int>>): List<Pair<String, Int>> {
    var lower = 0L
    return buckets.map { (label, days) ->
        val upper = if (days == Int.MAX_VALUE) Long.MAX_VALUE else days * DAY_MS
        (label to ages.count { it.ms in lower until upper }).also { lower = upper }
    }
}

fun medianMs(ages: List<Aged>): Long? = ages.map { it.ms }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

// "same day", "3d", "2w", "4mo".
fun shortAge(ms: Long, underADay: String = "same day"): String {
    val days = ms / DAY_MS
    return when {
        days < 1 -> underADay
        days < 14 -> "${days}d"
        days < 60 -> "${days / 7}w"
        else -> "${days / 30}mo"
    }
}

// The median per top-level folder (null: no folder), in the order the folders first appear.
fun medianByTopFolder(ages: List<Aged>, byId: Map<Long, Task>): List<Pair<Task?, Long>> =
    ages.groupBy { outermostFolder(it.task, byId) }.mapNotNull { (folder, group) -> medianMs(group)?.let { folder to it } }

class ReviewPage(
    val zoom: Zoom,
    val first: LocalDate,
    val end: LocalDate,
    val today: LocalDate,
    val bars: List<ReviewBar>,
    // Completed in the window, oldest first. A completed checklist counts once; its items don't count.
    val done: List<Task>,
    val previousDone: List<Task>?, // the window before (null for All)
    val waiting: List<Aged>, // Active ignoring contexts, longest waiting first
    // Year and All list one row per calendar month, newest first.
    val months: List<Pair<YearMonth, List<Task>>>
) {
    val days: Int get() = (end.toEpochDay() - first.toEpochDay() + 1).toInt()

    // From start or creation to done. Repeating tasks are left out: an instance is made when the
    // previous one is done, so its time is only the repeat interval.
    val timeToDone: List<Aged> = ages(done)
    val previousTimeToDone: List<Aged>? = previousDone?.let(::ages)

    private fun ages(tasks: List<Task>) = tasks.filter { it.recurrenceType == null }.mapNotNull { t ->
        readyAt(t)?.let { Aged(t, (t.completedAt!! - it).coerceAtLeast(0)) }
    }

    // The window's tasks that had a due date; ms is how late (0 when on time).
    val dueOutcomes: List<Aged> = done.mapNotNull { t -> t.dueDate?.let { Aged(t, (t.completedAt!! - deadline(it)).coerceAtLeast(0)) } }

    // Stepping moves a whole window; next stops at today.
    val previous: LocalDate? get() = if (zoom == Zoom.ALL) null else end.minusDays(zoom.days.toLong())
    val next: LocalDate? get() = if (zoom == Zoom.ALL || end >= today) null else minOf(end.plusDays(zoom.days.toLong()), today)
    val zoomOut: Zoom? get() = Zoom.entries.getOrNull(zoom.ordinal + 1)

    // A 7-day bar opens as a Week, a month bar as the Month ending on its last day; a day doesn't zoom.
    fun zoomIn(bar: ReviewBar): Pair<Zoom, LocalDate>? = when (zoom) {
        Zoom.YEAR -> Zoom.WEEK to bar.last
        Zoom.ALL -> Zoom.MONTH to minOf(bar.last, today)
        else -> null
    }

    fun monthEnd(month: YearMonth): LocalDate = minOf(month.atEndOfMonth(), today)

    // --- Wording, shared so the phone and the web say the same thing.

    val windowLabel: String get() = when {
        zoom == Zoom.ALL -> "Since ${first.format(pattern("MMM yyyy"))}"
        first.year == today.year && end.year == today.year -> "${first.format(pattern("MMM d"))} – ${end.format(pattern("MMM d"))}"
        else -> "${first.format(pattern("MMM d, yyyy"))} – ${end.format(pattern("MMM d, yyyy"))}"
    }

    // A bar's tooltip heading.
    fun barLabel(bar: ReviewBar): String = when (zoom) {
        Zoom.WEEK, Zoom.MONTH -> bar.first.format(pattern("EEE, MMM d"))
        Zoom.YEAR -> "${bar.first.format(pattern("MMM d"))} – ${bar.last.format(pattern("MMM d"))}"
        Zoom.ALL -> bar.first.format(pattern("MMMM yyyy"))
    }

    // Under the bars: weekdays in a Week, every 7th date (counting back from the end) in a Month, and
    // a month's initial under the bar holding its 1st in a Year, or under each month in All.
    fun axisLabel(index: Int): String {
        val bar = bars[index]
        return when (zoom) {
            Zoom.WEEK -> bar.first.format(pattern("EEE")).take(2)
            Zoom.MONTH -> if ((bars.size - 1 - index) % 7 == 0) bar.first.format(pattern("MMM d")) else ""
            Zoom.YEAR -> generateSequence(bar.first) { it.plusDays(1) }.takeWhile { it <= bar.last }.firstOrNull { it.dayOfMonth == 1 }
                ?.format(pattern("MMMMM")).orEmpty()
            Zoom.ALL -> bar.first.format(pattern("MMMMM"))
        }
    }
}

private fun pattern(p: String) = DateTimeFormatter.ofPattern(p, Locale.US)

// modeFolderId: folder mode's folder; only what was done under it counts.
fun review(all: List<Task>, waiting: List<Task>, now: Long, rolloverHour: Int, zoom: Zoom, end: LocalDate? = null, modeFolderId: Long? = null): ReviewPage {
    val byId = all.associateBy { it.id }
    val completed = all.filter { it.type.isDoable && !isChecklistItem(it, byId) && it.isComplete && it.completedAt != null && inMode(it, modeFolderId, byId) }
        .map { it to appDay(it.completedAt!!, rolloverHour) }
    val today = appDay(now, rolloverHour)
    val last = minOf(end ?: today, today)
    val first = if (zoom == Zoom.ALL) completed.minOfOrNull { it.second } ?: today else last.minusDays(zoom.days - 1L)
    fun between(from: LocalDate, to: LocalDate) = completed.filter { it.second in from..to }.sortedBy { it.first.completedAt }.map { it.first }
    val done = between(first, last)
    val byDay = completed.filter { it.second in first..last }.groupBy({ it.second }, { it.first })
    fun bar(from: LocalDate, to: LocalDate) = ReviewBar(from, to, generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.flatMap { byDay[it].orEmpty() }.toList())
    val bars = when (zoom) {
        Zoom.WEEK, Zoom.MONTH -> generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.map { bar(it, it) }.toList()
        Zoom.YEAR -> (51 downTo 0).map { k -> last.minusDays(7L * k).let { bar(it.minusDays(6), it) } }
        Zoom.ALL -> generateSequence(YearMonth.from(first)) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(last) }
            .map { bar(maxOf(it.atDay(1), first), minOf(it.atEndOfMonth(), last)) }.toList()
    }
    val previous = if (zoom == Zoom.ALL) null else between(first.minusDays(zoom.days.toLong()), first.minusDays(1))
    val aged = waiting.mapNotNull { t -> readyAt(t)?.let { Aged(t, (now - it).coerceAtLeast(0)) } }.sortedByDescending { it.ms }
    val months = completed.filter { it.second in first..last }.sortedBy { it.first.completedAt }
        .groupBy({ YearMonth.from(it.second) }, { it.first }).toList().sortedByDescending { it.first }
    return ReviewPage(zoom, first, last, today, bars, done, previous, aged, months)
}
