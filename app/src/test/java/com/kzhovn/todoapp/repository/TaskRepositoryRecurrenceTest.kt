package com.kzhovn.todoapp.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.RecurrenceType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TodoDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TaskRepositoryRecurrenceTest {
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
        ))
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
}
