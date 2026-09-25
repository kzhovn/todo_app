package com.kzhovn.todoapp.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.RecurrenceType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TodoDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TaskRepositoryRecurrenceTest {
    private lateinit var db: TodoDatabase
    private lateinit var repository: TaskRepository
    private lateinit var alarmManager: android.app.AlarmManager
    // Real-clock-relative: ReminderScheduler drops trigger times already in the past, so a
    // hardcoded "now" would make the future-due-date case unschedulable.
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), TodoDatabase::class.java
        ).allowMainThreadQueries().build()
        alarmManager = ApplicationProvider.getApplicationContext<android.content.Context>()
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
    fun `the next instance keeps contexts and re-creates subtasks, and undo removes them all`() = runBlocking {
        val contexts = ContextRepository(db.taskContextDao())
        val home = contexts.createContext(com.kzhovn.todoapp.data.TaskContext(name = "Home", type = com.kzhovn.todoapp.data.ContextType.PLACE))
        val parent = repository.createTask(Task(title = "Review", recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "30"))
        contexts.setTaskContexts(parent, setOf(home))
        repository.createTask(Task(title = "Move money", parentId = parent))

        repository.completeWithDescendants(parent, now)

        val next = repository.getAllTasks().single { it.title == "Review" && !it.isComplete }
        assertEquals(setOf(home), contexts.getContextsForTask(next.id).map { it.id }.toSet())
        val subCopy = repository.getAllTasks().single { it.parentId == next.id }
        assertEquals("Move money", subCopy.title)
        assertTrue(!subCopy.isComplete)

        repository.toggleComplete(parent, now)
        assertEquals(2, repository.getAllTasks().size)
    }

    @Test
    fun `un-completing a recurring task removes its untouched spawned instance`() = runBlocking {
        val taskId = repository.createTask(
            Task(title = "Take out trash", recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "7")
        )
        repository.markComplete(taskId, now)

        repository.toggleComplete(taskId, now)

        assertEquals(listOf(taskId), repository.getAllTasks().map { it.id })
    }

    @Test
    fun `completing a recurring task spawns the next instance`() = runBlocking {
        val taskId = repository.createTask(
            Task(title = "Take out trash", recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "7")
        )

        repository.markComplete(taskId, now)

        val all = repository.getAllTasks()
        assertEquals(2, all.size)
        val spawned = all.first { it.id != taskId }
        assertTrue(!spawned.isComplete)
        assertEquals(now + TimeUnit.DAYS.toMillis(7), spawned.startDate)
    }

    @Test
    fun `spawned recurring task with due date and reminder has an alarm scheduled`() = runBlocking {
        val dueDate = now + TimeUnit.DAYS.toMillis(1)
        val taskId = repository.createTask(
            Task(
                title = "Recurring task",
                dueDate = dueDate,
                reminderOffsetMinutes = 30,
                recurrenceType = RecurrenceType.AFTER_COMPLETION,
                recurrenceRule = "7"
            )
        )

        repository.markComplete(taskId, now)

        val all = repository.getAllTasks()
        assertEquals(2, all.size)
        val spawned = all.first { it.id != taskId }
        assertTrue(!spawned.isComplete)

        // The spawned task's dueDate shifts forward by the same 7 days its startDate did
        // (RecurrenceEngine.nextInstance keeps the start-to-due gap constant); its
        // reminderOffsetMinutes is preserved unchanged since that's not part of the shift.
        val expectedSpawnedDueDate = dueDate + TimeUnit.DAYS.toMillis(7)
        assertEquals(expectedSpawnedDueDate, spawned.dueDate)
        val nextAlarm = Shadows.shadowOf(alarmManager).peekNextScheduledAlarm()
        assertEquals(expectedSpawnedDueDate - 30 * 60_000L, nextAlarm?.triggerAtMs)
    }

    @Test
    fun `spawned recurring task with past due date does not get an alarm`() = runBlocking {
        val pastDueDate = now - TimeUnit.DAYS.toMillis(10) // still in the past after the 7-day recurrence shift
        val taskId = repository.createTask(
            Task(
                title = "Overdue recurring task",
                dueDate = pastDueDate,
                reminderOffsetMinutes = 30,
                recurrenceType = RecurrenceType.AFTER_COMPLETION,
                recurrenceRule = "7"
            )
        )

        repository.markComplete(taskId, now)

        val all = repository.getAllTasks()
        assertEquals(2, all.size)
        val spawned = all.first { it.id != taskId }
        assertTrue(!spawned.isComplete)

        // The spawned task's dueDate shifts forward by 7 days (pastDueDate + 7 days = now - 3 days),
        // still in the past, so it correctly gets no alarm.
        assertEquals(pastDueDate + TimeUnit.DAYS.toMillis(7), spawned.dueDate)
        assertNull(Shadows.shadowOf(alarmManager).peekNextScheduledAlarm())
    }
}
