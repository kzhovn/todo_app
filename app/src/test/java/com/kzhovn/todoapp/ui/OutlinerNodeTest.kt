package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlinerNodeTest {
    @Test
    fun `top-level tasks and folders have no parent`() {
        val tasks = listOf(
            Task(id = 1, type = TaskType.FOLDER, title = "Home"),
            Task(id = 2, title = "Call dentist")
        )
        val tree = buildOutlinerTree(tasks)
        assertEquals(2, tree.size)
        assertTrue(tree[0] is OutlinerNode.FolderNode)
        assertTrue(tree[1] is OutlinerNode.TaskNode)
    }

    @Test
    fun `tasks nest under their parent folder`() {
        val tasks = listOf(
            Task(id = 1, type = TaskType.FOLDER, title = "Home"),
            Task(id = 2, title = "Water the plants", parentId = 1)
        )
        val tree = buildOutlinerTree(tasks)
        assertEquals(1, tree.size)
        assertEquals(1, tree[0].children.size)
        assertEquals("Water the plants", tree[0].children[0].task.title)
    }

    @Test
    fun `subfolders and subtasks nest to arbitrary depth`() {
        val tasks = listOf(
            Task(id = 1, type = TaskType.FOLDER, title = "Work"),
            Task(id = 2, type = TaskType.FOLDER, title = "Client X", parentId = 1),
            Task(id = 3, title = "Send invoice", parentId = 2),
            Task(id = 4, title = "Follow up", parentId = 3)
        )
        val tree = buildOutlinerTree(tasks)
        val work = tree[0]
        val clientX = work.children[0]
        val sendInvoice = clientX.children[0]
        val followUp = sendInvoice.children[0]
        assertEquals("Client X", clientX.task.title)
        assertEquals("Send invoice", sendInvoice.task.title)
        assertEquals("Follow up", followUp.task.title)
    }

    @Test
    fun `siblings are ordered by id`() {
        val tasks = listOf(
            Task(id = 2, title = "Second"),
            Task(id = 1, title = "First")
        )
        val tree = buildOutlinerTree(tasks)
        assertEquals(listOf("First", "Second"), tree.map { it.task.title })
    }

    @Test
    fun `subtaskCounts reports completed and total children`() {
        val tasks = listOf(
            Task(id = 1, title = "Draft Q3 planning doc"),
            Task(id = 2, title = "Pull Q2 numbers", parentId = 1, isComplete = true),
            Task(id = 3, title = "Write draft", parentId = 1),
            Task(id = 4, title = "Get feedback from Sam", parentId = 1)
        )
        val counts = subtaskCounts(tasks)
        assertEquals(1 to 3, counts[1L])
    }

    @Test
    fun `subtaskCounts has no entry for tasks with no children`() {
        val tasks = listOf(Task(id = 1, title = "Call dentist"))
        assertTrue(subtaskCounts(tasks).isEmpty())
    }

    @Test
    fun `hideCompleted omits a completed task but keeps its active children`() {
        val tasks = listOf(
            Task(id = 1, type = TaskType.FOLDER, title = "Project"),
            Task(id = 2, title = "Old milestone", parentId = 1, isComplete = true),
            Task(id = 3, title = "Next step", parentId = 2)
        )
        val tree = buildOutlinerTree(tasks, hideCompleted = true)
        val project = tree[0]
        assertEquals(1, project.children.size)
        assertEquals("Next step", project.children[0].task.title)
    }

    @Test
    fun `hideCompleted omits a completed top-level task with no children`() {
        val tasks = listOf(Task(id = 1, title = "Done", isComplete = true))
        val tree = buildOutlinerTree(tasks, hideCompleted = true)
        assertTrue(tree.isEmpty())
    }

    @Test
    fun `hideCompleted defaults to false, keeping existing behavior`() {
        val tasks = listOf(Task(id = 1, title = "Done", isComplete = true))
        val tree = buildOutlinerTree(tasks)
        assertEquals(1, tree.size)
    }
}
