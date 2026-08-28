package com.kzhovn.todoapp.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.notifications.ReminderScheduler
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.repository.TaskRepository
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskListViewModelTest {
    private lateinit var db: TodoDatabase
    private lateinit var repository: TaskRepository
    private lateinit var viewModel: TaskListViewModel
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .build()
        val alarmManager = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        repository = TaskRepository(db.taskDao(), ReminderScheduler(context, alarmManager), db.taskContextDao())
        viewModel = TaskListViewModel(repository, ContextRepository(db.taskContextDao()), clock = { now })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `doing mode includes only starred or imminently due active tasks`() = runTest {
        repository.createTask(Task(title = "Starred task", isStarred = true))
        repository.createTask(Task(title = "Due soon", dueDate = now + 1_000))
        repository.createTask(Task(title = "Not due soon", dueDate = now + 10 * 24 * 60 * 60 * 1000L))
        repository.createTask(Task(title = "Plain active task"))

        viewModel.load(TaskListMode.DOING)
        advanceUntilIdle()

        assertEquals(setOf("Starred task", "Due soon"), viewModel.tasks.value.map { it.title }.toSet())
    }

    @Test
    fun `active mode excludes tasks with a future start date`() = runTest {
        repository.createTask(Task(title = "Ready now"))
        repository.createTask(Task(title = "Not yet", startDate = now + 1_000))

        viewModel.load(TaskListMode.ACTIVE)
        advanceUntilIdle()

        assertEquals(listOf("Ready now"), viewModel.tasks.value.map { it.title })
    }

    @Test
    fun `all mode includes every task regardless of active status`() = runTest {
        repository.createTask(Task(title = "Future task", startDate = now + 1_000))

        viewModel.load(TaskListMode.ALL)
        advanceUntilIdle()

        assertEquals(listOf("Future task"), viewModel.tasks.value.map { it.title })
    }

    @Test
    fun `search matches title case-insensitively regardless of active status`() = runTest {
        repository.createTask(Task(title = "Buy milk"))
        repository.createTask(Task(title = "Walk the dog", startDate = now + 1_000))

        viewModel.search("milk")
        advanceUntilIdle()

        assertEquals(listOf("Buy milk"), viewModel.tasks.value.map { it.title })
    }

    @Test
    fun `load populates subtask counts for tasks with children`() = runTest {
        val parentId = repository.createTask(Task(title = "Draft Q3 planning doc"))
        repository.createTask(Task(title = "Pull Q2 numbers", parentId = parentId, isComplete = true))
        repository.createTask(Task(title = "Write draft", parentId = parentId))

        viewModel.load(TaskListMode.ALL)
        advanceUntilIdle()

        assertEquals(1 to 2, viewModel.subtaskCounts.value[parentId])
    }

    @Test
    fun `requestComplete reopens an already-complete task`() = runTest {
        val taskId = repository.createTask(Task(title = "Ship report"))
        repository.toggleComplete(taskId, now)

        viewModel.requestComplete(taskId, TaskListMode.ALL) { _, _ -> }
        advanceUntilIdle()

        assertEquals(false, viewModel.tasks.value.first { it.id == taskId }.isComplete)
    }

    @Test
    fun `requestComplete asks for a decision when active subtasks exist`() = runTest {
        val parentId = repository.createTask(Task(title = "Plan trip"))
        repository.createTask(Task(title = "Book flight", parentId = parentId))
        var decision: Pair<Long, Int>? = null

        viewModel.requestComplete(parentId, TaskListMode.ALL) { id, count -> decision = id to count }
        advanceUntilIdle()

        assertEquals(parentId to 1, decision)
        assertEquals(false, repository.getTask(parentId)!!.isComplete)
    }

    @Test
    fun `requestComplete completes directly when there are no active subtasks`() = runTest {
        val taskId = repository.createTask(Task(title = "Ship report"))
        var decisionCalled = false

        viewModel.requestComplete(taskId, TaskListMode.ALL) { _, _ -> decisionCalled = true }
        advanceUntilIdle()

        assertEquals(false, decisionCalled)
        assertEquals(true, repository.getTask(taskId)!!.isComplete)
    }
}
