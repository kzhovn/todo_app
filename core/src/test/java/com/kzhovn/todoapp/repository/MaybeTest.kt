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

    @Test
    fun `starring a maybe makes it a normal starred task`() {
        val starred = Task(id = 1, title = "someday", isMaybe = true).toggledStar()
        assertEquals(false to true, starred.isMaybe to starred.isStarred)
        assertFalse(starred.toggledStar().isStarred)
    }
}
