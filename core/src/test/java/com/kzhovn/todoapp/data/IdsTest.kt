package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {
    @Test
    fun `ids are strictly increasing, JS-safe, and above legacy autoincrement ids`() {
        val ids = List(10_000) { newId() }
        assertEquals(ids.sorted().distinct(), ids)
        assertTrue(ids.all { it in 1_000_000_000L until (1L shl 53) })
    }
}
