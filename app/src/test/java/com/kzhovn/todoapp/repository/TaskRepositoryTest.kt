package com.kzhovn.todoapp.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.TodoDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskRepositoryTest {
    private lateinit var db: TodoDatabase
    private lateinit var repository: TaskRepository
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), TodoDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = TaskRepository(db.taskDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `reparent moves a task under a new folder`() = runBlocking {
        val folderId = repository.createTask(Task(type = TaskType.FOLDER, title = "Work"))
        val taskId = repository.createTask(Task(title = "Ship report"))

        repository.reparent(taskId, folderId)

        assertEquals(folderId, repository.getTask(taskId)?.parentId)
    }

    @Test
    fun `toggleStar flips the starred flag`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Important"))
        repository.toggleStar(taskId)
        assertTrue(repository.getTask(taskId)!!.isStarred)
        repository.toggleStar(taskId)
        assertTrue(!repository.getTask(taskId)!!.isStarred)
    }

    @Test
    fun `snooze pushes the start date forward so the task drops out of active`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Later"))
        assertEquals(1, repository.getActiveTasks(now, 600).size)

        repository.snooze(taskId, durationMillis = 60_000, now = now)

        assertTrue(repository.getActiveTasks(now, 600).isEmpty())
    }

    @Test
    fun `markComplete sets completion fields`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Finish plan"))
        repository.markComplete(taskId, now)
        val task = repository.getTask(taskId)!!
        assertTrue(task.isComplete)
        assertEquals(now, task.completedAt)
    }

    @Test
    fun `deleteTask removes it and undoDelete restores it`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Oops"))
        val task = repository.getTask(taskId)!!

        repository.deleteTask(task)
        assertNull(repository.getTask(taskId))

        repository.undoDelete(task)
        assertEquals("Oops", repository.getTask(taskId)?.title)
    }

    @Test
    fun `getTasksUnderFolder returns nested descendants but not folders`() = runBlocking {
        val workId = repository.createTask(Task(type = TaskType.FOLDER, title = "Work"))
        val projectId = repository.createTask(Task(type = TaskType.FOLDER, title = "Project A", parentId = workId))
        repository.createTask(Task(title = "Write spec", parentId = projectId))
        repository.createTask(Task(title = "Unrelated task"))

        val underWork = repository.getTasksUnderFolder(workId)

        assertEquals(listOf("Write spec"), underWork.map { it.title })
    }
}
