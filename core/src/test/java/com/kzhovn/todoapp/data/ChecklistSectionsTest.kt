package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.quickadd.planQuickAdd
import com.kzhovn.todoapp.repository.computeActiveTasks
import com.kzhovn.todoapp.repository.planCheckAll
import com.kzhovn.todoapp.repository.planCompleteChecklist
import com.kzhovn.todoapp.repository.planQuickAddWrite
import com.kzhovn.todoapp.repository.planUncheckAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// A checklist's sections (Clothes, Winter): items one level down, still the checklist's items everywhere.
class ChecklistSectionsTest {
    private val now = 1_790_000_000_000L
    private val list = Task(id = 1, title = "Packing", type = TaskType.CHECKLIST)
    private val passport = Task(id = 2, title = "Passport", parentId = 1)
    private val clothes = Task(id = 3, title = "Clothes", type = TaskType.SECTION, parentId = 1)
    private val socks = Task(id = 4, title = "Socks", parentId = 3)
    private val sandals = Task(id = 5, title = "Sandals", parentId = 3, isComplete = true)
    private val all = listOf(list, passport, clothes, socks, sandals)
    private val byId = all.associateBy { it.id }

    @Test fun `a section's items are the checklist's items, counted with it, and kept out of Active`() {
        assertEquals(list, checklistOf(socks, byId))
        assertTrue(isChecklistItem(socks, byId))
        assertEquals(listOf(null to listOf(passport), clothes to listOf(socks, sandals)), checklistGroups(1, all))
        val counts = subtaskCounts(all)
        assertEquals(1 to 3, counts[1L]) // the section itself isn't an item
        assertEquals(1 to 2, counts[3L])
        assertEquals(emptyList<Task>(), computeActiveTasks(all, emptyMap(), emptyList(), emptyList(), emptyList(), now).filter { it.id != 1L })
    }

    @Test fun `check all and uncheck all work on one section`() {
        val checked = planCheckAll(all, emptyMap(), 3, now).applyTo(all)
        assertTrue(checked.single { it.id == 4L }.isComplete)
        assertEquals(false, checked.single { it.id == 2L }.isComplete) // a loose item isn't in the section
        assertEquals(false, planUncheckAll(all, 3).applyTo(all).single { it.id == 5L }.isComplete)
    }

    @Test fun `moving unchecked items to a new list takes them loose`() {
        val after = planCompleteChecklist(all, emptyMap(), 1, moveUncheckedToNewList = true, now).applyTo(all)
        val copy = after.single { it.title == "Packing" && !it.isComplete }
        assertEquals(setOf(2L, 4L), after.filter { it.parentId == copy.id }.map { it.id }.toSet())
    }

    @Test fun `quick add makes sections from semicolon groups`() {
        val plan = { text: String -> planQuickAdd(text, emptyList(), emptyList(), now, 4) }
        val add = plan("packing [passport; clothes: socks, shirt; winter: coat]")
        assertEquals(listOf("passport"), add.items)
        assertEquals(listOf("clothes" to listOf("socks", "shirt"), "winter" to listOf("coat")), add.sections)
        val made = planQuickAddWrite(emptyList(), add, null, now, plan).first.applyTo(emptyList())
        val checklist = made.single { it.type == TaskType.CHECKLIST }
        assertEquals(listOf("passport", "socks", "shirt", "coat"), checklistItems(checklist.id, made).map { it.title })
        assertEquals(listOf("clothes", "winter"), checklistGroups(checklist.id, made).mapNotNull { it.first?.title })
    }
}
