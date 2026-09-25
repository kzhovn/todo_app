package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackburnerTest {
    @Test
    fun `maybes are stamped when marked, cleared when unmarked, and dim after a month`() {
        val marked = Task(id = 1, title = "someday", isMaybe = true, isStarred = true).withRules(now = 1000L)
        assertEquals(1000L, marked.maybeSince)
        assertFalse(marked.isStarred)
        assertEquals(1000L, marked.withRules(now = 5000L).maybeSince) // re-saving keeps the original stamp

        assertFalse(marked.isBackburner(1000L + BACKBURNER_AFTER - 1))
        assertTrue(marked.isBackburner(1000L + BACKBURNER_AFTER))
        assertNull(marked.copy(isMaybe = false).withRules(9000L).maybeSince)
    }
}
