package com.kzhovn.todoapp.notifications

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {

    @Test
    fun `schedules an exact alarm at the task due date`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduler = ReminderScheduler(context, alarmManager)

        scheduler.schedule(Task(id = 1, title = "Pay rent", dueDate = 1_000_000L))

        val nextAlarm = Shadows.shadowOf(alarmManager).peekNextScheduledAlarm()
        assertEquals(1_000_000L, nextAlarm?.triggerAtMs)
    }

    @Test
    fun `task with no due date is not scheduled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduler = ReminderScheduler(context, alarmManager)

        scheduler.schedule(Task(id = 1, title = "No due date"))

        assertNull(Shadows.shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `cancel removes a scheduled alarm`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduler = ReminderScheduler(context, alarmManager)
        val task = Task(id = 1, title = "Pay rent", dueDate = 1_000_000L)
        scheduler.schedule(task)

        scheduler.cancel(task)

        assertNull(Shadows.shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `clearing a due date cancels the alarm`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduler = ReminderScheduler(context, alarmManager)
        val task = Task(id = 1, title = "Pay rent", dueDate = 1_000_000L)
        scheduler.schedule(task)

        scheduler.schedule(task.copy(dueDate = null))

        assertNull(Shadows.shadowOf(alarmManager).peekNextScheduledAlarm())
    }
}
