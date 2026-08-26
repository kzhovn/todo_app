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

        // The spawned task inherits dueDate and reminderOffsetMinutes from the original.
        // Since RecurrenceEngine.nextInstance() copies the original task and only changes
        // id, startDate, isComplete, and completedAt, both fields are preserved.
        assertEquals(dueDate, spawned.dueDate)
        val nextAlarm = Shadows.shadowOf(alarmManager).peekNextScheduledAlarm()
        assertEquals(dueDate - 30 * 60_000L, nextAlarm?.triggerAtMs)
    }

    @Test
    fun `spawned recurring task with past due date does not get an alarm`() = runBlocking {
        val pastDueDate = now - TimeUnit.DAYS.toMillis(1)
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

        // The spawned task still exists but should not have an alarm scheduled
        // since its inherited dueDate is in the past. This prevents spurious
        // notifications from firing immediately upon task completion.
        assertEquals(pastDueDate, spawned.dueDate)
        assertNull(Shadows.shadowOf(alarmManager).peekNextScheduledAlarm())
    }
}
