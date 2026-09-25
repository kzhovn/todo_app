package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderColorsTest {
    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
    private fun folder(id: Long, title: String, parent: Long? = null, position: Long? = null, slot: Int? = null) =
        Task(id = id, title = title, type = TaskType.FOLDER, parentId = parent, position = position, colorIndex = slot)

    // Applies the assignments, as the app and server do when they store them.
    private fun assign(tasks: List<Task>): List<Task> {
        val assigned = folderColorAssignments(tasks).associateBy { it.id }
        return tasks.map { assigned[it.id] ?: it }
    }

    @Test
    fun `first colouring follows the tree order, and slots then stick when older folders go`() {
        val work = folder(1, "Work", position = 2)
        val personal = folder(2, "Personal", position = 1)
        val hobby = folder(3, "Hobby", position = 3)
        val tasks = assign(listOf(work, personal, hobby))
        assertEquals(mapOf(2L to 0, 1L to 1, 3L to 2), tasks.associate { it.id to it.colorIndex })
        assertEquals("#3B8841", hex(folderColorsArgb(tasks).getValue(personal.id))) // green, the top one

        // Deleting Work frees its slot; Hobby keeps blue, and a new folder takes the freed slot.
        val afterDelete = assign(tasks.filter { it.id != work.id } + folder(4, "Garden"))
        assertEquals(mapOf(2L to 0, 3L to 2, 4L to 1), afterDelete.associate { it.id to it.colorIndex })
        assertEquals(emptyList<Task>(), folderColorAssignments(afterDelete))
    }

    @Test
    fun `subfolders are variants of their family, and a clash is resolved in the older folder's favour`() {
        val personal = folder(1, "Personal", slot = 0)
        val medical = folder(2, "Medical", parent = 1, slot = 0)
        val household = folder(3, "Household", parent = 1, slot = 0) // e.g. moved in from elsewhere
        val tasks = assign(listOf(personal, medical, household))
        assertEquals(mapOf(1L to 0, 2L to 0, 3L to 1), tasks.associate { it.id to it.colorIndex })

        val colors = folderColorsArgb(tasks)
        listOf(medical, household).map { colors.getValue(it.id) }.forEach { c ->
            val (r, g, b) = Triple(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
            assertTrue(hex(c), g > r && g > b) // still green
            assertNotEquals(colors.getValue(personal.id), c)
        }
        assertNotEquals(colors.getValue(medical.id), colors.getValue(household.id))
    }
}
