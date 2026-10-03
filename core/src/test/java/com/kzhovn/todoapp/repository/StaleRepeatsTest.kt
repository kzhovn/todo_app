package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.RecurrenceType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StaleRepeatsTest {
    private val day = 24L * 60 * 60 * 1000
    private val start = 1_790_000_000_000L
    private val plants = Task(id = 1, title = "Water plants", recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "4", startDate = start)

    @Test
    fun `a repeat is promoted to Doing half an interval past its start, stalest first`() {
        assertEquals(start + 2 * day, RecurrenceEngine.staleAt(plants))
        assertEquals(emptyList<Task>(), filterDoing(listOf(plants), start + day))
        assertEquals(listOf(plants), filterDoing(listOf(plants), start + 2 * day))
        val sheets = Task(id = 2, title = "Wash sheets", recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "2w", startDate = start - 10 * day)
        val starred = Task(id = 3, title = "Starred", isStarred = true)
        // Sheets went stale (day 7 of 14) three days before the plants did: they lead, then plants, then the rest.
        assertEquals(listOf("Wash sheets", "Water plants", "Starred"), filterDoing(listOf(starred, plants, sheets), start + 3 * day).map { it.title })
    }

    @Test
    fun `a repeat with a due date goes by it, and a calendar repeat's interval comes from its rule`() {
        assertNull(RecurrenceEngine.staleAt(plants.copy(dueDate = start + day)))
        val weekly = plants.copy(recurrenceType = RecurrenceType.RRULE, recurrenceRule = "FREQ=WEEKLY")
        assertEquals(start + 7 * day / 2, RecurrenceEngine.staleAt(weekly))
    }

    @Test
    fun `the next time drops the star, and skipping moves to the next time without completing`() {
        assertFalse(RecurrenceEngine.nextInstance(plants.copy(isStarred = true), start)!!.isStarred)
        val skipped = RecurrenceEngine.skip(plants.copy(isStarred = true), start + day)!!
        assertEquals(start + 5 * day to false, skipped.startDate to skipped.isComplete)
        assertEquals(plants.id, skipped.id)
        assertFalse(skipped.isStarred)
        // Skipped before its start: it moves a whole interval on from that start.
        assertEquals(start + 4 * day, RecurrenceEngine.skip(plants, start - day)!!.startDate)
    }
}
