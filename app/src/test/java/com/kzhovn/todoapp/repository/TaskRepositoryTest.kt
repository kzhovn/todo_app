package com.kzhovn.todoapp.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.TodoDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskRepositoryTest {
    private lateinit var db: TodoDatabase
    private lateinit var repository: TaskRepository
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), TodoDatabase::class.java
        ).allowMainThreadQueries().build()
        val alarmManager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        repository = TaskRepository(db.taskDao(), com.kzhovn.todoapp.notifications.ReminderScheduler(
            ApplicationProvider.getApplicationContext(), alarmManager
        ), db.taskContextDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `reparent moves a task under a new folder`() = runBlocking {
        val folderId = repository.createTask(Task(type = TaskType.FOLDER, title = "Work"))
        val taskId = repository.createTask(Task(title = "Ship report"))

        repository.reparent(taskId, folderId)

        assertEquals(folderId, repository.getTask(taskId)?.parentId)
    }

    @Test
    fun `toggleStar flips the starred flag`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Important"))
        repository.toggleStar(taskId)
        assertTrue(repository.getTask(taskId)!!.isStarred)
        repository.toggleStar(taskId)
        assertTrue(!repository.getTask(taskId)!!.isStarred)
    }

    @Test
    fun `snooze pushes the start date forward so the task drops out of active`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Later"))
        assertEquals(1, repository.getActiveTasks(now, 600, ContextTimeWindow.ALL_DAYS).size)

        repository.snooze(taskId, durationMillis = 60_000, now = now)

        assertTrue(repository.getActiveTasks(now, 600, ContextTimeWindow.ALL_DAYS).isEmpty())
    }

    @Test
    fun `markComplete sets completion fields`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Finish plan"))
        repository.markComplete(taskId, now)
        val task = repository.getTask(taskId)!!
        assertTrue(task.isComplete)
        assertEquals(now, task.completedAt)
    }

    @Test
    fun `toggleComplete marks an incomplete task complete`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Ship report"))
        repository.toggleComplete(taskId, now)
        val task = repository.getTask(taskId)!!
        assertTrue(task.isComplete)
        assertEquals(now, task.completedAt)
    }

    @Test
    fun `toggleComplete reopens a completed task`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Ship report"))
        repository.toggleComplete(taskId, now)
        repository.toggleComplete(taskId, now + 1000)
        val task = repository.getTask(taskId)!!
        assertTrue(!task.isComplete)
        assertNull(task.completedAt)
    }

    @Test
    fun `deleteTask removes it and undoDelete restores it`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Oops"))
        val task = repository.getTask(taskId)!!

        repository.deleteTask(task)
        assertNull(repository.getTask(taskId))

        repository.undoDelete(listOf(task))
        assertEquals("Oops", repository.getTask(taskId)?.title)
    }

    @Test
    fun `deleteTask populates lastDeleted with the task and its descendants`() = runBlocking {
        val folderId = repository.createTask(Task(type = TaskType.FOLDER, title = "Work"))
        val childId = repository.createTask(Task(title = "Ship report", parentId = folderId))
        val folder = repository.getTask(folderId)!!
        val child = repository.getTask(childId)!!

        repository.deleteTask(folder)

        val deleted = repository.lastDeleted.value
        assertEquals(setOf(folderId, childId), deleted?.map { it.id }?.toSet())
    }

    @Test
    fun `undoDelete restores a deleted folder and its children together`() = runBlocking {
        val folderId = repository.createTask(Task(type = TaskType.FOLDER, title = "Work"))
        val childId = repository.createTask(Task(title = "Ship report", parentId = folderId))
        val folder = repository.getTask(folderId)!!
        val child = repository.getTask(childId)!!

        repository.deleteTask(folder)
        assertNull(repository.getTask(folderId))
        assertNull(repository.getTask(childId))

        repository.undoDelete(listOf(folder, child))

        assertEquals("Work", repository.getTask(folderId)?.title)
        assertEquals(folderId, repository.getTask(childId)?.parentId)
    }

    @Test
    fun `clearLastDeleted resets lastDeleted to null`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Oops"))
        val task = repository.getTask(taskId)!!
        repository.deleteTask(task)
        assertNotNull(repository.lastDeleted.value)

        repository.clearLastDeleted()

        assertNull(repository.lastDeleted.value)
    }

    @Test
    fun `deleteTask cascades to a folder's direct children`() = runBlocking {
        val folderId = repository.createTask(Task(type = TaskType.FOLDER, title = "Work"))
        val childId = repository.createTask(Task(title = "Ship report", parentId = folderId))
        val folder = repository.getTask(folderId)!!

        repository.deleteTask(folder)

        assertNull(repository.getTask(folderId))
        assertNull(repository.getTask(childId))
    }

    @Test
    fun `deleteTask cascades through nested descendants`() = runBlocking {
        val workId = repository.createTask(Task(type = TaskType.FOLDER, title = "Work"))
        val projectId = repository.createTask(Task(type = TaskType.FOLDER, title = "Project A", parentId = workId))
        val taskId = repository.createTask(Task(title = "Write spec", parentId = projectId))
        val work = repository.getTask(workId)!!

        repository.deleteTask(work)

        assertNull(repository.getTask(workId))
        assertNull(repository.getTask(projectId))
        assertNull(repository.getTask(taskId))
    }

    @Test
    fun `deleteTask still deletes an empty folder`() = runBlocking {
        val folderId = repository.createTask(Task(type = TaskType.FOLDER, title = "Empty"))
        val folder = repository.getTask(folderId)!!

        repository.deleteTask(folder)

        assertNull(repository.getTask(folderId))
    }

    @Test
    fun `countDescendants counts nested descendants`() = runBlocking {
        val workId = repository.createTask(Task(type = TaskType.FOLDER, title = "Work"))
        val projectId = repository.createTask(Task(type = TaskType.FOLDER, title = "Project A", parentId = workId))
        repository.createTask(Task(title = "Write spec", parentId = projectId))
        repository.createTask(Task(title = "Unrelated top-level task"))

        assertEquals(2, repository.countDescendants(workId))
    }

    @Test
    fun `setDependencies assigns and reads back dependencies`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Ship report"))
        val depA = repository.createTask(Task(title = "Gather data"))
        val depB = repository.createTask(Task(title = "Get approval"))

        repository.setDependencies(taskId, setOf(depA, depB))

        assertEquals(setOf(depA, depB), repository.getDependencyIds(taskId))
    }

    @Test
    fun `setDependencies removes an unassigned dependency`() = runBlocking {
        val taskId = repository.createTask(Task(title = "Ship report"))
        val depA = repository.createTask(Task(title = "Gather data"))
        val depB = repository.createTask(Task(title = "Get approval"))
        repository.setDependencies(taskId, setOf(depA, depB))

        repository.setDependencies(taskId, setOf(depA))

        assertEquals(setOf(depA), repository.getDependencyIds(taskId))
    }

    @Test
    fun `search excludes completed tasks by default`() = runBlocking {
        val id = repository.createTask(Task(title = "Buy milk"))
        repository.markComplete(id, now)
        repository.createTask(Task(title = "Buy eggs"))

        val results = repository.search("buy")

        assertEquals(listOf("Buy eggs"), results.map { it.title })
    }

    @Test
    fun `search includes completed tasks when requested`() = runBlocking {
        val id = repository.createTask(Task(title = "Buy milk"))
        repository.markComplete(id, now)

        val results = repository.search("buy", SearchFilters(includeCompleted = true))

        assertEquals(listOf("Buy milk"), results.map { it.title })
    }

    @Test
    fun `search filters by folder`() = runBlocking {
        val folderId = repository.createTask(Task(type = TaskType.FOLDER, title = "Work"))
        repository.createTask(Task(title = "Ship report", parentId = folderId))
        repository.createTask(Task(title = "Ship kayak"))

        val results = repository.search("ship", SearchFilters(folderId = folderId))

        assertEquals(listOf("Ship report"), results.map { it.title })
    }

    @Test
    fun `search filters by starred`() = runBlocking {
        repository.createTask(Task(title = "Starred task", isStarred = true))
        repository.createTask(Task(title = "Plain task"))

        val results = repository.search("task", SearchFilters(starredOnly = true))

        assertEquals(listOf("Starred task"), results.map { it.title })
    }

    @Test
    fun `countActiveDescendants counts only incomplete descendants`() = runBlocking {
        val parentId = repository.createTask(Task(title = "Plan trip"))
        val doneId = repository.createTask(Task(title = "Book flight", parentId = parentId))
        repository.createTask(Task(title = "Pack bags", parentId = parentId))
        repository.markComplete(doneId, now)

        assertEquals(1, repository.countActiveDescendants(parentId))
    }

    @Test
    fun `completeWithDescendants completes every active descendant`() = runBlocking {
        val parentId = repository.createTask(Task(title = "Plan trip"))
        val childId = repository.createTask(Task(title = "Book flight", parentId = parentId))

        repository.completeWithDescendants(parentId, now)

        assertTrue(repository.getTask(parentId)!!.isComplete)
        assertTrue(repository.getTask(childId)!!.isComplete)
    }

    @Test
    fun `promoteChildrenToTopLevel detaches direct children`() = runBlocking {
        val parentId = repository.createTask(Task(title = "Plan trip"))
        val childId = repository.createTask(Task(title = "Book flight", parentId = parentId))

        repository.promoteChildrenToTopLevel(parentId)

        assertNull(repository.getTask(childId)!!.parentId)
    }

    @Test
    fun `minuteOfDay converts a timestamp to minutes since midnight`() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.AUGUST, 26, 14, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        assertEquals(14 * 60 + 30, minuteOfDay(cal.timeInMillis))
    }

    @Test
    fun `dayOfWeekMask sets exactly one bit matching Calendar's DAY_OF_WEEK`() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.AUGUST, 26, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val expectedBit = 1 shl (cal.get(java.util.Calendar.DAY_OF_WEEK) - 1)
        assertEquals(expectedBit, dayOfWeekMask(cal.timeInMillis))
    }
}
