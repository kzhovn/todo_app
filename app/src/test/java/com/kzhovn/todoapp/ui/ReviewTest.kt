package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ReviewTest {
    private fun at(day: Int, hour: Int) = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, day, hour, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `groups completions by rollover day, newest first, with empty days kept`() {
        val tasks = listOf(
            Task(id = 1, title = "late night", isComplete = true, completedAt = at(24, 1)), // before 4am: counts as the 23rd
            Task(id = 2, title = "morning", isComplete = true, completedAt = at(24, 9)),
            Task(id = 3, title = "open", completedAt = null),
            Task(id = 4, title = "folder", type = TaskType.FOLDER, isComplete = true, completedAt = at(24, 9))
        )

        val days = completionsByDay(tasks, now = at(24, 12), rolloverHour = 4, days = 3)

        assertEquals(listOf(listOf("morning"), listOf("late night"), emptyList()), days.map { d -> d.tasks.map { it.title } })
    }
}
