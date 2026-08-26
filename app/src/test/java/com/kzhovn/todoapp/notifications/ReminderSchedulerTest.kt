package com.kzhovn.todoapp.notifications

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.Task
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val alarmManager = context.getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager
    private val scheduler = ReminderScheduler(context, alarmManager)

    // schedule() drops any trigger time already in the past, so the happy-path fixtures have to be
    // relative to the real clock rather than a hardcoded timestamp.
    private val futureDue = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L

    @Test
    fun `does not schedule when reminderOffsetMinutes is null even with a due date`() {
        val task = Task(id = 1, title = "No reminder", dueDate = futureDue, reminderOffsetMinutes = null)
        scheduler.schedule(task)
        val shadow: ShadowAlarmManager = shadowOf(alarmManager)
        assertNull(shadow.nextScheduledAlarm)
    }

    @Test
    fun `does not schedule when there is no due date even with an offset set`() {
        val task = Task(id = 1, title = "No due date", dueDate = null, reminderOffsetMinutes = 30)
        scheduler.schedule(task)
        val shadow: ShadowAlarmManager = shadowOf(alarmManager)
        assertNull(shadow.nextScheduledAlarm)
    }

    @Test
    fun `schedules at dueDate minus the offset when both are set`() {
        val dueDate = futureDue
        val task = Task(id = 1, title = "Remind me", dueDate = dueDate, reminderOffsetMinutes = 30)
        scheduler.schedule(task)
        val shadow: ShadowAlarmManager = shadowOf(alarmManager)
        val scheduled = shadow.nextScheduledAlarm
        assert(scheduled != null && scheduled.triggerAtTime == dueDate - 30 * 60_000L) {
            "expected trigger at ${dueDate - 30 * 60_000L}, got ${scheduled?.triggerAtTime}"
        }
    }

    @Test
    fun `offset 0 schedules exactly at due date`() {
        val dueDate = futureDue
        val task = Task(id = 1, title = "At due time", dueDate = dueDate, reminderOffsetMinutes = 0)
        scheduler.schedule(task)
        val shadow: ShadowAlarmManager = shadowOf(alarmManager)
        assert(shadow.nextScheduledAlarm?.triggerAtTime == dueDate)
    }

    // "Due today" is midnight today, so an offset pushes triggerAt into the past — an alarm set
    // then would fire the moment the user hits Save.
    @Test
    fun `does not schedule when the offset puts the trigger time in the past`() {
        val dueDate = System.currentTimeMillis() - 60 * 60 * 1000L
        val task = Task(id = 1, title = "Due earlier today", dueDate = dueDate, reminderOffsetMinutes = 30)
        scheduler.schedule(task)
        val shadow: ShadowAlarmManager = shadowOf(alarmManager)
        assertNull(shadow.nextScheduledAlarm)
    }
}
