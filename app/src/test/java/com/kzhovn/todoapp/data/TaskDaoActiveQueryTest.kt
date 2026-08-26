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
        val active = taskDao.getActiveTasks(now, 600, ContextTimeWindow.ALL_DAYS)
        assertEquals(1, active.size)
    }

    @Test
    fun `task with future start date is not active`() = runBlocking {
        taskDao.insert(Task(title = "Future task", startDate = now + 1_000))
        val active = taskDao.getActiveTasks(now, 600, ContextTimeWindow.ALL_DAYS)
        assertTrue(active.isEmpty())
    }

    @Test
    fun `task blocked by incomplete dependency is not active`() = runBlocking {
        val prereqId = taskDao.insert(Task(title = "Prereq"))
        val dependentId = taskDao.insert(Task(title = "Dependent"))
        taskDao.insertDependency(TaskDependency(dependentId, prereqId))

        val active = taskDao.getActiveTasks(now, 600, ContextTimeWindow.ALL_DAYS)
        assertEquals(1, active.size)
        assertEquals("Prereq", active[0].title)
    }

    @Test
    fun `second sequential subtask is inactive until first completes`() = runBlocking {
        val parentId = taskDao.insert(Task(type = TaskType.FOLDER, title = "Project", sequential = true))
        val firstId = taskDao.insert(Task(title = "Step 1", parentId = parentId))
        taskDao.insert(Task(title = "Step 2", parentId = parentId))

        val beforeCompletion = taskDao.getActiveTasks(now, 600, ContextTimeWindow.ALL_DAYS)
        assertEquals(listOf("Step 1"), beforeCompletion.map { it.title })

        taskDao.update(taskDao.getById(firstId)!!.copy(isComplete = true, completedAt = now))
        val afterCompletion = taskDao.getActiveTasks(now, 600, ContextTimeWindow.ALL_DAYS)
        assertEquals(listOf("Step 2"), afterCompletion.map { it.title })
    }

    @Test
    fun `task requiring unsatisfied place context is not active`() = runBlocking {
        val contextId = contextDao.insert(
            TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyHome", isCurrentlySatisfied = false)
        )
        val taskId = taskDao.insert(Task(title = "Water plants"))
        contextDao.assignContext(TaskContextCrossRef(taskId, contextId))

        val active = taskDao.getActiveTasks(now, 600, ContextTimeWindow.ALL_DAYS)
        assertTrue(active.isEmpty())
    }

    @Test
    fun `task requiring time context outside window is not active`() = runBlocking {
        val contextId = contextDao.insert(TaskContext(name = "Business hours", type = ContextType.TIME))
        contextDao.insertTimeWindow(ContextTimeWindow(contextId = contextId, windowStartMinute = 540, windowEndMinute = 1020))
        val taskId = taskDao.insert(Task(title = "Call bank"))
        contextDao.assignContext(TaskContextCrossRef(taskId, contextId))

        val outsideWindow = taskDao.getActiveTasks(now, 100, ContextTimeWindow.ALL_DAYS)
        assertTrue(outsideWindow.isEmpty())

        val insideWindow = taskDao.getActiveTasks(now, 600, ContextTimeWindow.ALL_DAYS)
        assertEquals(1, insideWindow.size)
    }

    @Test
    fun `time window spanning midnight matches on both sides`() = runBlocking {
        val contextId = contextDao.insert(TaskContext(name = "Overnight", type = ContextType.TIME))
        contextDao.insertTimeWindow(ContextTimeWindow(contextId = contextId, windowStartMinute = 22 * 60, windowEndMinute = 6 * 60))
        val taskId = taskDao.insert(Task(title = "Check server logs"))
        contextDao.assignContext(TaskContextCrossRef(taskId, contextId))

        val lateNight = taskDao.getActiveTasks(now, 23 * 60, ContextTimeWindow.ALL_DAYS)
        assertEquals(1, lateNight.size)

        val earlyMorning = taskDao.getActiveTasks(now, 3 * 60, ContextTimeWindow.ALL_DAYS)
        assertEquals(1, earlyMorning.size)

        val midday = taskDao.getActiveTasks(now, 12 * 60, ContextTimeWindow.ALL_DAYS)
        assertTrue(midday.isEmpty())
    }

    @Test
    fun `time window respects its day-of-week mask`() = runBlocking {
        val contextId = contextDao.insert(TaskContext(name = "Weekdays only", type = ContextType.TIME))
        // Mon-Fri: bits 1..5 (Sunday is bit 0 per Calendar.DAY_OF_WEEK - 1).
        contextDao.insertTimeWindow(
            ContextTimeWindow(contextId = contextId, windowStartMinute = 540, windowEndMinute = 1020, daysMask = 0b0111110)
        )
        val taskId = taskDao.insert(Task(title = "Stand-up"))
        contextDao.assignContext(TaskContextCrossRef(taskId, contextId))

        val onWeekday = taskDao.getActiveTasks(now, 600, todayMask = 1 shl 2) // Tuesday
        assertEquals(1, onWeekday.size)

        val onWeekend = taskDao.getActiveTasks(now, 600, todayMask = 1 shl 0) // Sunday
        assertTrue(onWeekend.isEmpty())
    }

    @Test
    fun `folder rows never appear as active`() = runBlocking {
        taskDao.insert(Task(type = TaskType.FOLDER, title = "Work"))
        val active = taskDao.getActiveTasks(now, 600, ContextTimeWindow.ALL_DAYS)
        assertTrue(active.isEmpty())
    }
}
