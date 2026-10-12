package com.kzhovn.todoapp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.notifications.PinnedTask
import com.kzhovn.todoapp.notifications.TaskTimer
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

// Completes a task from outside the app (the widget, a notification, the timer's "Done"), asking about
// its open subtasks first as the app does. With none, it just completes it and closes; nothing shows.
class CompleteAskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as TodoApp).repository
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        // From the timer's "time's up": that notification goes, as its own Done did.
        if (intent.getBooleanExtra(EXTRA_FROM_TIMER, false)) TaskTimer.clearNotification(this)
        fun complete(moveOut: Boolean) = lifecycleScope.launch {
            repository.completeChoosing(taskId, moveOut, System.currentTimeMillis())
            PinnedTask.refresh(this@CompleteAskActivity)
            finish()
        }
        lifecycleScope.launch {
            if (repository.getTask(taskId)?.isComplete != false) return@launch finish()
            val question = repository.completeQuestion(taskId) ?: run { complete(moveOut = false); return@launch }
            setContent {
                LedgerTheme {
                    CompleteSubtasksDialog(question, onCompleteAll = { complete(moveOut = false) }, onMoveOut = { complete(moveOut = true) }, onDismiss = ::finish)
                }
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_FROM_TIMER = "from_timer"

        fun intent(context: Context, taskId: Long, fromTimer: Boolean = false): Intent =
            Intent(context, CompleteAskActivity::class.java).putExtra(EXTRA_TASK_ID, taskId).putExtra(EXTRA_FROM_TIMER, fromTimer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
