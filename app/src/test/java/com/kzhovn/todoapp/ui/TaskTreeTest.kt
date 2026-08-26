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
}
