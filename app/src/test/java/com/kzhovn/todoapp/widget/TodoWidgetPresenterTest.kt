package com.kzhovn.todoapp.widget

import com.kzhovn.todoapp.data.Task
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoWidgetPresenterTest {
    @Test
    fun `maps tasks to widget rows preserving id title complete and starred state`() {
        val tasks = listOf(
            Task(id = 1, title = "Buy milk", isComplete = false, isStarred = true),
            Task(id = 2, title = "Walk dog", isComplete = true, isStarred = false)
        )

        val rows = TodoWidgetPresenter.toRows(tasks)

        assertEquals(
            listOf(
                WidgetTaskRow(1, "Buy milk", isComplete = false, isStarred = true),
                WidgetTaskRow(2, "Walk dog", isComplete = true, isStarred = false)
            ),
            rows
        )
    }

    @Test
    fun `empty task list maps to empty rows`() {
        assertEquals(emptyList<WidgetTaskRow>(), TodoWidgetPresenter.toRows(emptyList()))
    }
}
