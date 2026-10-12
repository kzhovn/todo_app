package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import org.junit.Assert.assertEquals
import org.junit.Test

class DimmedInTreeTest {
    @Test fun `the All tree dims what's held back by anything but a context`() {
        val now = 1_790_000_000_000L
        val free = Task(id = 1, title = "Free")
        val blocked = Task(id = 2, title = "Blocked")
        val later = Task(id = 3, title = "Later", startDate = now + 1)
        val maybe = Task(id = 4, title = "Maybe", isMaybe = true)
        val parent = Task(id = 5, title = "Parent") // waits on its open subtask
        val sub = Task(id = 6, title = "Sub", parentId = 5)
        val project = Task(id = 7, title = "Project", type = TaskType.PROJECT)
        val all = listOf(free, blocked, later, maybe, parent, sub, project)
        val workable = computeActiveIgnoringContexts(all, listOf(TaskDependency(2, 1)), now).map { it.id }.toSet()
        val byId = all.associateBy { it.id }
        assertEquals(listOf("Blocked", "Later", "Maybe", "Parent"), all.filter { dimmedInTree(it, workable, byId, now) }.map { it.title })
    }
}
