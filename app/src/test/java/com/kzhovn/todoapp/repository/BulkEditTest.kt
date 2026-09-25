package com.kzhovn.todoapp.repository

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.notifications.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BulkEditTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: TodoDatabase
    private lateinit var repository: TaskRepository
    private lateinit var contexts: ContextRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java).allowMainThreadQueries().build()
        repository = TaskRepository(
            db.taskDao(), ReminderScheduler(context, context.getSystemService(Context.ALARM_SERVICE) as AlarmManager), db.taskContextDao()
        )
        contexts = ContextRepository(db.taskContextDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `applies set, clear, and keep across tasks, and skips folders`() = runBlocking {
        val work = repository.createTask(Task(type = TaskType.FOLDER, title = "Work"))
        val a = repository.createTask(Task(title = "A", dueDate = 100L, startDate = 50L))
        val b = repository.createTask(Task(title = "B", isStarred = true))
        val home = contexts.createContext(TaskContext(name = "Home", type = ContextType.PLACE))
        contexts.setTaskContexts(b, setOf(home))
        val blocker = repository.createTask(Task(title = "Blocker"))

        repository.applyBulkEdit(
            listOf(a, b, work),
            BulkEdit(
                starred = true, dueDate = DateChange(null), moveTo = FolderChange(work),
                removeContextIds = setOf(home), dependsOnId = blocker
            )
        )

        val taskA = repository.getTask(a)!!
        assertTrue(taskA.isStarred)
        assertNull(taskA.dueDate)
        assertEquals(50L, taskA.startDate)
        assertEquals(work, taskA.parentId)
        assertTrue(contexts.getContextsForTask(b).isEmpty())
        assertEquals(setOf(blocker), repository.getDependencyIds(a))
        assertEquals(setOf(blocker), repository.getDependencyIds(b))
        assertFalse(repository.getTask(work)!!.isStarred)
        assertNull(repository.getTask(work)!!.parentId)
    }

    @Test
    fun `maybe wins over star, and a dependency that would form a cycle is skipped`() = runBlocking {
        val task = repository.createTask(Task(title = "T"))
        val dependsOnTask = repository.createTask(Task(title = "Waits"))
        repository.addDependency(dependsOnTask, task)

        repository.applyBulkEdit(listOf(task), BulkEdit(starred = true, maybe = true, dependsOnId = dependsOnTask))

        val t = repository.getTask(task)!!
        assertTrue(t.isMaybe)
        assertFalse(t.isStarred)
        assertTrue(repository.getDependencyIds(task).isEmpty())
    }
}
