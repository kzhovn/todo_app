package com.kzhovn.todoapp.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskDaoSearchTest {
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
    fun `search matches title case-insensitively`() = runBlocking {
        dao.insert(Task(title = "Buy Milk"))
        dao.insert(Task(title = "Walk the dog"))

        val results = dao.search("milk")

        assertEquals(listOf("Buy Milk"), results.map { it.title })
    }

    @Test
    fun `search excludes folder rows`() = runBlocking {
        dao.insert(Task(type = TaskType.FOLDER, title = "Milk Projects"))
        dao.insert(Task(title = "Buy Milk"))

        val results = dao.search("milk")

        assertEquals(listOf("Buy Milk"), results.map { it.title })
    }

    @Test
    fun `search with no matches returns empty list`() = runBlocking {
        dao.insert(Task(title = "Buy Milk"))
        assertTrue(dao.search("xylophone").isEmpty())
    }
}
