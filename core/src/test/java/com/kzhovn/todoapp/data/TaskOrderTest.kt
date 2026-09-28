package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.repository.computeActiveTasks
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskOrderTest {
    @Test
    fun `manual positions win, unpositioned newcomers go last, and sequential follows the order`() {
        val parent = Task(id = 1, title = "P", sequential = true)
        val a = Task(id = 10, title = "a", parentId = 1, position = 2)
        val b = Task(id = 11, title = "b", parentId = 1, position = 1)
        val c = Task(id = 12, title = "c", parentId = 1)

        assertEquals(listOf("b", "a", "c"), listOf(a, b, c).sortedWith(TaskOrder).map { it.title })
        val active = computeActiveTasks(listOf(parent, a, b, c), emptyMap(), emptyList(), emptyList(), emptyList(), now = 0L)
        // P waits on its open subtasks; of those, only the first in order is workable.
        assertEquals(listOf("b"), active.map { it.title })
    }

    @Test
    fun `projects are never active, and stall once every subtask is done`() {
        val project = Task(id = 1, title = "Coat", type = TaskType.PROJECT)
        val done = Task(id = 2, title = "cut", parentId = 1, isComplete = true)
        val open = Task(id = 3, title = "sew", parentId = 1)

        assertEquals(listOf("sew"), computeActiveTasks(listOf(project, open), emptyMap(), emptyList(), emptyList(), emptyList(), 0L).map { it.title })
        assertEquals(emptyList<Task>(), stalledProjects(listOf(project, done, open)))
        assertEquals(listOf(project), stalledProjects(listOf(project, done)))
    }

    @Test
    fun `a new task goes first below the folders, moving no sibling until there's no room`() {
        val home = Task(id = 1, title = "Home", type = TaskType.FOLDER)
        val all = mutableListOf(home, Task(id = 2, title = "Sub", type = TaskType.FOLDER, parentId = 1), Task(id = 3, title = "old", parentId = 1))
        var siblingWrites = 0
        for (n in 1..40) {
            val new = Task(id = 100L + n, title = "new $n", parentId = 1)
            val positions = newTaskPositions(all, new)
            siblingWrites += positions.keys.count { it != new.id }
            all.replaceAll { t -> positions[t.id]?.let { t.copy(position = it) } ?: t }
            all += new.copy(position = positions[new.id])
            assertEquals(listOf("Sub", "new $n"), all.filter { it.parentId == 1L }.sortedWith(TaskOrder).take(2).map { it.title })
        }
        assertEquals(listOf("Sub") + (40 downTo 1).map { "new $it" } + "old", all.filter { it.parentId == 1L }.sortedWith(TaskOrder).map { it.title })
        // Room between the folder and the first task runs out only now and then; it was every add.
        assert(siblingWrites < 40 * 5) { "respaced too often: $siblingWrites sibling writes" }
    }

    @Test
    fun `with no folders to stay below, a new task never moves its siblings`() {
        val all = mutableListOf(Task(id = 1, title = "Home", type = TaskType.FOLDER), Task(id = 2, title = "a", parentId = 1, position = 1))
        repeat(50) { n ->
            val new = Task(id = 100L + n, title = "n$n", parentId = 1)
            val positions = newTaskPositions(all, new)
            assertEquals(setOf(new.id), positions.keys)
            all += new.copy(position = positions[new.id])
        }
        assertEquals("n49", all.filter { it.parentId == 1L }.sortedWith(TaskOrder).first().title)
    }
}
