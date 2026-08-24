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
class TaskDaoActiveQueryTest {
    private lateinit var db: TodoDatabase
    private lateinit var taskDao: TaskDao
    private lateinit var contextDao: TaskContextDao
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), TodoDatabase::class.java
        ).allowMainThreadQueries().build()
        taskDao = db.taskDao()
        contextDao = db.taskContextDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `task with no start date and no blockers is active`() = runBlocking {
        taskDao.insert(Task(title = "Simple task"))
        val active = taskDao.getActiveTasks(now, 600)
        assertEquals(1, active.size)
    }

    @Test
    fun `task with future start date is not active`() = runBlocking {
        taskDao.insert(Task(title = "Future task", startDate = now + 1_000))
        val active = taskDao.getActiveTasks(now, 600)
        assertTrue(active.isEmpty())
    }

    @Test
    fun `task blocked by incomplete dependency is not active`() = runBlocking {
        val prereqId = taskDao.insert(Task(title = "Prereq"))
        val dependentId = taskDao.insert(Task(title = "Dependent"))
        taskDao.insertDependency(TaskDependency(dependentId, prereqId))

        val active = taskDao.getActiveTasks(now, 600)
        assertEquals(1, active.size)
        assertEquals("Prereq", active[0].title)
    }

    @Test
    fun `second sequential subtask is inactive until first completes`() = runBlocking {
        val parentId = taskDao.insert(Task(type = TaskType.FOLDER, title = "Project", sequential = true))
        val firstId = taskDao.insert(Task(title = "Step 1", parentId = parentId))
        taskDao.insert(Task(title = "Step 2", parentId = parentId))

        val beforeCompletion = taskDao.getActiveTasks(now, 600)
        assertEquals(listOf("Step 1"), beforeCompletion.map { it.title })

        taskDao.update(taskDao.getById(firstId)!!.copy(isComplete = true, completedAt = now))
        val afterCompletion = taskDao.getActiveTasks(now, 600)
        assertEquals(listOf("Step 2"), afterCompletion.map { it.title })
    }

    @Test
    fun `task requiring unsatisfied place context is not active`() = runBlocking {
        val contextId = contextDao.insert(
            TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyHome", isCurrentlySatisfied = false)
        )
        val taskId = taskDao.insert(Task(title = "Water plants"))
        contextDao.assignContext(TaskContextCrossRef(taskId, contextId))

        val active = taskDao.getActiveTasks(now, 600)
        assertTrue(active.isEmpty())
    }

    @Test
    fun `task requiring time context outside window is not active`() = runBlocking {
        val contextId = contextDao.insert(
            TaskContext(name = "Business hours", type = ContextType.TIME, windowStartMinute = 540, windowEndMinute = 1020)
        )
        val taskId = taskDao.insert(Task(title = "Call bank"))
        contextDao.assignContext(TaskContextCrossRef(taskId, contextId))

        val outsideWindow = taskDao.getActiveTasks(now, 100)
        assertTrue(outsideWindow.isEmpty())

        val insideWindow = taskDao.getActiveTasks(now, 600)
        assertEquals(1, insideWindow.size)
    }

    @Test
    fun `folder rows never appear as active`() = runBlocking {
        taskDao.insert(Task(type = TaskType.FOLDER, title = "Work"))
        val active = taskDao.getActiveTasks(now, 600)
        assertTrue(active.isEmpty())
    }
}
