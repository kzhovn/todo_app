package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.repository.computeActiveTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DayRolloverTest {
    private fun at(day: Int, hour: Int, minute: Int = 0) = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, day, hour, minute, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `rollover is the next 4am, so a 1am task lives until that same morning`() {
        assertEquals(at(25, 4), nextRollover(at(24, 18)))
        assertEquals(at(25, 4), nextRollover(at(25, 1)))
        assertEquals(at(26, 4), nextRollover(at(25, 4)))
        assertEquals(at(25, 6), nextRollover(at(24, 23), rolloverHour = 6))
    }

    @Test
    fun `expired tasks drop out of active`() {
        val task = Task(id = 1, title = "shower", expiresAt = at(25, 4))
        assertEquals(listOf(task), computeActiveTasks(listOf(task), emptyMap(), emptyList(), emptyList(), emptyList(), at(25, 3, 59)))
        assertTrue(computeActiveTasks(listOf(task), emptyMap(), emptyList(), emptyList(), emptyList(), at(25, 4)).isEmpty())
    }
}
