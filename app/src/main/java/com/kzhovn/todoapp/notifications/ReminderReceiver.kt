package com.kzhovn.todoapp.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kzhovn.todoapp.MainActivity
import com.kzhovn.todoapp.R

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1)
        val title = intent.getStringExtra(EXTRA_TASK_TITLE) ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Task Reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        // Same Uri-identity approach as ReminderScheduler.pendingIntentFor: the content Intent's
        // data carries the full Long id, so the request code can be a constant.
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                data = taskDeepLinkUri(taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        // The (tag, id) pair is the actual notification identity — using the full Long id as the
        // tag (rather than truncating it into the Int id) means two tasks can never collide here
        // either.
        manager.notify(taskId.toString(), 0, notification)
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"
        const val CHANNEL_ID = "task_reminders"
    }
}
