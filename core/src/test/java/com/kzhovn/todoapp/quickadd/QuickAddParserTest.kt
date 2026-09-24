package com.kzhovn.todoapp.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuickAddParserTest {
    @Test
    fun `plain title with no flags`() {
        val task = QuickAddParser.parse("Buy milk")
        assertEquals("Buy milk", task.title)
        assertNull(task.startDate)
        assertNull(task.dueDate)
    }

    @Test
    fun `start flag with today keyword`() {
        val task = QuickAddParser.parse("Water plants -s today")
        assertEquals("Water plants", task.title)
        assertNotNull(task.startDate)
    }

    @Test
    fun `due flag with iso date`() {
        val task = QuickAddParser.parse("File taxes -d 2026-04-15")
        assertEquals("File taxes", task.title)
        assertNotNull(task.dueDate)
    }

    @Test
    fun `both flags are stripped from the title`() {
        val task = QuickAddParser.parse("Plan trip -s tomorrow -d 2026-09-01")
        assertEquals("Plan trip", task.title)
        assertNotNull(task.startDate)
        assertNotNull(task.dueDate)
    }

    @Test
    fun `unparseable date value is ignored, flag stripped from title`() {
        val task = QuickAddParser.parse("Do thing -s whenever")
        assertEquals("Do thing", task.title)
        assertNull(task.startDate)
    }
}
