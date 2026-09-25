package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class DoingOrderTest {
    private fun at(day: Int, hour: Int = 0) = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, day, hour, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun `overdue and due-today tasks lead, soonest first, and the rest keep their order`() {
        val now = at(25, 10)
        val tasks = listOf(
            Task(id = 1, title = "starred, no date", isStarred = true),
            Task(id = 2, title = "due today", isStarred = true, dueDate = at(25)),
            Task(id = 3, title = "due tomorrow", isStarred = true, dueDate = at(26)),
            Task(id = 4, title = "overdue", isStarred = true, dueDate = at(23)),
            Task(id = 5, title = "starred too", isStarred = true)
        )
        assertEquals(listOf("overdue", "due today", "starred, no date", "due tomorrow", "starred too"), filterDoing(tasks, now).map { it.title })
    }
}
