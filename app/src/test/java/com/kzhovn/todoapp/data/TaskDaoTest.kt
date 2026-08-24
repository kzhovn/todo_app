package com.kzhovn.todoapp.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskDaoTest {
    private lateinit var db: TodoDatabase
    private lateinit var dao: TaskDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), TodoDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.taskDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and retrieve a task`() = runBlocking {
        val id = dao.insert(Task(title = "Buy milk"))
        val loaded = dao.getById(id)
        assertEquals("Buy milk", loaded?.title)
    }

    @Test
    fun `getAll emits inserted tasks`() = runBlocking {
        dao.insert(Task(title = "Task A"))
        dao.insert(Task(title = "Task B"))
        assertEquals(2, dao.getAll().first().size)
    }

    @Test
    fun `folder row stores type distinctly from task`() = runBlocking {
        val folderId = dao.insert(Task(type = TaskType.FOLDER, title = "Work", icon = "briefcase"))
        val loaded = dao.getById(folderId)
        assertEquals(TaskType.FOLDER, loaded?.type)
    }
}
