package com.kzhovn.todoapp.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.notifications.ReminderScheduler
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
        repository = TaskRepository(db.taskDao(), ReminderScheduler(context, alarmManager))
        viewModel = TaskListViewModel(repository, clock = { now })
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
    fun `deleteWithUndo removes the task and undoDelete restores it`() = runTest {
        val taskId = repository.createTask(Task(title = "Oops"))
        viewModel.load(TaskListMode.ALL)
        advanceUntilIdle()

        var deleted: Task? = null
        viewModel.deleteWithUndo(taskId, TaskListMode.ALL) { deleted = it }
        advanceUntilIdle()
        assertEquals(emptyList<String>(), viewModel.tasks.value.map { it.title })

        viewModel.undoDelete(deleted!!, TaskListMode.ALL)
        advanceUntilIdle()
        assertEquals(listOf("Oops"), viewModel.tasks.value.map { it.title })
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
}
