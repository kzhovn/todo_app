package com.kzhovn.todoapp.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import com.kzhovn.todoapp.R
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.ui.TaskEditActivity
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// "Doing" mode: one task pinned as an ongoing notification, completable from the lock screen.
// The pinned id is persisted so the notification can be restored after a reboot, and refresh()
// drops it once the task is completed or deleted anywhere (app, widget, sync, Discord).
object PinnedTask {
    private const val CHANNEL_ID = "doing"
    private const val NOTIFICATION_ID = 7001
    const val ACTION_COMPLETE = "com.kzhovn.todoapp.PINNED_COMPLETE"
    const val ACTION_UNPIN = "com.kzhovn.todoapp.PINNED_UNPIN"

    private fun prefs(context: Context) = context.getSharedPreferences("pinned", Context.MODE_PRIVATE)

    fun pinnedId(context: Context): Long? = prefs(context).getLong("taskId", 0L).takeIf { it != 0L }

    fun pin(context: Context, task: Task) {
        prefs(context).edit().putLong("taskId", task.id).apply()
        show(context, task)
    }

    fun unpin(context: Context) {
        prefs(context).edit().remove("taskId").apply()
        manager(context).cancel(NOTIFICATION_ID)
    }

    // Re-shows the pinned task (new title, after reboot) or unpins it if it's no longer open.
    suspend fun refresh(context: Context) {
        val id = pinnedId(context) ?: return
        val task = (context.applicationContext as TodoApp).repository.getTask(id)
        if (task == null || task.isComplete) unpin(context) else show(context, task)
    }

    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun show(context: Context, task: Task) {
        val manager = manager(context)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Doing (pinned task)", NotificationManager.IMPORTANCE_LOW).apply {
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title)
            .setContentText("Doing now")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            // Broadcast actions (not activities) run without unlocking, so Complete works on the lock screen.
            .addAction(0, "Complete", action(ACTION_COMPLETE, 1))
            .addAction(0, "Unpin", action(ACTION_UNPIN, 2))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

class PinnedTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TodoApp
        when (intent.action) {
            PinnedTask.ACTION_UNPIN -> PinnedTask.unpin(context)
            PinnedTask.ACTION_COMPLETE -> {
                val taskId = intent.getLongExtra(TaskEditActivity.EXTRA_TASK_ID, 0L)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Like the widget: no room for the subtask dialog, so subtasks complete too.
                        app.repository.completeWithDescendants(taskId, System.currentTimeMillis())
                        PinnedTask.unpin(context)
                        TodoWidget().updateAll(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
