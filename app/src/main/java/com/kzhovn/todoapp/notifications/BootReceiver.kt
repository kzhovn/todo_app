package com.kzhovn.todoapp.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kzhovn.todoapp.TodoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// AlarmManager alarms don't survive a reboot, so every reminder is scheduled again once the device
// is back up (schedule() skips tasks that shouldn't have one).
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val app = context.applicationContext as TodoApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.repository.getAllTasks().forEach { app.reminderScheduler.schedule(it) }
                PinnedTask.refresh(app) // ongoing notifications don't survive a reboot either
                TaskTimer.restore(app) // nor do the timer's notification and alarm
            } finally {
                pendingResult.finish()
            }
        }
    }
}
