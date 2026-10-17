package com.kzhovn.todoapp.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kzhovn.todoapp.R
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.isTimedWait
import com.kzhovn.todoapp.repository.stepAfter
import com.kzhovn.todoapp.ui.TaskEditActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1)
        val title = intent.getStringExtra(EXTRA_TASK_TITLE) ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // A workflow's wait is read at the time it rings, for what comes next (and its own buttons' answers).
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = (context.applicationContext as TodoApp).repository
                val now = System.currentTimeMillis()
                when (intent.action) {
                    // Done: the wait's over and the step after it is done (Switch to dryer).
                    ACTION_DONE -> {
                        manager.cancel(taskId.toString(), 0)
                        repository.markComplete(taskId, now)
                        intent.getLongExtra(EXTRA_NEXT_ID, -1).takeIf { it >= 0 }?.let { repository.markComplete(it, now) }
                    }
                    ACTION_MORE_WAIT -> { manager.cancel(taskId.toString(), 0); repository.moreWait(taskId, now) }
                    else -> {
                        val all = repository.getAllTasks()
                        val wait = all.firstOrNull { it.id == taskId }?.takeIf { it.isTimedWait }
                        if (wait == null) notify(context, manager, taskId, title, null)
                        else if (!wait.isComplete) notify(context, manager, taskId, Labels.waitDone(wait.title), wait to all)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    // `wait`: a workflow's wait with every task, for its line ("Laundry · next: Switch to dryer") and buttons.
    private fun notify(context: Context, manager: NotificationManager, taskId: Long, title: String, wait: Pair<Task, List<Task>>?) {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Task Reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        // Opens the task. The data Uri (taskDeepLinkUri) keeps each task's intent distinct, so the request
        // code can be a constant.
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, TaskEditActivity::class.java).apply {
                data = taskDeepLinkUri(taskId)
                putExtra(TaskEditActivity.EXTRA_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
        wait?.let { (w, all) ->
            val next = stepAfter(w, all)
            builder.setContentText(listOfNotNull(all.firstOrNull { it.id == w.parentId }?.title, next?.let { Labels.nextStep(it.title) }).joinToString(" · "))
            if (next != null) builder.addAction(0, "Done", action(context, ACTION_DONE, taskId) { putExtra(EXTRA_NEXT_ID, next.id) })
            builder.addAction(0, Labels.MORE_WAIT, action(context, ACTION_MORE_WAIT, taskId) {})
        }
        // The full Long id as the tag (the Int id would truncate it), so two tasks never collide.
        manager.notify(taskId.toString(), 0, builder.build())
    }

    private fun action(context: Context, action: String, taskId: Long, extras: Intent.() -> Unit): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(action).setData(taskDeepLinkUri(taskId))
            .putExtra(EXTRA_TASK_ID, taskId).putExtra(EXTRA_TASK_TITLE, "").apply(extras),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"
        const val CHANNEL_ID = "task_reminders"
        private const val EXTRA_NEXT_ID = "next_id"
        private const val ACTION_DONE = "com.kzhovn.todoapp.WAIT_DONE"
        private const val ACTION_MORE_WAIT = "com.kzhovn.todoapp.WAIT_MORE"
    }
}
