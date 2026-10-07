package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.toTaskFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskChangesTest {
    private val now = 1_790_000_000_000L
    private val repeat = RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 3).toTaskFields()
    private val chores = Task(id = 1, title = "Chores", type = TaskType.PROJECT)
    private val bins = Task(id = 2, title = "Bins", parentId = 1, startDate = now - 1000, recurrenceType = repeat.first, recurrenceRule = repeat.second)
    private val sweep = Task(id = 3, title = "Sweep", parentId = 1)
    private val all = listOf(chores, bins, sweep)

    @Test fun completingWithDescendantsCompletesEachAndRepeatsTheRepeatingOneWithItsContexts() {
        val changes = planCompleteWithDescendants(all, mapOf(2L to setOf(9L)), 1, now)
        val after = changes.applyTo(all)
        assertTrue(after.filter { it.id in setOf(1L, 2L, 3L) }.all { it.isComplete })
        val next = changes.creates.single()
        assertEquals("Bins" to setOf(9L), next.first.title to next.second)
        assertEquals(false, next.first.isComplete)
    }

    @Test fun uncompletingTakesBackAnUntouchedNextInstance() {
        val done = planComplete(all, emptyMap(), 2, now)
        val after = done.applyTo(all)
        val back = planUncomplete(after, 2)
        assertEquals(setOf(done.creates.single().first.id), back.deletes)
        assertEquals(false, back.applyTo(after).single { it.id == 2L }.isComplete)
    }

    @Test fun completingAChecklistCanMoveItsUncheckedItemsToACopy() {
        val list = Task(id = 10, title = "Groceries", type = TaskType.CHECKLIST)
        val milk = Task(id = 11, title = "Milk", parentId = 10)
        val eggs = Task(id = 12, title = "Eggs", parentId = 10, isComplete = true)
        val changes = planCompleteChecklist(listOf(list, milk, eggs), emptyMap(), 10, moveUncheckedToNewList = true, now)
        val after = changes.applyTo(listOf(list, milk, eggs))
        val copy = after.single { it.title == "Groceries" && !it.isComplete }
        assertEquals(copy.id, after.single { it.id == 11L }.parentId)
        assertTrue(after.single { it.id == 10L }.isComplete)
    }

    @Test fun anExpiredTodayOnlyTaskGoesWithItsSubtasks() {
        val today = Task(id = 20, title = "Today", expiresAt = now - 1)
        val sub = Task(id = 21, title = "Step", parentId = 20)
        assertEquals(setOf(20L, 21L), planPurgeExpired(listOf(today, sub, sweep), now).deletes)
    }

    @Test fun uncheckingAllTakesBackARepeatingItemsNextOne() {
        val list = Task(id = 10, title = "Weekly", type = TaskType.CHECKLIST)
        val item = bins.copy(id = 11, parentId = 10)
        val done = planComplete(listOf(list, item), emptyMap(), 11, now)
        val after = done.applyTo(listOf(list, item))
        val back = planUncheckAll(after, 10)
        assertEquals(setOf(done.creates.single().first.id), back.deletes)
    }

    @Test fun aChecklistItemTakesQuickAddsSyntaxButStaysAnItem() {
        val plan = { text: String -> com.kzhovn.todoapp.quickadd.planQuickAdd(text, emptyList(), emptyList(), now, 4) }
        val (milk, _) = checklistItem("milk due tomorrow !", 10, plan)!!
        assertEquals("milk" to 10L, milk.title to milk.parentId)
        assertTrue(milk.dueDate != null && milk.isHighPriority)
        val (packing, _) = checklistItem("packing [passport, charger] -p", 10, plan)!!
        assertEquals(TaskType.TASK, packing.type)
        assertEquals(null, packing.pinnedAt)
        assertEquals(TaskType.TASK, checklistItem("wait for the bus", 10, plan)!!.first.type)
    }
}
