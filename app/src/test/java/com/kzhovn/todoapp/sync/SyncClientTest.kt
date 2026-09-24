package com.kzhovn.todoapp.sync

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.notifications.ReminderScheduler
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.server.Store
import com.kzhovn.todoapp.server.TaskService
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
import java.io.File

// End to end: the real app sync client against the real server Store, minus HTTP.
@RunWith(RobolectricTestRunner::class)
class SyncClientTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val config = SyncConfig("https://unused", "t")
    private val store = Store(File.createTempFile("server", ".db").apply { deleteOnExit() }.path)
    private val server = TaskService(store)
    private var duringRequest: () -> Unit = {}

    private lateinit var db: TodoDatabase
    private lateinit var repository: TaskRepository
    private lateinit var contexts: ContextRepository
    private lateinit var client: SyncClient

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java)
            .addCallback(SyncTracking).allowMainThreadQueries().build()
        val reminders = ReminderScheduler(context, context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        repository = TaskRepository(db.taskDao(), reminders, db.taskContextDao())
        contexts = ContextRepository(db.taskContextDao())
        client = SyncClient(context, db, reminders) { _, request ->
            duringRequest().also { duringRequest = {} }
            store.sync(request)
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `local tasks, contexts, and assignments reach the server`() = runBlocking {
        val ctxId = contexts.createContext(TaskContext(name = "Work", type = ContextType.TIME))
        contexts.addTimeWindow(ContextTimeWindow(contextId = ctxId, windowStartMinute = 540, windowEndMinute = 1020))
        val taskId = repository.createTask(Task(title = "Report"))
        contexts.setTaskContexts(taskId, setOf(ctxId))

        assertEquals("pushing only echoes our own rows back", 0, client.sync(config))

        assertEquals("Report", server.get(taskId)!!.title)
        assertEquals(setOf(ctxId), store.get(TASKS, taskId)!!.contextIds())
        assertEquals(1, store.get(CONTEXTS, ctxId)!!.timeWindows().size)
        assertFalse(client.hasDirty())
    }

    @Test
    fun `offline phone rename and bot star on the same task both survive on both sides`() = runBlocking {
        val id = repository.createTask(Task(title = "Taxes"))
        client.sync(config)

        repository.updateTask(repository.getTask(id)!!.copy(title = "File taxes"))
        server.setStarred(id, true)
        client.sync(config)

        val local = repository.getTask(id)!!
        assertEquals("File taxes", local.title)
        assertTrue(local.isStarred)
        assertEquals(local, server.get(id))
    }

    @Test
    fun `deletes propagate both ways and server restore brings the task back`() = runBlocking {
        val kept = repository.createTask(Task(title = "Deleted from Discord"))
        val gone = repository.createTask(Task(title = "Deleted on phone"))
        client.sync(config)

        repository.deleteTask(repository.getTask(gone)!!)
        server.delete(kept)
        client.sync(config)
        assertNull(repository.getTask(kept))
        assertNull(server.get(gone))

        server.restore(kept)
        client.sync(config)
        assertEquals("Deleted from Discord", repository.getTask(kept)!!.title)
    }

    @Test
    fun `server-created tasks arrive and a second sync with no changes applies nothing`() = runBlocking {
        val created = server.create(Task(title = "From Discord"))

        assertEquals(1, client.sync(config))
        assertEquals("From Discord", repository.getTask(created.id)!!.title)
        assertEquals(0, client.sync(config))
    }

    @Test
    fun `an edit made while a sync is in flight is kept and pushed next time`() = runBlocking {
        val id = repository.createTask(Task(title = "Draft"))
        client.sync(config)

        repository.updateTask(repository.getTask(id)!!.copy(title = "Pushed"))
        server.setStarred(id, true)
        duringRequest = { runBlocking { repository.updateTask(repository.getTask(id)!!.copy(title = "Edited mid-flight")) } }
        client.sync(config)
        assertEquals("Edited mid-flight", repository.getTask(id)!!.title)

        client.sync(config)
        assertEquals("Edited mid-flight", server.get(id)!!.title)
        assertTrue(repository.getTask(id)!!.isStarred)
        assertFalse(client.hasDirty())
    }
}
