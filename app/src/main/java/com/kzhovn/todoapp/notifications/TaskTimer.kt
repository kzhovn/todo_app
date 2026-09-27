package com.kzhovn.todoapp.notifications

import com.kzhovn.todoapp.data.countdown
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kzhovn.todoapp.R
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// The countdown for a timed task: one at a time, pausable. Shown as an ongoing notification with the
// time left; an exact alarm at the end asks whether the task is done (TimerDoneActivity). State lives
// in prefs, so it outlives the app process, and in a StateFlow the play buttons watch.
object TaskTimer {
    data class State(val taskId: Long, val title: String, val endsAt: Long?, val remainingMillis: Long) {
        val isPaused get() = endsAt == null
        fun remaining(now: Long) = endsAt?.let { it - now } ?: remainingMillis
    }

    private const val RUNNING_CHANNEL = "timer_running"
    private const val DONE_CHANNEL = "timer_done"
    private const val NOTIFICATION_ID = 7002
    const val ACTION_PAUSE = "com.kzhovn.todoapp.TIMER_PAUSE"
    const val ACTION_RESUME = "com.kzhovn.todoapp.TIMER_RESUME"
    const val ACTION_STOP = "com.kzhovn.todoapp.TIMER_STOP"
    const val ACTION_FINISHED = "com.kzhovn.todoapp.TIMER_FINISHED"
    const val ACTION_DONE = "com.kzhovn.todoapp.TIMER_DONE"

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private fun prefs(context: Context) = context.getSharedPreferences("timer", Context.MODE_PRIVATE)

    // Reloads saved state (app start, reboot) and puts the notification and alarm back.
    fun restore(context: Context) {
        val p = prefs(context)
        val id = p.getLong("taskId", 0L).takeIf { it != 0L } ?: return
        val state = State(id, p.getString("title", "").orEmpty(), p.getLong("endsAt", 0L).takeIf { it != 0L }, p.getLong("remaining", 0L))
        _state.value = state
        if (!state.isPaused) scheduleEnd(context, state.endsAt!!)
        showRunning(context, state)
    }

    fun start(context: Context, task: Task, minutes: Int) = run(context, State(task.id, task.title, System.currentTimeMillis() + minutes * 60_000L, 0L))

    fun pause(context: Context) {
        val s = _state.value?.takeUnless { it.isPaused } ?: return
        cancelEnd(context)
        run(context, s.copy(endsAt = null, remainingMillis = s.remaining(System.currentTimeMillis()).coerceAtLeast(0)))
    }

    fun resume(context: Context) {
        val s = _state.value?.takeIf { it.isPaused } ?: return
        run(context, s.copy(endsAt = System.currentTimeMillis() + s.remainingMillis))
    }

    fun stop(context: Context) {
        cancelEnd(context)
        save(context, null)
        manager(context).cancel(NOTIFICATION_ID)
    }

    // The alarm went off: swap the countdown for "Time's up", which asks whether the task is done.
    fun finish(context: Context) {
        val s = _state.value ?: return
        save(context, null)
        ensureChannels(context)
        val ask = PendingIntent.getActivity(
            context, 10,
            Intent(context, TimerDoneActivity::class.java).putExtra(TimerDoneActivity.EXTRA_TASK_ID, s.taskId).putExtra(TimerDoneActivity.EXTRA_TITLE, s.title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Time's up: ${s.title}")
            .setContentText("Is it done?")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(ask)
            .addAction(0, "Done", broadcast(context, ACTION_DONE, s.taskId, 11))
            .addAction(0, "Not yet", ask)
            .build()
        manager(context).notify(NOTIFICATION_ID, notification)
    }

    fun clearNotification(context: Context) = manager(context).cancel(NOTIFICATION_ID)

    private fun run(context: Context, s: State) {
        save(context, s)
        if (!s.isPaused) scheduleEnd(context, s.endsAt!!)
        showRunning(context, s)
    }

    private fun save(context: Context, s: State?) {
        _state.value = s
        prefs(context).edit().apply {
            if (s == null) clear() else {
                putLong("taskId", s.taskId); putString("title", s.title)
                putLong("endsAt", s.endsAt ?: 0L); putLong("remaining", s.remainingMillis)
            }
        }.apply()
    }

    private fun showRunning(context: Context, s: State) {
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, RUNNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (s.isPaused) {
            builder.setContentText("Paused · ${countdown(s.remainingMillis)} left")
                .addAction(0, "Resume", broadcast(context, ACTION_RESUME, s.taskId, 1))
        } else {
            // The system draws the live countdown to endsAt.
            builder.setContentText("Timer").setUsesChronometer(true).setChronometerCountDown(true).setWhen(s.endsAt!!).setShowWhen(true)
                .addAction(0, "Pause", broadcast(context, ACTION_PAUSE, s.taskId, 2))
        }
        builder.addAction(0, "Stop", broadcast(context, ACTION_STOP, s.taskId, 3))
        manager(context).notify(NOTIFICATION_ID, builder.build())
    }

    // Exact, so the timer ends on time; USE_EXACT_ALARM (a timer app's permission) grants it on
    // Android 13+, and before that SCHEDULE_EXACT_ALARM, falling back to inexact if revoked.
    private fun scheduleEnd(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = broadcast(context, ACTION_FINISHED, 0L, 4)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    private fun cancelEnd(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(broadcast(context, ACTION_FINISHED, 0L, 4))

    private fun broadcast(context: Context, action: String, taskId: Long, requestCode: Int) = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, TaskTimerReceiver::class.java).setAction(action).putExtra(TimerDoneActivity.EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    // Running: prominent but silent, like the pinned task. Done: an alert, with sound.
    private fun ensureChannels(context: Context) {
        val manager = manager(context)
        if (manager.getNotificationChannel(RUNNING_CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(RUNNING_CHANNEL, "Timer (running)", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }
        if (manager.getNotificationChannel(DONE_CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Timer (time's up)", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            })
        }
    }
}

class TaskTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TaskTimer.ACTION_PAUSE -> TaskTimer.pause(context)
            TaskTimer.ACTION_RESUME -> TaskTimer.resume(context)
            TaskTimer.ACTION_STOP -> TaskTimer.stop(context)
            TaskTimer.ACTION_FINISHED -> TaskTimer.finish(context)
            TaskTimer.ACTION_DONE -> {
                val taskId = intent.getLongExtra(TimerDoneActivity.EXTRA_TASK_ID, 0L)
                TaskTimer.clearNotification(context)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Like the pinned task's Complete: no room for the subtask question here.
                        (context.applicationContext as TodoApp).repository.completeWithDescendants(taskId, System.currentTimeMillis())
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
