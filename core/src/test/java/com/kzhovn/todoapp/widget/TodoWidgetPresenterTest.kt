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

    @Test
    fun `rows follow the tree's top-level folder order and carry the nearest folder's colour`() {
        val work = Task(id = 1, title = "Work", type = com.kzhovn.todoapp.data.TaskType.FOLDER, position = 2)
        val home = Task(id = 2, title = "Home", type = com.kzhovn.todoapp.data.TaskType.FOLDER, position = 1)
        val bug = Task(id = 10, title = "Bug", parentId = work.id)
        val step = Task(id = 11, title = "Step", parentId = bug.id)
        val dishes = Task(id = 12, title = "Dishes", parentId = home.id)
        val loose = Task(id = 13, title = "Loose")
        val byId = listOf(work, home, bug, step, dishes, loose).associateBy { it.id }
        val colors = mapOf(work.id to 0xFFFF0000.toInt(), home.id to 0xFF0000FF.toInt())

        val rows = TodoWidgetPresenter.toRows(listOf(loose, bug, step, dishes), allById = byId, folderColors = colors)
        assertEquals(listOf("Dishes", "Bug", "Step", "Loose"), rows.map { it.title })
        assertEquals(listOf(0xFF0000FF.toInt(), 0xFFFF0000.toInt(), 0xFFFF0000.toInt(), null), rows.map { it.barColor })
    }
}
