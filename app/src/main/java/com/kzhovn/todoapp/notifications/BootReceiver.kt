package com.kzhovn.todoapp.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kzhovn.todoapp.TodoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// AlarmManager alarms don't survive a reboot, so every reminder that was scheduled before the
// device restarted needs to be re-scheduled from scratch once it comes back up. schedule() itself
// safely no-ops for a task with no due date/offset or a now-past trigger time, so it's safe to call
// unconditionally for every task rather than re-deriving which ones "should" have had an alarm.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val app = context.applicationContext as TodoApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.repository.getAllTasks()
                    .filter { it.dueDate != null && it.reminderOffsetMinutes != null && !it.isComplete }
                    .forEach { app.repository.updateTask(it) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
