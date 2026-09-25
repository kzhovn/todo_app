package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.repository.computeActiveTasks
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskOrderTest {
    @Test
    fun `manual positions win, unpositioned newcomers go last, and sequential follows the order`() {
        val parent = Task(id = 1, title = "P", sequential = true)
        val a = Task(id = 10, title = "a", parentId = 1, position = 2)
        val b = Task(id = 11, title = "b", parentId = 1, position = 1)
        val c = Task(id = 12, title = "c", parentId = 1)

        assertEquals(listOf("b", "a", "c"), listOf(a, b, c).sortedWith(TaskOrder).map { it.title })
        val active = computeActiveTasks(listOf(parent, a, b, c), emptyMap(), emptyList(), emptyList(), emptyList(), now = 0L)
        assertEquals(listOf("P", "b"), active.map { it.title })
    }

    @Test
    fun `projects are never active, and stall once every subtask is done`() {
        val project = Task(id = 1, title = "Coat", type = TaskType.PROJECT)
        val done = Task(id = 2, title = "cut", parentId = 1, isComplete = true)
        val open = Task(id = 3, title = "sew", parentId = 1)

        assertEquals(listOf("sew"), computeActiveTasks(listOf(project, open), emptyMap(), emptyList(), emptyList(), emptyList(), 0L).map { it.title })
        assertEquals(emptyList<Task>(), stalledProjects(listOf(project, done, open)))
        assertEquals(listOf(project), stalledProjects(listOf(project, done)))
    }
}
