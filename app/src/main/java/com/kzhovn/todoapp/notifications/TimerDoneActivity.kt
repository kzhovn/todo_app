package com.kzhovn.todoapp.notifications

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.formatDuration
import com.kzhovn.todoapp.ui.SelectablePill
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.launch

// "Time's up" for a timed task: is it done? Done completes it; Not yet asks how much more time and
// starts the timer again with that.
class TimerDoneActivity : ComponentActivity() {
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        TaskTimer.clearNotification(this)

        fun restart(minutes: Int) = lifecycleScope.launch {
            app.repository.getTask(taskId)?.let { TaskTimer.start(this@TimerDoneActivity, it, minutes) }
            finish()
        }

        setContent {
            LedgerTheme {
                var askMore by remember { mutableStateOf(false) }
                var custom by remember { mutableStateOf("") }
                Column(Modifier.background(LedgerBackground).padding(20.dp)) {
                    Text("Time's up", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LedgerInk)
                    Text(title, fontSize = 15.sp, color = LedgerInk, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(16.dp))
                    if (!askMore) {
                        Text("Is it done?", fontSize = 14.sp, color = LedgerMuted)
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Button(
                                onClick = {
                                    lifecycleScope.launch {
                                        // As the notification's Done: subtasks complete too.
                                        app.repository.completeWithDescendants(taskId, System.currentTimeMillis())
                                        TodoWidget().updateAll(applicationContext)
                                        finish()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)
                            ) { Text("Done") }
                            Spacer(Modifier.width(10.dp))
                            Button(onClick = { askMore = true }) { Text("Not yet") }
                        }
                    } else {
                        Text("How much more time?", fontSize = 14.sp, color = LedgerMuted)
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 10, 15, 30, 60).forEach { minutes ->
                                SelectablePill("+${formatDuration(minutes)}", selected = false) { restart(minutes) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = custom,
                                onValueChange = { custom = it.filter(Char::isDigit).take(4) },
                                placeholder = { Text("Minutes") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(120.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Button(enabled = (custom.toIntOrNull() ?: 0) > 0, onClick = { restart(custom.toInt()) }) { Text("Start") }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TITLE = "title"
    }
}
