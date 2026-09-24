package com.kzhovn.todoapp.sync

import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolTest {
    private val task = Task(id = 7, title = "Taxes", dueDate = 500L)
    private val base = SyncRow(TASKS, 7, taskFields(task, setOf(2, 1), emptySet()), clocks = mapOf("title" to 100, "isStarred" to 100))

    @Test
    fun `concurrent edits to different fields both survive`() {
        val phone = diff(TASKS, 7, base, taskFields(task.copy(title = "File taxes"), setOf(1, 2), emptySet()), now = 200)!!
        val bot = diff(TASKS, 7, base, taskFields(task.copy(isStarred = true), setOf(1, 2), emptySet()), now = 300)!!

        val merged = merge(merge(base, bot), phone)

        assertEquals(task.copy(title = "File taxes", isStarred = true), merged.toTask())
    }

    @Test
    fun `later write to the same field wins regardless of arrival order, ties keep current`() {
        val older = diff(TASKS, 7, base, taskFields(task.copy(title = "Old"), setOf(1, 2), emptySet()), now = 200)!!
        val newer = diff(TASKS, 7, base, taskFields(task.copy(title = "New"), setOf(1, 2), emptySet()), now = 300)!!

        assertEquals("New", merge(merge(base, newer), older).toTask().title)
        assertEquals("New", merge(merge(base, newer), older.copy(clocks = mapOf("title" to 300))).toTask().title)
    }

    @Test
    fun `diff sends only changed fields and beats base even with a clock running behind`() {
        val change = diff(TASKS, 7, base, taskFields(task.copy(title = "X"), setOf(1, 2), emptySet()), now = 50)!!

        assertEquals(setOf("title"), change.fields.keys)
        assertEquals(101L, change.clocks["title"])
        assertNull(diff(TASKS, 7, base, taskFields(task, listOf(1, 2), emptyList()), now = 50))
    }

    @Test
    fun `rows round-trip including join data and tombstones`() {
        assertEquals(task, base.toTask())
        assertEquals(setOf(1L, 2L), base.contextIds())
        val deleted = merge(base, SyncRow(TASKS, 7, kotlinx.serialization.json.JsonObject(mapOf(DELETED_AT to JsonPrimitive(900L))), mapOf(DELETED_AT to 900L)))
        assertTrue(deleted.isDeleted)
        assertEquals(900L, deleted.deletedAt)

        val ctx = TaskContext(id = 3, name = "Work", type = ContextType.TIME)
        val row = SyncRow(CONTEXTS, 3, contextFields(ctx, listOf(ContextTimeWindow(id = 99, contextId = 3, windowStartMinute = 540, windowEndMinute = 1020))))
        assertEquals(ctx, row.toContext())
        assertEquals(listOf(ContextTimeWindow(contextId = 3, windowStartMinute = 540, windowEndMinute = 1020)), row.timeWindows())
    }
}
