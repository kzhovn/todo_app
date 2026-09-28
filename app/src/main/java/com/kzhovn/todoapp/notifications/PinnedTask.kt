package com.kzhovn.todoapp.notifications

import com.kzhovn.todoapp.focus.FocusActivity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kzhovn.todoapp.R
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.ui.TaskEditActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// "Doing" mode: one task pinned as an ongoing notification, completable from the lock screen. The pin
// is shared by every device (Task.pinnedAt), and refresh() runs on every data change, so the
// notification follows pins, completions and deletions from anywhere (app, widget, sync, web).
object PinnedTask {
    // Channel importance can't change once created, so raising it meant a new channel; the old,
    // low-importance "doing" one is deleted. Low importance counts as "silent", which Pixels hide
    // from the lock screen.
    private const val CHANNEL_ID = "doing_pinned"
    private const val OLD_CHANNEL_ID = "doing"
    private const val NOTIFICATION_ID = 7001
    const val ACTION_COMPLETE = "com.kzhovn.todoapp.PINNED_COMPLETE"
    const val ACTION_UNPIN = "com.kzhovn.todoapp.PINNED_UNPIN"

    private fun repository(context: Context) = (context.applicationContext as TodoApp).repository

    suspend fun pinnedId(context: Context): Long? = repository(context).getPinnedTask()?.id

    suspend fun pin(context: Context, taskId: Long) {
        repository(context).pin(taskId, System.currentTimeMillis())
        refresh(context)
    }

    suspend fun unpin(context: Context) {
        repository(context).unpin()
        refresh(context)
    }

    // Shows whatever is pinned now (a new title, a pin from another device, after a reboot), or
    // removes the notification when nothing is.
    suspend fun refresh(context: Context) {
        migrateLocalPin(context)
        val task = repository(context).getPinnedTask()
        if (task == null) {
            manager(context).cancel(NOTIFICATION_ID)
            shown = null
        } else if (shown != task.id to task.title) {
            show(context, task)
            shown = task.id to task.title
        }
    }

    // What the notification shows, so a refresh with nothing new (most data changes) posts nothing.
    @Volatile
    private var shown: Pair<Long, String>? = null

    // Before pins synced, the phone kept its own in preferences; moved onto the task once.
    private suspend fun migrateLocalPin(context: Context) {
        val prefs = context.getSharedPreferences("pinned", Context.MODE_PRIVATE)
        val id = prefs.getLong("taskId", 0L).takeIf { it != 0L } ?: return
        prefs.edit().remove("taskId").apply()
        if (repository(context).getTask(id)?.isComplete == false) repository(context).pin(id, System.currentTimeMillis())
    }

    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun show(context: Context, task: Task) {
        val manager = manager(context)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.deleteNotificationChannel(OLD_CHANNEL_ID)
            // High importance (shown on the lock screen and as a heads-up), but without sound or
            // vibration: pinning is something you just did, not an alert.
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Doing (pinned task)", NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
        fun action(action: String, requestCode: Int) = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, PinnedTaskReceiver::class.java).setAction(action).putExtra(TaskEditActivity.EXTRA_TASK_ID, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_TASK_ID, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val focus = PendingIntent.getActivity(
            context, 3,
            Intent(context, FocusActivity::class.java).putExtra(FocusActivity.EXTRA_TASK_ID, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title)
            .setContentText("Doing now")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Doing now"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MAX) // pre-Oreo stand-in for channel importance
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Android 16's "promoted ongoing" (Live Update): pinned to the top of the lock screen and
            // a status-bar chip. Set by key since compileSdk predates the constant; older versions
            // ignore it. Needs POST_PROMOTED_NOTIFICATIONS in the manifest.
            .addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .setContentIntent(open)
            // Broadcast actions (not activities) run without unlocking, so Complete works on the lock screen.
            .addAction(0, "Complete", action(ACTION_COMPLETE, 1))
            .addAction(0, "Unpin", action(ACTION_UNPIN, 2))
            .addAction(0, "Focus", focus)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

class PinnedTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TodoApp
        val taskId = intent.getLongExtra(TaskEditActivity.EXTRA_TASK_ID, 0L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    PinnedTask.ACTION_UNPIN -> PinnedTask.unpin(context)
                    // Like the widget: no room for the subtask dialog, so subtasks complete too. A
                    // completed task is no longer pinned.
                    PinnedTask.ACTION_COMPLETE -> {
                        app.repository.completeWithDescendants(taskId, System.currentTimeMillis())
                        PinnedTask.refresh(context)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
