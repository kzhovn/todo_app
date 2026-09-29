package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import org.junit.Assert.assertEquals
import org.junit.Test

class NextFocusTasksTest {
    private val project = Task(id = 1, title = "Project", sequential = true)
    private val done = Task(id = 2, title = "Step 1", parentId = 1, isComplete = true)
    private val step2 = Task(id = 3, title = "Step 2", parentId = 1)
    private val starred = Task(id = 4, title = "Starred", isStarred = true)
    private val other = Task(id = 5, title = "Other")
    private val byId = listOf(project, done, step2, starred, other).associateBy { it.id }

    @Test
    fun `Doing only, falling back to Active when Doing is empty`() {
        assertEquals(listOf(starred), nextFocusTasks(listOf(starred, other), listOf(starred), null, byId))
        assertEquals(listOf(starred, other), nextFocusTasks(listOf(starred, other), emptyList(), null, byId))
    }

    @Test
    fun `the unblocked next step of a sequential parent always comes first`() {
        assertEquals(listOf(step2, starred), nextFocusTasks(listOf(step2, starred, other), listOf(starred), done, byId))
        // Not sequential: no special step.
        val loose = byId + (1L to project.copy(sequential = false))
        assertEquals(listOf(starred), nextFocusTasks(listOf(step2, starred, other), listOf(starred), done, loose))
    }

    @Test
    fun `search finds any open task, not just Doing or Active`() {
        val maybe = Task(id = 6, title = "Call the bank?", isMaybe = true)
        val later = Task(id = 7, title = "Call mom", startDate = Long.MAX_VALUE)
        val closed = Task(id = 8, title = "Call Sam", isComplete = true)
        val folder = Task(id = 9, title = "Calls", type = com.kzhovn.todoapp.data.TaskType.FOLDER)
        val list = Task(id = 10, title = "Call list", type = com.kzhovn.todoapp.data.TaskType.CHECKLIST)
        val item = Task(id = 11, title = "Call the plumber", parentId = 10)
        assertEquals(listOf("Call list", "Call mom", "Call the bank?"), focusSearch(listOf(maybe, later, closed, folder, list, item), " call ").map { it.title })
        assertEquals(emptyList<Task>(), focusSearch(listOf(maybe), " "))
    }
}
