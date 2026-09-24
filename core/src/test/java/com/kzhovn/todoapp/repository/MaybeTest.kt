package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MaybeTest {
    @Test
    fun `maybes are never active and never starred`() {
        val maybe = Task(id = 1, title = "someday", isMaybe = true)
        val normal = Task(id = 2, title = "now")

        val active = computeActiveTasks(listOf(maybe, normal), emptyMap(), emptyList(), emptyList(), emptyList(), now = 0L)

        assertEquals(listOf(normal), active)
        assertFalse(maybe.copy(isStarred = true).starRule().isStarred)
    }
}
