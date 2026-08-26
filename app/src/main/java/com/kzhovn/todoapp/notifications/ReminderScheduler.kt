package com.kzhovn.todoapp.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        // The date picker is date-only, so "due today" is already a past timestamp for most of the
        // day; setAndAllowWhileIdle would fire such an alarm immediately, on save.
        if (triggerAt <= System.currentTimeMillis()) {
            cancel(task)
            return
        }
        // Inexact: fires within a few minutes of triggerAt, not to-the-second. Avoids
        // SCHEDULE_EXACT_ALARM, which needs a manifest permission plus a user grant on API 33+ —
        // not worth it for a todo reminder.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntentFor(task))
    }

    fun cancel(task: Task) {
        alarmManager.cancel(pendingIntentFor(task))
    }

    private fun pendingIntentFor(task: Task): PendingIntent {
        // PendingIntent equality (for FLAG_UPDATE_CURRENT) is keyed off the whole Intent, not just
        // the request code — encoding the full Long id into the Intent's data Uri means two tasks
        // can never collide just because their ids happen to share the same low 32 bits, the way a
        // truncated Int request code alone would let them.
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            data = Uri.parse("todoapp://task/${task.id}")
            putExtra(ReminderReceiver.EXTRA_TASK_ID, task.id)
            putExtra(ReminderReceiver.EXTRA_TASK_TITLE, task.title)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
