package com.kzhovn.todoapp.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TodoDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContextRepositoryTest {
    private lateinit var db: TodoDatabase
    private lateinit var repository: ContextRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), TodoDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = ContextRepository(db.taskContextDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `createContext and getAllContexts round-trip`() = runBlocking {
        repository.createContext(TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyWifi"))
        assertEquals(listOf("Home"), repository.getAllContexts().map { it.name })
    }

    @Test
    fun `setTaskContexts assigns and reads back contexts for a task`() = runBlocking {
        val homeId = repository.createContext(TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyWifi"))
        val workId = repository.createContext(TaskContext(name = "Work", type = ContextType.PLACE, wifiSsid = "WorkWifi"))

        repository.setTaskContexts(1L, setOf(homeId, workId))

        assertEquals(setOf("Home", "Work"), repository.getContextsForTask(1L).map { it.name }.toSet())
    }

    @Test
    fun `setTaskContexts removes an unassignment`() = runBlocking {
        val homeId = repository.createContext(TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyWifi"))
        val workId = repository.createContext(TaskContext(name = "Work", type = ContextType.PLACE, wifiSsid = "WorkWifi"))
        repository.setTaskContexts(1L, setOf(homeId, workId))

        repository.setTaskContexts(1L, setOf(homeId))

        assertEquals(listOf("Home"), repository.getContextsForTask(1L).map { it.name })
    }

    @Test
    fun `setTaskContexts is idempotent when called twice with the same set`() = runBlocking {
        val homeId = repository.createContext(TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyWifi"))
        repository.setTaskContexts(1L, setOf(homeId))
        repository.setTaskContexts(1L, setOf(homeId))
        assertEquals(listOf("Home"), repository.getContextsForTask(1L).map { it.name })
    }
}
