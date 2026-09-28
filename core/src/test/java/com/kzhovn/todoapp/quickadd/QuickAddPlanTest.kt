package com.kzhovn.todoapp.quickadd

import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuickAddPlanTest {
    private val work = Task(id = 1, title = "Work", type = TaskType.FOLDER)
    private val groceries = Task(id = 2, title = "Groceries", type = TaskType.CHECKLIST)
    private val move = Task(id = 3, title = "House move", type = TaskType.PROJECT)
    private val workList = Task(id = 4, title = "work", type = TaskType.CHECKLIST)
    private val tasks = listOf(work, groceries, move, workList)
    private val contexts = listOf(TaskContext(id = 7, name = "Home", type = ContextType.PLACE), TaskContext(id = 8, name = "At desk", type = ContextType.PLACE))
    private fun plan(text: String) = planQuickAdd(text, tasks, contexts, now = 0, rolloverHour = 4)

    @Test
    fun `a prefix names a folder, project or checklist, and otherwise stays in the title`() {
        assertEquals(work.id, plan("work: fix bug").task!!.parentId) // the folder beats the checklist "work"
        assertEquals("fix bug", plan("WORK: fix bug").task!!.title)
        assertEquals(move.id, plan("house move: book van").task!!.parentId)
        val items = plan("groceries: milk, eggs -d fri")
        assertNull(items.task)
        assertEquals(groceries.id, items.intoChecklist)
        assertEquals(listOf("milk", "eggs -d fri"), items.items) // items are plain titles
        val re = plan("Re: invoice").task!!
        assertEquals("Re: invoice" to null, re.title to re.parentId)
    }

    @Test
    fun `d prefix is today only, and contexts match by name`() {
        val daily = plan("d: shower @home").task!!
        assertEquals("shower", daily.title)
        assertNotNull(daily.expiresAt)
        assertEquals(setOf(7L), plan("d: shower @home").contextIds)
        val both = plan("email bob @HOME @atdesk @nowhere a@b.com")
        assertEquals(setOf(7L, 8L), both.contextIds)
        assertEquals("email bob @nowhere a@b.com", both.task!!.title)
    }

    @Test
    fun `contexts come from the title, not the note`() {
        val add = plan("fix sink @home // ask @home about parts")
        assertEquals(setOf(7L), add.contextIds)
        assertEquals("fix sink" to "ask @home about parts", add.task!!.title to add.task!!.notes)
    }
}
