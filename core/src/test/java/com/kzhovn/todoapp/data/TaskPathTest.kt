package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskPathTest {
    private val personal = Task(id = 1, title = "Personal", type = TaskType.FOLDER)
    private val admin = Task(id = 2, title = "Admin", type = TaskType.FOLDER, parentId = 1)
    private val passport = Task(id = 3, title = "Renew passport", parentId = 2)
    private val photo = Task(id = 4, title = "Take photo", parentId = 3, isComplete = true)
    private val form = Task(id = 5, title = "Form", parentId = 3)
    private val all = listOf(personal, admin, passport, photo, form)
    private val byId = all.associateBy { it.id }

    @Test fun ancestorsOutermostFirst() = assertEquals(listOf(1L, 2L, 3L), ancestors(photo, byId).map { it.id })

    @Test fun moveOutGoesToTheFolderTheParentIsIn() {
        assertEquals(2L, moveOutFolderId(passport, byId))
        assertEquals(2L, moveOutFolderId(photo, byId)) // however deep
        assertEquals(1L, moveOutFolderId(admin, byId))
        assertEquals(null, moveOutFolderId(personal, byId))
    }

    @Test fun progressCountsDoneSubtasks() = assertEquals(1 to 2, subtaskProgress(3, all))
}
