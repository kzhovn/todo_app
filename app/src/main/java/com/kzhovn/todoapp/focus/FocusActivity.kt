package com.kzhovn.todoapp.focus

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.dueText
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.notifications.PinnedTask
import com.kzhovn.todoapp.notifications.TaskTimer
import com.kzhovn.todoapp.repository.dayOfWeekMask
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.repository.minuteOfDay
import com.kzhovn.todoapp.ui.TaskCheckbox
import com.kzhovn.todoapp.ui.TaskPickerDialog
import com.kzhovn.todoapp.ui.TimerButton
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.launch

// One task, full screen, with the app pinned (Android's screen pinning) so other apps are out of
// reach. Leaving takes typing a random sentence; finishing the task offers the next one instead.
// ponytail: pinning can still be undone with Android's own Back+Overview gesture; a real app
// blocker (usage access + overlay) is on the backlog.
class FocusActivity : ComponentActivity() {
    private var focusing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        val repository = app.repository
        setContent {
            LedgerTheme {
                var task by remember { mutableStateOf<Task?>(null) }
                var effectiveDue by remember { mutableStateOf<Long?>(null) }
                var candidates by remember { mutableStateOf<List<Task>?>(null) }
                var askNext by remember { mutableStateOf(false) }
                var leaving by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                fun focusOn(t: Task) {
                    task = t
                    scope.launch {
                        val all = repository.getAllTasks()
                        effectiveDue = resolveEffective(t, all.associateBy { it.id }, repository.getAllTaskContexts()).effectiveDueDate
                    }
                    if (!focusing) { focusing = true; startLockTask() }
                }

                // What's active, Doing's tasks first, for "pick a task".
                suspend fun pickable(): List<Task> {
                    val now = System.currentTimeMillis()
                    val active = repository.getActiveTasks(now, minuteOfDay(now), dayOfWeekMask(now))
                    return filterDoing(active, now).let { doing -> doing + (active - doing.toSet()) }
                }

                fun exit() {
                    focusing = false
                    runCatching { stopLockTask() }
                    finish()
                }

                // The running timer's task, else the pinned one, else ask.
                LaunchedEffect(Unit) {
                    val id = TaskTimer.state.value?.taskId ?: PinnedTask.pinnedId(this@FocusActivity)
                    val current = id?.let { repository.getTask(it) }?.takeUnless { it.isComplete }
                    if (current != null) focusOn(current) else candidates = pickable()
                }
                BackHandler(enabled = task != null) { }

                Box(Modifier.fillMaxSize().background(LedgerBackground).padding(24.dp)) {
                    task?.let { t ->
                        Column(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Focus", fontSize = 13.sp, color = LedgerMuted, letterSpacing = 2.sp)
                            Spacer(Modifier.height(16.dp))
                            Text(t.title, fontSize = 28.sp, fontWeight = FontWeight.Medium, color = LedgerInk, textAlign = TextAlign.Center, lineHeight = 34.sp)
                            effectiveDue?.let { due -> Text(dueText(due, System.currentTimeMillis()), fontSize = 14.sp, color = LedgerMuted, modifier = Modifier.padding(top = 8.dp)) }
                            t.durationMinutes?.let { minutes ->
                                Spacer(Modifier.height(20.dp))
                                TimerButton(t, minutes)
                            }
                            Spacer(Modifier.height(32.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TaskCheckbox(checked = false, due = effectiveDue?.let { dueStatus(it, System.currentTimeMillis()) }, size = 36.dp, touchSize = 56.dp) {
                                    scope.launch {
                                        repository.completeWithDescendants(t.id, System.currentTimeMillis())
                                        if (TaskTimer.state.value?.taskId == t.id) TaskTimer.stop(this@FocusActivity)
                                        TodoWidget().updateAll(applicationContext)
                                        askNext = true
                                    }
                                }
                                Text("Done", fontSize = 16.sp, color = LedgerMuted)
                            }
                        }
                        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            // Calls stay possible. Opening the dialer has to unpin; coming back here pins again.
                            IconButton(onClick = {
                                runCatching { stopLockTask() }
                                startActivity(Intent(Intent.ACTION_DIAL))
                            }) { Icon(Icons.Filled.Call, contentDescription = "Phone", tint = LedgerMuted) }
                            TextButton(onClick = { leaving = FocusPhrase.random() }) { Text("Leave focus", color = LedgerMuted) }
                        }
                    }
                }

                candidates?.let { list ->
                    TaskPickerDialog(
                        title = "Focus on",
                        tasks = list,
                        onPick = { candidates = null; askNext = false; focusOn(it) },
                        onCreateNew = null,
                        // Nothing picked: with no task yet there's nothing to focus on; after one, it's the "done" choice.
                        onDismiss = { candidates = null; if (task == null || askNext) exit() }
                    )
                }

                if (askNext && candidates == null) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Done!") },
                        text = { Text("Focus on the next task, or finish?") },
                        confirmButton = { Button(onClick = { scope.launch { candidates = pickable() } }) { Text("Next task") } },
                        dismissButton = { TextButton(onClick = { exit() }) { Text("I'm done") } }
                    )
                }

                leaving?.let { phrase ->
                    var typed by remember(phrase) { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { leaving = null },
                        title = { Text("Leave focus?") },
                        text = {
                            Column {
                                Text("Type this out to leave:", fontSize = 13.sp, color = LedgerMuted)
                                Text(phrase, fontSize = 16.sp, color = LedgerInk, modifier = Modifier.padding(vertical = 8.dp))
                                OutlinedTextField(
                                    value = typed,
                                    onValueChange = { typed = it },
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { exit() },
                                enabled = FocusPhrase.matches(phrase, typed),
                                colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)
                            ) { Text("Leave") }
                        },
                        dismissButton = { TextButton(onClick = { leaving = null }) { Text("Stay") } }
                    )
                }
            }
        }
    }

    // Back from the dialer (or anywhere): pin again.
    override fun onResume() {
        super.onResume()
        if (focusing && getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
    }
}
