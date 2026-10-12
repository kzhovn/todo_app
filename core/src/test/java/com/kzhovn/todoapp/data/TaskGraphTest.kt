package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGraphTest {
    @Test
    fun `picking yourself as your own parent is a cycle`() {
        val byId = mapOf(1L to Task(id = 1, title = "A"))
        assertTrue(wouldCreateCycle(candidateId = 1, editingTaskId = 1, allById = byId))
    }

    @Test
    fun `picking your own descendant as your parent is a cycle`() {
        val a = Task(id = 1, title = "A")
        val b = Task(id = 2, title = "B", parentId = 1)
        val byId = mapOf(1L to a, 2L to b)
        assertTrue(wouldCreateCycle(candidateId = 2, editingTaskId = 1, allById = byId))
    }

    @Test
    fun `unrelated task is not a cycle`() {
        val a = Task(id = 1, title = "A")
        val b = Task(id = 2, title = "B")
        val byId = mapOf(1L to a, 2L to b)
        assertFalse(wouldCreateCycle(candidateId = 2, editingTaskId = 1, allById = byId))
    }

    @Test(timeout = 2000)
    fun `a pre-existing cycle that never reaches editingTaskId returns false instead of hanging`() {
        val a = Task(id = 1, title = "A", parentId = 2)
        val b = Task(id = 2, title = "B", parentId = 1)
        val allById = mapOf(1L to a, 2L to b)

        // editingTaskId = 99 is never reached by walking 1 -> 2 -> 1 -> 2 -> ... The 2-second
        // JUnit timeout is the real guarantee here: without the visited-set guard this loops
        // forever and the timeout fails the test, rather than the run hanging indefinitely.
        assertFalse(wouldCreateCycle(candidateId = 1, editingTaskId = 99, allById = allById))
    }

    @Test
    fun `the pickers list what's beside a task first, then its folder, then recent, then the rest`() {
        val now = 1_790_000_000_000L
        val old = 1_700_000_000_000L shl 11 // ids carry their creation time (newId)
        val personal = Task(id = old + 1, title = "Personal", type = TaskType.FOLDER)
        val trip = Task(id = old + 2, title = "Trip", type = TaskType.PROJECT, parentId = personal.id)
        val canisters = Task(id = old + 3, title = "Buy canisters", parentId = trip.id)
        val campsite = Task(id = old + 4, title = "Pick campsite", parentId = trip.id)
        val step = Task(id = old + 5, title = "Check size", parentId = canisters.id)
        val pants = Task(id = old + 6, title = "Hem pants", parentId = personal.id)
        val fresh = Task(id = (now - DAY_MS) shl 11, title = "New thing")
        val stale = Task(id = old + 7, title = "Old thing")
        val all = listOf(personal, trip, canisters, campsite, step, pants, fresh, stale)
        val sections = nearbyFirst(canisters, listOf(stale, fresh, pants, step, campsite), all, now)
        assertEquals(
            listOf(Labels.BESIDE_THIS to setOf(step, campsite), "In Personal" to setOf(pants), Labels.ADDED_RECENTLY to setOf(fresh), Labels.EVERYTHING_ELSE to setOf(stale)),
            sections.map { (label, tasks) -> label to tasks.toSet() }
        )
    }
}
