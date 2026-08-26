package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.Task
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTreeTest {
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
}
