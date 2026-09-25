package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderSectionsTest {
    @Test
    fun `groups by outermost folder in tree order, folderless last, empty sections dropped`() {
        val work = Task(id = 1, title = "Work", type = TaskType.FOLDER, position = 2)
        val home = Task(id = 2, title = "Home", type = TaskType.FOLDER, position = 1)
        val sub = Task(id = 3, title = "Garden", type = TaskType.FOLDER, parentId = home.id)
        val hobby = Task(id = 4, title = "Hobby", type = TaskType.FOLDER) // no active tasks
        val weed = Task(id = 10, title = "Weed", parentId = sub.id)
        val bug = Task(id = 11, title = "Bug", parentId = work.id)
        val step = Task(id = 12, title = "Step", parentId = bug.id)
        val loose = Task(id = 13, title = "Loose")
        val byId = listOf(work, home, sub, hobby, weed, bug, step, loose).associateBy { it.id }

        val sections = sectionsByTopFolder(listOf(loose, bug, weed, step), byId)
        assertEquals(listOf("Home", "Work", null), sections.map { it.first?.title })
        assertEquals(listOf(listOf("Weed"), listOf("Bug", "Step"), listOf("Loose")), sections.map { s -> s.second.map { it.title } })
        assertEquals(listOf("Work"), sectionsByTopFolder(listOf(bug), byId).map { it.first?.title })
    }
}
