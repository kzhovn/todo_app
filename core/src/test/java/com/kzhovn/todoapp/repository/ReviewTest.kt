package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.RecurrenceType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Calendar

class ReviewTest {
    private fun at(day: Int, hour: Int, month: Int = Calendar.SEPTEMBER) = Calendar.getInstance().apply {
        set(2026, month, day, hour, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val now = at(24, 12)
    private fun sep(day: Int) = LocalDate.of(2026, 9, day)
    // A task created at `created`, as newId makes them.
    private fun made(created: Long, title: String) = Task(id = created shl 11, title = title)

    @Test
    fun `completions go by rollover day, and the window ends today`() {
        val tasks = listOf(
            Task(id = 1, title = "late night", isComplete = true, completedAt = at(24, 1)), // before 4am: counts as the 23rd
            Task(id = 2, title = "morning", isComplete = true, completedAt = at(24, 9)),
            Task(id = 3, title = "open"),
            Task(id = 4, title = "folder", type = TaskType.FOLDER, isComplete = true, completedAt = at(24, 9)),
            Task(id = 5, title = "old", isComplete = true, completedAt = at(10, 9))
        )
        val week = review(tasks, emptyList(), now, rolloverHour = 4, zoom = Zoom.WEEK)
        assertEquals(sep(18), week.first)
        assertEquals(sep(24), week.end)
        assertEquals(listOf("late night", "morning"), week.done.map { it.title })
        assertEquals(listOf("late night"), week.bars.single { it.first == sep(23) }.tasks.map { it.title })
        assertEquals(listOf("old"), review(tasks, emptyList(), now, 4, Zoom.WEEK, end = sep(12)).done.map { it.title })
    }

    @Test
    fun `stepping moves a whole window, stops at today, and zooming keeps the end`() {
        val month = review(emptyList(), emptyList(), now, 4, Zoom.MONTH, end = sep(10))
        assertEquals(LocalDate.of(2026, 8, 12), month.first)
        assertEquals(LocalDate.of(2026, 8, 11), month.previous)
        assertEquals(sep(24), month.next) // not Oct 10
        assertNull(review(emptyList(), emptyList(), now, 4, Zoom.MONTH).next)
        assertEquals(Zoom.YEAR, month.zoomOut)
        assertNull(month.zoomIn(month.bars.first()))

        val year = review(emptyList(), emptyList(), now, 4, Zoom.YEAR)
        assertEquals(52, year.bars.size)
        assertEquals(sep(18) to sep(24), year.bars.last().let { it.first to it.last })
        assertEquals(Zoom.WEEK to sep(24), year.zoomIn(year.bars.last()))
    }

    @Test
    fun `All has a bar per month from the first completion, and a month opens as a Month`() {
        val tasks = listOf(Task(id = 1, title = "july", isComplete = true, completedAt = at(20, 9, Calendar.JULY)))
        val all = review(tasks, emptyList(), now, 4, Zoom.ALL)
        assertEquals(listOf(7, 8, 9), all.bars.map { it.first.monthValue })
        assertEquals(Zoom.MONTH to LocalDate.of(2026, 7, 31), all.zoomIn(all.bars.first()))
        assertEquals(Zoom.MONTH to sep(24), all.zoomIn(all.bars.last())) // not past today
        assertEquals(listOf("july"), all.months.single().second.map { it.title })
    }

    @Test
    fun `time to done counts from start or creation, and leaves out repeats and unknowns`() {
        val day = 24L * 60 * 60 * 1000
        val tasks = listOf(
            made(at(20, 9), "created").copy(isComplete = true, completedAt = at(23, 9)),
            made(at(1, 9), "started later").copy(startDate = at(22, 9), isComplete = true, completedAt = at(23, 9)),
            Task(id = 7, title = "pre-sync, started", startDate = at(21, 9), isComplete = true, completedAt = at(23, 9)),
            Task(id = 8, title = "pre-sync", isComplete = true, completedAt = at(23, 9)),
            made(at(1, 9), "repeat").copy(recurrenceType = RecurrenceType.RRULE, recurrenceRule = "FREQ=DAILY", isComplete = true, completedAt = at(23, 9))
        )
        val page = review(tasks, emptyList(), now, 4, Zoom.WEEK)
        assertEquals(mapOf("created" to 3 * day, "started later" to day, "pre-sync, started" to 2 * day), page.timeToDone.associate { it.task.title to it.ms })
        assertEquals(2 * day, medianMs(page.timeToDone))
        assertEquals(listOf("Same day" to 0, "1–2 days" to 2, "3–6 days" to 1), bucketCounts(page.timeToDone, DONE_BUCKETS).take(3))
    }

    @Test
    fun `a date-only due date is kept by doing it that day`() {
        val due = at(22, 0)
        val tasks = listOf(
            Task(id = 1, title = "on time", dueDate = due, isComplete = true, completedAt = at(22, 23)),
            Task(id = 2, title = "late", dueDate = due, isComplete = true, completedAt = at(23, 12))
        )
        val outcomes = review(tasks, emptyList(), now, 4, Zoom.WEEK).dueOutcomes.associate { it.task.title to it.ms }
        assertEquals(0L, outcomes["on time"])
        assertEquals(12L * 60 * 60 * 1000, outcomes["late"])
    }

    @Test
    fun `waiting tasks are aged the same way, longest first`() {
        val waiting = listOf(made(at(20, 12), "newer"), made(at(1, 12), "older"), Task(id = 9, title = "unknown"))
        assertEquals(listOf("older", "newer"), review(emptyList(), waiting, now, 4, Zoom.WEEK).waiting.map { it.task.title })
    }

    @Test
    fun `ages read short`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(listOf("same day", "3d", "2w", "4mo"), listOf(day / 2, 3 * day, 15 * day, 125 * day).map { shortAge(it) })
    }
}
