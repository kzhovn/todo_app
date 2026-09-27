package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.data.splitItems
import org.junit.Assert.assertEquals
import org.junit.Test

class ChecklistTest {
    private val now = 1_790_000_000_000L
    private val groceries = Task(id = 1, title = "Groceries", type = TaskType.CHECKLIST)
    private val milk = Task(id = 2, title = "milk", parentId = 1)
    private val eggs = Task(id = 3, title = "eggs", parentId = 1, isComplete = true, completedAt = now - 1000)
    private val call = Task(id = 4, title = "call mom")
    private val all = listOf(groceries, milk, eggs, call)

    @Test
    fun `a checklist is active like a task, its items never are`() {
        val active = computeActiveTasks(all, emptyMap(), emptyList(), emptyList(), emptyList(), now)
        assertEquals(listOf("Groceries", "call mom"), active.map { it.title })
    }

    @Test
    fun `items don't count in Review, a completed checklist does`() {
        val done = all.map { if (it.id == 1L) it.copy(isComplete = true, completedAt = now - 500) else it }
        assertEquals(listOf("Groceries"), completionsByDay(done, now, 4, 1).single().tasks.map { it.title })
    }

    @Test
    fun `items list open ones first, and commas split`() {
        assertEquals(listOf("milk", "eggs"), checklistItems(1, all).map { it.title })
        assertEquals(listOf("milk", "eggs", "bread"), splitItems(" milk, eggs,,bread "))
    }

    @Test
    fun `a checklist can be waited on, like a task`() {
        val packed = groceries.copy(id = 20, title = "Packing")
        val leave = Task(id = 21, title = "Leave")
        val active = computeActiveTasks(listOf(packed, leave), emptyMap(), emptyList(), emptyList(), listOf(com.kzhovn.todoapp.data.TaskDependency(21, 20)), now)
        assertEquals(listOf("Packing"), active.map { it.title })
    }
}
