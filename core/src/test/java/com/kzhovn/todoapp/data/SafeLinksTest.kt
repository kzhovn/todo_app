package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeLinksTest {
    private val folder = Task(id = 1, title = "Admin", type = TaskType.FOLDER)
    private val passport = Task(id = 2, title = "Renew passport", parentId = 1)
    private val photo = Task(id = 3, title = "Take photo", parentId = 2)
    private val form = Task(id = 4, title = "Form")
    private val byId = listOf(folder, passport, photo, form).associateBy { it.id }

    @Test fun aParentThatMakesALoopOrIsGoneKeepsTheStoredOne() {
        assertEquals(1L, safeParent(passport.copy(parentId = 3), 1L, byId)) // under its own subtask
        assertEquals(1L, safeParent(passport.copy(parentId = 99), 1L, byId))
        assertEquals(4L, safeParent(passport.copy(parentId = 4), 1L, byId))
        assertEquals(null, safeParent(passport.copy(parentId = null), 1L, byId))
    }

    @Test fun loopsAndFoldersAreDroppedFromDependencies() {
        val edges = listOf(TaskDependency(4, 2)) // form waits on passport
        assertEquals(setOf(3L), safeDependencies(passport, setOf(4, 1, 3, 2, 99), byId, edges))
        assertEquals(emptySet<Long>(), safeDependencies(folder, setOf(4), byId, edges))
    }
}
