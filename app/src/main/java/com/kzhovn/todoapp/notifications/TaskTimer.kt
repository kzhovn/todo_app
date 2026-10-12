package com.kzhovn.todoapp.notifications

import com.kzhovn.todoapp.ui.CompleteAskActivity

import com.kzhovn.todoapp.data.Labels
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
import com.kzhovn.todoapp.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// The current task's timer. It lives on the task (CurrentTask), so every device shows the same
// countdown; its "Now" notification draws it (PinnedTask). This keeps the phone's side: `state` for the
// play buttons, an exact alarm at the end, and the "Time's up" alert.
object TaskTimer {
    data class State(val taskId: Long, val title: String, val endsAt: Long?, val remainingMillis: Long) {
        val isPaused get() = endsAt == null
        fun remaining(now: Long) = endsAt?.let { it - now } ?: remainingMillis
    }

    private const val DONE_CHANNEL = "timer_done"
    private const val NOTIFICATION_ID = 7002
    const val ACTION_PAUSE = "com.kzhovn.todoapp.TIMER_PAUSE"
    const val ACTION_RESUME = "com.kzhovn.todoapp.TIMER_RESUME"
    const val ACTION_FINISHED = "com.kzhovn.todoapp.TIMER_FINISHED"

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun repository(context: Context) = (context.applicationContext as TodoApp).repository

    // Each writes the task, then brings the notification, alarm and `state` up to date.
    private fun act(context: Context, block: suspend TaskRepository.(now: Long) -> Unit) = scope.launch {
        repository(context).block(System.currentTimeMillis())
        PinnedTask.refresh(context)
    }

    // Starting a timer pins its task (see CurrentTask).
    fun start(context: Context, task: Task, minutes: Int) = act(context) { startTimer(task.id, minutes, it) }
    fun pause(context: Context) = act(context) { pauseTimer(it) }
    fun resume(context: Context) = act(context) { resumeTimer(it) }
    fun addTime(context: Context, minutes: Int) = act(context) { addTime(minutes, it) }

    // Follows the current task's timer (from PinnedTask.refresh, after any change from anywhere): the
    // alarm for its end, or "Time's up" if that's passed; the alert goes once it's answered anywhere.
    internal suspend fun follow(context: Context, current: Task?) {
        val s = current?.let { t ->
            val endsAt = t.timerEndsAt
            val remaining = t.timerRemaining
            when {
                endsAt != null -> State(t.id, t.title, endsAt, 0L)
                remaining != null -> State(t.id, t.title, null, remaining)
                else -> null
            }
        }
        _state.value = s
        val endsAt = s?.endsAt
        val prefs = context.getSharedPreferences("timer", Context.MODE_PRIVATE)
        when {
            endsAt == null -> { cancelEnd(context); manager(context).cancel(NOTIFICATION_ID) }
            endsAt > System.currentTimeMillis() -> { scheduleEnd(context, endsAt); manager(context).cancel(NOTIFICATION_ID) }
            // Already over (the alarm rang, or it ran out while this phone was away): ask, once per timer end.
            prefs.getLong("alerted", 0L) != endsAt -> { prefs.edit().putLong("alerted", endsAt).apply(); timeUp(context, s) }
        }
    }

    fun clearNotification(context: Context) = manager(context).cancel(NOTIFICATION_ID)

    private fun timeUp(context: Context, s: State) {
        ensureChannel(context)
        val ask = PendingIntent.getActivity(
            context, 10,
            Intent(context, TimerDoneActivity::class.java).putExtra(TimerDoneActivity.EXTRA_TASK_ID, s.taskId).putExtra(TimerDoneActivity.EXTRA_TITLE, s.title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${Labels.TIMES_UP}: ${s.title}")
            .setContentText("Is it done?")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(ask)
            // Asks about open subtasks first, as the app does.
            .addAction(0, "Done", PendingIntent.getActivity(context, 11, CompleteAskActivity.intent(context, s.taskId, fromTimer = true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Not yet", ask)
            .build()
        manager(context).notify(NOTIFICATION_ID, notification)
    }

    // Exact, so the timer ends on time; USE_EXACT_ALARM (a timer app's permission) grants it on
    // Android 13+, and before that SCHEDULE_EXACT_ALARM, falling back to inexact if revoked.
    private fun scheduleEnd(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = actionIntent(context, ACTION_FINISHED, 0L, 4)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    private fun cancelEnd(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(actionIntent(context, ACTION_FINISHED, 0L, 4))

    // Also the "Now" notification's Pause / Resume.
    internal fun actionIntent(context: Context, action: String, taskId: Long, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, TaskTimerReceiver::class.java).setAction(action).putExtra(TimerDoneActivity.EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    // Time's up is an alert, with sound.
    private fun ensureChannel(context: Context) {
        val manager = manager(context)
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
            // The alarm: PinnedTask.refresh sees the timer is over and asks.
            TaskTimer.ACTION_FINISHED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try { PinnedTask.refresh(context) } finally { pending.finish() }
                }
            }
        }
    }
}
