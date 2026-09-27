package com.kzhovn.todoapp.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.notifications.ReminderScheduler
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.ContextType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    private lateinit var contexts: ContextRepository

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
        contexts = ContextRepository(db.taskContextDao())
        viewModel = TaskEditViewModel(repository, contexts)
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
    fun `save passes the newly created task's id to onSaved`() = runTest {
        var savedId: Long? = null
        viewModel.save(Task(title = "New task")) { id -> savedId = id }
        advanceUntilIdle()

        assertNotNull(savedId)
        assertEquals("New task", repository.getTask(savedId!!)?.title)
    }

    @Test
    fun `save passes the existing task's id to onSaved on update`() = runTest {
        val taskId = repository.createTask(Task(title = "Original"))
        var savedId: Long? = null

        viewModel.save(Task(id = taskId, title = "Updated")) { id -> savedId = id }
        advanceUntilIdle()

        assertEquals(taskId, savedId)
        assertEquals("Updated", repository.getTask(taskId)?.title)
    }

    private suspend fun editorFor(id: Long) = TaskEditViewModel(repository, contexts, id).also { it.load(draft = null, draftDependsOn = null) }

    @Test
    fun `only a real change counts as dirty, and a save becomes the new baseline`() = runTest {
        val id = repository.createTask(Task(title = "Call mom"))
        val editor = editorFor(id)
        assertFalse(editor.isDirty())
        editor.task = editor.task.copy(title = "Call mom back")
        assertTrue(editor.isDirty())
        editor.saveEdits { _, _ -> }
        advanceUntilIdle()
        assertFalse(editor.isDirty()) // an auto-save keeps the editor open
        assertEquals("Call mom back", repository.getTask(id)?.title)
    }

    @Test
    fun `saving asks first for a project's first step, and about subtasks with their own date`() = runTest {
        val project = editorFor(0).apply { task = task.copy(title = "Move", type = TaskType.PROJECT) }
        assertEquals(TaskEditViewModel.Question.FirstStep, project.questionBeforeSave())

        val parentId = repository.createTask(Task(title = "Trip"))
        repository.createTask(Task(title = "Book", parentId = parentId, dueDate = 1_800_000_000_000L))
        val editor = editorFor(parentId)
        editor.task = editor.task.copy(startDate = 1_790_000_000_000L)
        assertNull(editor.questionBeforeSave()) // the subtask's own due date isn't what changed
        editor.task = editor.task.copy(dueDate = 1_790_000_000_000L)
        assertTrue(editor.questionBeforeSave() is TaskEditViewModel.Question.UpdateSubtasks)
    }

    @Test
    fun `a new task's pending subtasks, dependents and contexts are linked on save`() = runTest {
        val home = contexts.createContext(TaskContext(name = "Home", type = ContextType.PLACE))
        val waiting = repository.createTask(Task(title = "Paint"))
        val editor = editorFor(0)
        editor.task = editor.task.copy(title = "Buy paint")
        editor.pendingSubtasks = listOf("Pick a colour")
        editor.pendingDependentIds = setOf(waiting)
        editor.contextIds = setOf(home)
        var savedId = 0L
        editor.saveEdits { id, _ -> savedId = id }
        advanceUntilIdle()
        assertEquals(listOf("Pick a colour"), repository.getAllTasks().filter { it.parentId == savedId }.map { it.title })
        assertEquals(setOf(savedId), repository.getDependencyIds(waiting))
        assertEquals(listOf(home), contexts.getContextsForTask(savedId).map { it.id })
    }
}
