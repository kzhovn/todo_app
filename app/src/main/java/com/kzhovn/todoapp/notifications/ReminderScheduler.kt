package com.kzhovn.todoapp.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kzhovn.todoapp.AppSettings
import com.kzhovn.todoapp.data.ReminderKind
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.reminderTimes

// Shared by ReminderScheduler.pendingIntentFor and ReminderReceiver's content Intent — both need
// the same Uri-identity scheme so PendingIntent equality is keyed off the full Long id, not a
// truncated Int request code. Keeping the format in one place means it can't drift out of sync
// between the two.
fun taskDeepLinkUri(id: Long): Uri = Uri.parse("todoapp://task/$id")

class ReminderScheduler(private val context: Context, private val alarmManager: AlarmManager) {

    // One alarm per kind of reminder (when it starts, before due, at a time); see reminderTimes.
    fun schedule(task: Task, now: Long = System.currentTimeMillis()) {
        // Saving a completed task (an edit, a bulk edit, an auto-save) mustn't bring its reminders back.
        val times = if (task.isComplete) emptyMap() else reminderTimes(task, AppSettings.reminderHour(context))
        for (kind in ReminderKind.entries) {
            val triggerAt = times[kind]
            // A past time would fire straight away, on save.
            if (triggerAt == null || triggerAt <= now) alarmManager.cancel(pendingIntentFor(task, kind))
            // Inexact: fires within a few minutes of triggerAt, not to-the-second. Avoids
            // SCHEDULE_EXACT_ALARM, which needs a manifest permission plus a user grant on API 33+ —
            // not worth it for a todo reminder.
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntentFor(task, kind))
        }
    }

    fun cancel(task: Task) = ReminderKind.entries.forEach { alarmManager.cancel(pendingIntentFor(task, it)) }

    // The before-due alarm keeps the plain task Uri it always had, so alarms set by older versions still match.
    private fun pendingIntentFor(task: Task, kind: ReminderKind): PendingIntent {
        // PendingIntent equality (for FLAG_UPDATE_CURRENT) is keyed off the whole Intent, not just
        // the request code — encoding the full Long id into the Intent's data Uri means two tasks
        // can never collide just because their ids happen to share the same low 32 bits, the way a
        // truncated Int request code alone would let them.
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            data = taskDeepLinkUri(task.id).let { if (kind == ReminderKind.DUE) it else it.buildUpon().fragment(kind.name.lowercase()).build() }
            putExtra(ReminderReceiver.EXTRA_TASK_ID, task.id)
            putExtra(ReminderReceiver.EXTRA_TASK_TITLE, task.title)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
