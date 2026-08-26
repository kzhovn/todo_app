package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.resolveEffective
import org.junit.Assert.assertEquals
import org.junit.Test

// filterDoing and resolveEffective are each well-covered in isolation elsewhere; this proves the
// two actually compose the way the app wires them together in TaskListViewModel/TodoWidget — a
// subtask that inherits a due date from its parent shows up in Doing because of that inherited
// value, not its own (null) dueDate.
class DoingFilterInheritanceTest {
    @Test
    fun `a subtask with no due date of its own appears in Doing via its parent's inherited due date`() {
        val now = 1_700_000_000_000L
        val dueSoon = now + 60 * 60 * 1000L // 1 hour from now, well within the Doing window
        val parent = Task(id = 1, title = "Parent", dueDate = dueSoon)
        val child = Task(id = 2, title = "Child", parentId = 1, dueDate = null)
        val allById = mapOf(1L to parent, 2L to child)

        val result = filterDoing(listOf(child), now) {
            resolveEffective(it, allById, emptyMap()).effectiveDueDate
        }

        assertEquals(listOf("Child"), result.map { it.title })
    }

    @Test
    fun `a subtask with no due date anywhere in its ancestry does not appear in Doing`() {
        val now = 1_700_000_000_000L
        val parent = Task(id = 1, title = "Parent", dueDate = null)
        val child = Task(id = 2, title = "Child", parentId = 1, dueDate = null)
        val allById = mapOf(1L to parent, 2L to child)

        val result = filterDoing(listOf(child), now) {
            resolveEffective(it, allById, emptyMap()).effectiveDueDate
        }

        assertEquals(emptyList<Task>(), result)
    }
}
