package com.kzhovn.todoapp.ui.theme

import androidx.compose.ui.graphics.Color
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorTest {
    private val palette = listOf(Color(0xFF111111), Color(0xFF222222), Color(0xFF333333))

    @Test
    fun `folders get distinct colors in creation order, cycling past the palette size`() {
        val folders = listOf(9L, 3L, 5L, 7L).map { Task(id = it, type = TaskType.FOLDER, title = "f$it") }
        val colors = folderColors(folders + Task(id = 4, title = "a task"), palette)

        assertEquals(mapOf(3L to palette[0], 5L to palette[1], 7L to palette[2], 9L to palette[0]), colors)
    }
}
