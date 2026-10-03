package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtaskBlockingTest {
    private val now = 1_790_000_000_000L
    private fun active(vararg tasks: Task) = computeActiveTasks(tasks.toList(), emptyMap(), emptyList(), emptyList(), emptyList(), now).map { it.title }

    @Test
    fun `a task waits on its open subtasks, like a dependency`() {
        val parent = Task(id = 1, title = "Clean the house")
        assertEquals(listOf("Vacuum"), active(parent, Task(id = 2, title = "Vacuum", parentId = 1)))
        assertEquals(listOf("Clean the house"), active(parent, Task(id = 2, title = "Vacuum", parentId = 1, isComplete = true)))
    }

    @Test
    fun `a subtask not yet started still blocks its parent`() {
        val parent = Task(id = 1, title = "Clean the house")
        assertEquals(emptyList<String>(), active(parent, Task(id = 2, title = "Vacuum", parentId = 1, startDate = now + 86_400_000)))
    }

    @Test
    fun `a task set to stay active isn't blocked by its subtasks`() {
        val parent = Task(id = 1, title = "Clean the house", activeWithSubtasks = true)
        assertEquals(listOf("Clean the house", "Vacuum"), active(parent, Task(id = 2, title = "Vacuum", parentId = 1)))
    }

    @Test
    fun `a checklist stays active with open items`() {
        assertEquals(listOf("Groceries"), active(Task(id = 1, title = "Groceries", type = TaskType.CHECKLIST), Task(id = 2, title = "milk", parentId = 1)))
    }

    @Test
    fun `a project waiting on a prerequisite holds back its steps, and a task can wait on a project`() {
        val passport = Task(id = 1, title = "Renew passport")
        val trip = Task(id = 2, title = "Trip", type = TaskType.PROJECT)
        val flights = Task(id = 3, title = "Book flights", parentId = 2)
        val packing = Task(id = 4, title = "Pack")
        fun active(deps: List<com.kzhovn.todoapp.data.TaskDependency>, vararg tasks: Task) =
            computeActiveTasks(tasks.toList(), emptyMap(), emptyList(), emptyList(), deps, now).map { it.title }
        val tripWaits = com.kzhovn.todoapp.data.TaskDependency(taskId = 2, dependsOnTaskId = 1)
        val packWaits = com.kzhovn.todoapp.data.TaskDependency(taskId = 4, dependsOnTaskId = 2)
        assertEquals(listOf("Renew passport"), active(listOf(tripWaits, packWaits), passport, trip, flights, packing))
        assertEquals(listOf("Book flights"), active(listOf(tripWaits, packWaits), passport.copy(isComplete = true), trip, flights, packing))
        // The project done: what waited on it is free.
        assertEquals(listOf("Pack", "Renew passport"), active(listOf(packWaits), passport, trip.copy(isComplete = true), flights.copy(isComplete = true), packing).sorted())
        // A project waiting on its own step doesn't block that step.
        val ownStep = com.kzhovn.todoapp.data.TaskDependency(taskId = 2, dependsOnTaskId = 3)
        assertEquals(listOf("Book flights"), active(listOf(ownStep), trip, flights))
    }
}
