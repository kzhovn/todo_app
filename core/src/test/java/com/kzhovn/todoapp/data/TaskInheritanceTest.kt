package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskInheritanceTest {
    @Test
    fun `a task with its own startDate does not inherit from its parent`() {
        val parent = Task(id = 1, title = "Parent", startDate = 100L)
        val child = Task(id = 2, title = "Child", parentId = 1, startDate = 200L)
        val allById = mapOf(1L to parent, 2L to child)
        val effective = resolveEffective(child, allById, emptyMap())
        assertEquals(200L, effective.effectiveStartDate)
    }

    @Test
    fun `a task with no startDate inherits its parent's`() {
        val parent = Task(id = 1, title = "Parent", startDate = 100L)
        val child = Task(id = 2, title = "Child", parentId = 1, startDate = null)
        val allById = mapOf(1L to parent, 2L to child)
        val effective = resolveEffective(child, allById, emptyMap())
        assertEquals(100L, effective.effectiveStartDate)
    }

    @Test
    fun `inheritance walks up through a grandparent when the parent also has nothing set`() {
        val grandparent = Task(id = 1, title = "Grandparent", dueDate = 500L)
        val parent = Task(id = 2, title = "Parent", parentId = 1, dueDate = null)
        val child = Task(id = 3, title = "Child", parentId = 2, dueDate = null)
        val allById = mapOf(1L to grandparent, 2L to parent, 3L to child)
        assertEquals(500L, DueDates(allById.values, emptyList()).at(child))
    }

    @Test
    fun `no ancestor has a value results in a null effective value`() {
        val parent = Task(id = 1, title = "Parent", dueDate = null)
        val child = Task(id = 2, title = "Child", parentId = 1, dueDate = null)
        val allById = mapOf(1L to parent, 2L to child)
        assertNull(DueDates(allById.values, emptyList()).at(child))
    }

    // Termination is the assertion here: without walkUp's `seen` guard this input loops forever.
    @Test
    fun `a cyclic parent chain with no values anywhere terminates instead of hanging`() {
        val a = Task(id = 1, title = "A", parentId = 2, startDate = null)
        val b = Task(id = 2, title = "B", parentId = 1, startDate = null)
        val allById = mapOf(1L to a, 2L to b)
        val effective = resolveEffective(a, allById, emptyMap())
        assertNull(effective.effectiveStartDate)
    }

    @Test
    fun `a task with its own context assignments does not inherit the parent's`() {
        val parent = Task(id = 1, title = "Parent")
        val child = Task(id = 2, title = "Child", parentId = 1)
        val allById = mapOf(1L to parent, 2L to child)
        val contextsByTaskId = mapOf(1L to setOf(10L), 2L to setOf(20L))
        val effective = resolveEffective(child, allById, contextsByTaskId)
        assertEquals(setOf(20L), effective.effectiveContextIds)
    }

    @Test
    fun `a task with no context assignments inherits the parent's context set`() {
        val parent = Task(id = 1, title = "Parent")
        val child = Task(id = 2, title = "Child", parentId = 1)
        val allById = mapOf(1L to parent, 2L to child)
        val contextsByTaskId = mapOf(1L to setOf(10L, 11L))
        val effective = resolveEffective(child, allById, contextsByTaskId)
        assertEquals(setOf(10L, 11L), effective.effectiveContextIds)
    }

    @Test
    fun `folders participate as ancestors the same as tasks`() {
        val folder = Task(id = 1, type = TaskType.FOLDER, title = "Folder", startDate = 300L)
        val child = Task(id = 2, title = "Child", parentId = 1, startDate = null)
        val allById = mapOf(1L to folder, 2L to child)
        val effective = resolveEffective(child, allById, emptyMap())
        assertEquals(300L, effective.effectiveStartDate)
    }

    @Test
    fun `a top-level task with no parent has no inherited values`() {
        val task = Task(id = 1, title = "Top level")
        val effective = resolveEffective(task, mapOf(1L to task), emptyMap())
        assertNull(effective.effectiveStartDate)
        assertNull(DueDates(listOf(task), emptyList()).at(task))
        assertEquals(emptySet<Long>(), effective.effectiveContextIds)
    }

    @Test
    fun `a prerequisite takes a sooner due date from an open task waiting on it, and its subtasks with it`() {
        val trip = Task(id = 1, title = "Trip", dueDate = 500L)
        val canisters = Task(id = 2, title = "Buy canisters", dueDate = 900L)
        val step = Task(id = 3, title = "Check size", parentId = 2)
        val laterDue = Task(id = 4, title = "Later", dueDate = 2000L)
        val done = Task(id = 5, title = "Done", dueDate = 100L, isComplete = true)
        val edges = listOf(TaskDependency(1, 2), TaskDependency(4, 2), TaskDependency(5, 2))
        val dues = DueDates(listOf(trip, canisters, step, laterDue, done), edges)
        assertEquals(ShownDue(500L, trip), dues.of(canisters))
        assertEquals(ShownDue(500L, trip), dues.of(step))
        assertEquals(ShownDue(500L), dues.of(trip))
        // Its own date wins when it's sooner.
        assertEquals(ShownDue(400L), DueDates(listOf(trip, canisters.copy(dueDate = 400L)), edges).of(canisters.copy(dueDate = 400L)))
    }
}
