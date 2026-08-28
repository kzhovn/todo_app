package com.kzhovn.todoapp.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.notifications.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskRepositoryActiveTest {
    private lateinit var db: TodoDatabase
    private lateinit var repository: TaskRepository
    private lateinit var contextRepository: ContextRepository
    private val now = 1_700_000_000_000L
    private val allDays = ContextTimeWindow.ALL_DAYS

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), TodoDatabase::class.java
        ).allowMainThreadQueries().build()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val alarmManager = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        repository = TaskRepository(db.taskDao(), ReminderScheduler(context, alarmManager), db.taskContextDao())
        contextRepository = ContextRepository(db.taskContextDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `task with no start date and no blockers is active`() = runBlocking {
        repository.createTask(Task(title = "Simple task"))
        assertEquals(1, repository.getActiveTasks(now, 600, allDays).size)
    }

    @Test
    fun `task with future start date is not active`() = runBlocking {
        repository.createTask(Task(title = "Future task", startDate = now + 1_000))
        assertTrue(repository.getActiveTasks(now, 600, allDays).isEmpty())
    }

    @Test
    fun `task blocked by incomplete dependency is not active`() = runBlocking {
        val prereqId = repository.createTask(Task(title = "Prereq"))
        val dependentId = repository.createTask(Task(title = "Dependent"))
        repository.addDependency(dependentId, prereqId)
        val active = repository.getActiveTasks(now, 600, allDays)
        assertEquals(listOf("Prereq"), active.map { it.title })
    }

    @Test
    fun `second sequential subtask is inactive until first completes`() = runBlocking {
        val parentId = repository.createTask(Task(type = TaskType.FOLDER, title = "Project", sequential = true))
        val firstId = repository.createTask(Task(title = "Step 1", parentId = parentId))
        repository.createTask(Task(title = "Step 2", parentId = parentId))
        assertEquals(listOf("Step 1"), repository.getActiveTasks(now, 600, allDays).map { it.title })
        repository.toggleComplete(firstId, now)
        assertEquals(listOf("Step 2"), repository.getActiveTasks(now, 600, allDays).map { it.title })
    }

    @Test
    fun `folder rows never appear as active`() = runBlocking {
        repository.createTask(Task(type = TaskType.FOLDER, title = "Work"))
        assertTrue(repository.getActiveTasks(now, 600, allDays).isEmpty())
    }

    @Test
    fun `task requiring unsatisfied place context is not active`() = runBlocking {
        val contextId = contextRepository.createContext(
            TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyHome", isCurrentlySatisfied = false)
        )
        val taskId = repository.createTask(Task(title = "Water plants"))
        contextRepository.setTaskContexts(taskId, setOf(contextId))
        assertTrue(repository.getActiveTasks(now, 600, allDays).isEmpty())
    }

    @Test
    fun `task requiring time context outside window is not active`() = runBlocking {
        val contextId = contextRepository.createContext(TaskContext(name = "Business hours", type = ContextType.TIME))
        contextRepository.addTimeWindow(
            ContextTimeWindow(contextId = contextId, windowStartMinute = 540, windowEndMinute = 1020)
        )
        val taskId = repository.createTask(Task(title = "Call bank"))
        contextRepository.setTaskContexts(taskId, setOf(contextId))
        assertTrue(repository.getActiveTasks(now, 100, allDays).isEmpty())
        assertEquals(1, repository.getActiveTasks(now, 600, allDays).size)
    }

    @Test
    fun `time window spanning midnight matches on both sides`() = runBlocking {
        val contextId = contextRepository.createContext(TaskContext(name = "Overnight", type = ContextType.TIME))
        contextRepository.addTimeWindow(
            ContextTimeWindow(contextId = contextId, windowStartMinute = 22 * 60, windowEndMinute = 6 * 60)
        )
        val taskId = repository.createTask(Task(title = "Check server logs"))
        contextRepository.setTaskContexts(taskId, setOf(contextId))
        assertEquals(1, repository.getActiveTasks(now, 23 * 60, allDays).size)
        assertEquals(1, repository.getActiveTasks(now, 3 * 60, allDays).size)
        assertTrue(repository.getActiveTasks(now, 12 * 60, allDays).isEmpty())
    }

    @Test
    fun `time window respects its day-of-week mask`() = runBlocking {
        val contextId = contextRepository.createContext(TaskContext(name = "Weekdays only", type = ContextType.TIME))
        // Mon-Fri: bits 1..5 (Sunday is bit 0 per Calendar.DAY_OF_WEEK - 1).
        contextRepository.addTimeWindow(
            ContextTimeWindow(contextId = contextId, windowStartMinute = 540, windowEndMinute = 1020, daysMask = 0b0111110)
        )
        val taskId = repository.createTask(Task(title = "Stand-up"))
        contextRepository.setTaskContexts(taskId, setOf(contextId))
        assertEquals(1, repository.getActiveTasks(now, 600, 1 shl 2).size) // Tuesday
        assertTrue(repository.getActiveTasks(now, 600, 1 shl 0).isEmpty()) // Sunday
    }

    @Test
    fun `a subtask with no start date inherits its parent's start date`() = runBlocking {
        val future = now + 1_000
        val parentId = repository.createTask(Task(title = "Parent", startDate = future))
        repository.createTask(Task(title = "Child", parentId = parentId, startDate = null))
        // Both parent and child are gated by the same future startDate — neither should be active yet.
        assertTrue(repository.getActiveTasks(now, 600, allDays).isEmpty())
    }

    @Test
    fun `a subtask's own start date overrides the inherited parent value`() = runBlocking {
        val future = now + 1_000
        val parentId = repository.createTask(Task(title = "Parent", startDate = future))
        repository.createTask(Task(title = "Child", parentId = parentId, startDate = now - 1_000))
        val active = repository.getActiveTasks(now, 600, allDays)
        assertEquals(listOf("Child"), active.map { it.title })
    }

    @Test
    fun `a subtask inherits an unsatisfied context from its parent`() = runBlocking {
        val contextId = contextRepository.createContext(
            TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyHome", isCurrentlySatisfied = false)
        )
        val parentId = repository.createTask(Task(title = "Parent"))
        contextRepository.setTaskContexts(parentId, setOf(contextId))
        repository.createTask(Task(title = "Child", parentId = parentId))
        // Parent itself has no due/start blockers but IS excluded (unsatisfied context); child
        // has no context of its own, so it inherits the parent's and is excluded too.
        assertTrue(repository.getActiveTasks(now, 600, allDays).isEmpty())
    }

    @Test
    fun `getActiveTasksFrom filters pre-fetched data the same way getActiveTasks does`() = runBlocking {
        repository.createTask(Task(title = "Simple task"))
        repository.createTask(Task(title = "Future task", startDate = now + 1_000))

        val all = repository.getAllTasks()
        val contextsByTaskId = repository.getAllTaskContexts()

        val fromHelper = repository.getActiveTasksFrom(all, contextsByTaskId, now, 600, allDays)
        val fromPublicEntry = repository.getActiveTasks(now, 600, allDays)

        assertEquals(fromPublicEntry.map { it.id }.toSet(), fromHelper.map { it.id }.toSet())
        assertEquals(listOf("Simple task"), fromHelper.map { it.title })
    }
}
