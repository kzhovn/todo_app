package com.kzhovn.todoapp.repository

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.notifications.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OrderAndInheritTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: TodoDatabase
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java).allowMainThreadQueries().build()
        repository = TaskRepository(
            db.taskDao(), ReminderScheduler(context, context.getSystemService(Context.ALARM_SERVICE) as AlarmManager), db.taskContextDao()
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun childrenOf(parent: Long?) =
        repository.getAllTasks().filter { it.parentId == parent }.sortedWith(TaskOrder).map { it.title }

    @Test
    fun `moving before, after, and into another list renumbers the right siblings`() = runBlocking {
        val folder = repository.createTask(Task(type = TaskType.FOLDER, title = "F"))
        val a = repository.createTask(Task(title = "a", parentId = folder))
        val b = repository.createTask(Task(title = "b", parentId = folder))
        val c = repository.createTask(Task(title = "c", parentId = folder))

        repository.moveNextTo(c, a, after = false)
        assertEquals(listOf("c", "a", "b"), childrenOf(folder))
        repository.moveNextTo(c, b, after = true)
        assertEquals(listOf("a", "b", "c"), childrenOf(folder))

        val top = repository.createTask(Task(title = "top"))
        repository.moveNextTo(b, top, after = false) // lands at top level, before "top"
        assertEquals(listOf("F", "b", "top"), childrenOf(null))
        assertEquals(listOf("a", "c"), childrenOf(folder))

        repository.reparent(b, folder) // dropped onto the folder: goes to the end
        assertEquals(listOf("a", "c", "b"), childrenOf(folder))
    }

    @Test
    fun `only subtasks overriding a changed field are found, and clearing makes them inherit`() = runBlocking {
        val home = ContextRepository(db.taskContextDao()).createContext(TaskContext(name = "Home", type = ContextType.PLACE))
        val parent = repository.createTask(Task(title = "P", dueDate = 100L))
        val own = repository.createTask(Task(title = "own due", parentId = parent, dueDate = 50L))
        repository.createTask(Task(title = "inherits", parentId = parent))
        val grandchild = repository.createTask(Task(title = "own ctx", parentId = own))
        ContextRepository(db.taskContextDao()).setTaskContexts(grandchild, setOf(home))

        val overridingDue = repository.descendantsOverriding(parent, setOf(InheritedField.DUE))
        assertEquals(listOf("own due"), overridingDue.map { it.title })

        repository.clearInherited(overridingDue, setOf(InheritedField.DUE))
        assertNull(repository.getTask(own)!!.dueDate)
        assertEquals(listOf("own ctx"), repository.descendantsOverriding(parent, setOf(InheritedField.CONTEXTS)).map { it.title })
    }
}
