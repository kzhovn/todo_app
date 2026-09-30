package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderModeTest {
    private val work = Task(id = 1, title = "Work", type = TaskType.FOLDER)
    private val clients = Task(id = 2, title = "Clients", type = TaskType.FOLDER, parentId = 1)
    private val report = Task(id = 3, title = "Report", parentId = 2)
    private val home = Task(id = 4, title = "Home", type = TaskType.FOLDER)
    private val sink = Task(id = 5, title = "Fix the sink", parentId = 4)
    private val byId = listOf(work, clients, report, home, sink).associateBy { it.id }

    @Test
    fun `a mode takes its folder and everything under it`() {
        assertEquals(listOf(work, clients, report), byId.values.filter { inMode(it, 1, byId) })
        assertEquals(5, byId.values.count { inMode(it, null, byId) })
    }

    @Test
    fun `the mode ends with its folder`() {
        assertEquals(work, modeFolder(1, byId))
        assertNull(modeFolder(3, byId)) // not a folder
        assertNull(modeFolder(99, byId))
    }

    @Test
    fun `due outside the mode lists only what's due today or overdue elsewhere`() {
        val now = 1_000_000_000_000L
        val dueSoon = sink.copy(dueDate = now)
        val later = Task(id = 6, title = "Later", parentId = 4, dueDate = now + 10L * 24 * 60 * 60 * 1000)
        val ids = byId + (5L to dueSoon) + (6L to later)
        assertEquals(listOf(dueSoon), dueOutsideMode(listOf(report, dueSoon, later), 1, ids, now) { it.dueDate })
        assertEquals(emptyList<Task>(), dueOutsideMode(listOf(dueSoon), null, ids, now) { it.dueDate })
    }
}
