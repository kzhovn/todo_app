package com.kzhovn.todoapp.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorTest {
    private val palette = listOf(Color(0xFF111111), Color(0xFF222222), Color(0xFF333333))

    @Test
    fun `same folder id always returns the same color`() {
        assertEquals(folderColor(5L, palette), folderColor(5L, palette))
    }

    @Test
    fun `sequential ids cycle through the whole palette`() {
        val colors = (1L..6L).map { folderColor(it, palette) }.toSet()
        assertEquals(3, colors.size)
    }

    @Test
    fun `a negative id still resolves to a valid palette entry`() {
        val color = folderColor(-7L, palette)
        assertTrue(palette.contains(color))
    }
}
