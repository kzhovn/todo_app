package com.kzhovn.todoapp.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskEditViewModelTest {
    private lateinit var db: TodoDatabase
    private lateinit var repository: TaskRepository
    private lateinit var viewModel: TaskEditViewModel

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
        viewModel = TaskEditViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `save with id zero creates a new task`() = runTest {
        var saved = false
        viewModel.save(Task(title = "New task")) { saved = true }
        advanceUntilIdle()

        assertTrue(saved)
        assertEquals(listOf("New task"), repository.getAllTasks().map { it.title })
    }

    @Test
    fun `save with an existing id updates the task`() = runTest {
        val taskId = repository.createTask(Task(title = "Original"))

        viewModel.save(Task(id = taskId, title = "Renamed")) {}
        advanceUntilIdle()

        assertEquals("Renamed", repository.getTask(taskId)?.title)
    }

    @Test
    fun `setSequential toggles a folder's sequential flag`() = runTest {
        val folderId = repository.createTask(Task(type = TaskType.FOLDER, title = "Project"))

        viewModel.setSequential(folderId, true)
        advanceUntilIdle()

        assertTrue(repository.getTask(folderId)!!.sequential)
    }
}
