package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.checklistItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatesTest {
    private val now = 1_790_000_000_000L
    private val personal = Task(id = 1, title = "Personal", type = TaskType.FOLDER)
    private val trip = Task(id = 2, title = "Trip", type = TaskType.PROJECT, parentId = 1)
    private val packing = Task(id = 3, title = "Packing", type = TaskType.CHECKLIST, parentId = 2, isStarred = true, dueDate = now)
    private val clothes = Task(id = 4, title = "Clothes", type = TaskType.SECTION, parentId = 3)
    private val socks = Task(id = 5, title = "Socks", parentId = 4, isComplete = true)
    private val passport = Task(id = 6, title = "Passport", parentId = 3)
    private val all = listOf(personal, trip, packing, clothes, socks, passport)

    private fun saved(): List<Task> {
        val (change, _) = planSaveAsTemplate(all, mapOf(5L to setOf(9L)), listOf(TaskDependency(6, 5)), 3)!!
        return change.intoTemplates(emptySet()).applyTo(all)
    }

    @Test fun `saving makes a template copy in the task's folder, fresh, the original as it was`() {
        val after = saved()
        val template = templateRoots(after).single()
        assertEquals(1L, template.parentId) // the folder, not the project it sat in
        assertTrue(template.isTemplate && !template.isStarred && template.dueDate == null)
        val items = checklistItems(template.id, after)
        assertEquals(listOf("Passport", "Socks"), items.map { it.title })
        assertTrue(items.all { it.isTemplate && !it.isComplete })
        assertEquals(packing, after.single { it.id == 3L }) // the original carries on
    }

    @Test fun `using a template makes a normal list linked back to it, and an unchanged one has no changes`() {
        val withTemplate = saved()
        val template = templateRoots(withTemplate).single()
        val (use, listId) = planUseTemplate(withTemplate, emptyMap(), emptyList(), template.id, now)!!
        val after = use.applyTo(withTemplate)
        val list = after.single { it.id == listId }
        assertFalse(list.isTemplate)
        assertEquals(template.id, list.fromTemplateId)
        assertEquals(1L, list.parentId)
        assertTrue(checklistItems(listId, after).all { !it.isTemplate && it.fromTemplateId != null })
        assertEquals(emptyList<TemplateChange>(), templateChanges(after, listId))
    }

    @Test fun `changes in the list are offered back, and only the chosen ones reach the template`() {
        val withTemplate = saved()
        val template = templateRoots(withTemplate).single()
        val (use, listId) = planUseTemplate(withTemplate, emptyMap(), emptyList(), template.id, now)!!
        var after = use.applyTo(withTemplate)
        val listItems = checklistItems(listId, after)
        val listSocks = listItems.single { it.title == "Socks" }
        val listPassport = listItems.single { it.title == "Passport" }
        val jacket = Task(id = 100, title = "Rain jacket", parentId = listSocks.parentId)
        val hat = Task(id = 101, title = "Sunhat", parentId = listId)
        after = after.map { if (it.id == listSocks.id) it.copy(title = "Socks ×5") else it }.filter { it.id != listPassport.id } + jacket + hat
        val changes = templateChanges(after, listId)
        assertEquals(setOf("add-100", "add-101", "change-${listSocks.id}", "remove-${listPassport.fromTemplateId}"), changes.map { it.key }.toSet())

        val update = planUpdateTemplate(after, emptyMap(), listId, chosen = setOf("add-100", "change-${listSocks.id}"), oneOff = setOf("add-101"), now = now)
        after = update.intoTemplates(after.filter { it.isTemplate }.map { it.id }.toSet()).applyTo(after)
        val templateItems = checklistItems(template.id, after).map { it.title }
        assertEquals(setOf("Passport", "Socks ×5", "Rain jacket"), templateItems.toSet()) // Passport not removed: unchosen
        assertTrue(checklistItems(template.id, after).all { it.isTemplate })
        assertEquals(setOf("remove-${listPassport.fromTemplateId}"), templateChanges(after, listId).map { it.key }.toSet()) // the hat: just this time
    }

    @Test fun `deleting a folder keeps its templates, moved to the top level`() {
        val withTemplate = saved()
        val deleting = withTemplate.filter { it.id == 1L || com.kzhovn.todoapp.data.isUnder(it, 1, withTemplate.associateBy { t -> t.id }) }
        val (moves, kept) = templatesKeptFrom(deleting, withTemplate)
        assertNull(moves.single().parentId)
        assertEquals(withTemplate.count { it.isTemplate }, kept.size)
    }
}
