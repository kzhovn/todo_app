package com.kzhovn.todoapp.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.kzhovn.todoapp.data.Task

class ReminderScheduler(private val context: Context, private val alarmManager: AlarmManager) {

    fun schedule(task: Task) {
        val dueDate = task.dueDate
        val offsetMinutes = task.reminderOffsetMinutes
        if (dueDate == null || offsetMinutes == null) {
            cancel(task)
            return
        }
        val triggerAt = dueDate - offsetMinutes * 60_000L
        // Inexact: fires within a few minutes of triggerAt, not to-the-second. Avoids
        // SCHEDULE_EXACT_ALARM, which needs a manifest permission plus a user grant on API 33+ —
        // not worth it for a todo reminder.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntentFor(task))
    }

    fun cancel(task: Task) {
        alarmManager.cancel(pendingIntentFor(task))
    }

    private fun pendingIntentFor(task: Task): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TASK_ID, task.id)
            putExtra(ReminderReceiver.EXTRA_TASK_TITLE, task.title)
        }
        return PendingIntent.getBroadcast(
            context, task.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
