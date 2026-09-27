package com.kzhovn.todoapp.focus

import com.kzhovn.todoapp.data.findFolder
import com.kzhovn.todoapp.data.DEFAULT_FOLDER
import com.kzhovn.todoapp.quickadd.QuickAddParser
import com.kzhovn.todoapp.quickadd.QuickAddActivity
import com.kzhovn.todoapp.data.Labels
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import android.widget.Toast
import android.app.ActivityManager
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
import androidx.compose.material.icons.filled.Add
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
import kotlinx.coroutines.launch

// One task, full screen, with the app pinned (Android's screen pinning) so other apps are out of
// reach. Leaving takes typing a random sentence; finishing the task offers the next one instead.
// ponytail: pinning can still be undone with Android's own Back+Overview gesture; a real app
// blocker (usage access + overlay) is on the backlog.
// What to focus on next: Doing, or Active when Doing is empty. After finishing a step of something
// sequential, the step that's now unblocked always comes first.
internal fun nextFocusTasks(active: List<Task>, doing: List<Task>, finished: Task?, byId: Map<Long, Task>): List<Task> {
    val nextStep = finished?.parentId?.takeIf { byId[it]?.sequential == true }?.let { parent -> active.firstOrNull { it.parentId == parent } }
    return (listOfNotNull(nextStep) + doing.ifEmpty { active }).distinctBy { it.id }
}

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
                var adding by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                fun focusOn(t: Task) {
                    task = t
                    scope.launch {
                        val all = repository.getAllTasks()
                        effectiveDue = resolveEffective(t, all.associateBy { it.id }, repository.getAllTaskContexts()).effectiveDueDate
                    }
                    if (!focusing) { focusing = true; startLockTask() }
                }

                suspend fun pickable(finished: Task? = null): List<Task> {
                    val now = System.currentTimeMillis()
                    val all = repository.getAllTasks()
                    val byId = all.associateBy { it.id }
                    val contexts = repository.getAllTaskContexts()
                    val active = repository.getActiveTasksFrom(all, contexts, now, minuteOfDay(now), dayOfWeekMask(now))
                    val doing = filterDoing(active, now, byId, contexts)
                    return nextFocusTasks(active, doing, finished, byId)
                }

                fun exit() {
                    focusing = false
                    runCatching { stopLockTask() }
                    finish()
                }

                // The task it was opened on, else the running timer's, else the pinned one, else ask.
                LaunchedEffect(Unit) {
                    val id = intent.getLongExtra(EXTRA_TASK_ID, 0L).takeIf { it != 0L }
                        ?: TaskTimer.state.value?.taskId ?: PinnedTask.pinnedId(this@FocusActivity)
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
                                        askNext = true
                                    }
                                }
                                Text("Done", fontSize = 16.sp, color = LedgerMuted)
                            }
                        }
                        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            // Capture a stray thought without leaving. A dialog here, not QuickAddActivity: that runs
                            // in its own task, which a pinned screen won't open.
                            IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Add a task", tint = LedgerAccent) }
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
                        confirmButton = { Button(onClick = { scope.launch { candidates = pickable(finished = task) } }) { Text("Next task") } },
                        dismissButton = { TextButton(onClick = { exit() }) { Text("I'm done") } }
                    )
                }

                if (adding) {
                    var text by remember { mutableStateOf("") }
                    val add = {
                        val parsed = QuickAddParser.parse(text)
                        adding = false
                        if (parsed.title.isNotBlank()) scope.launch {
                            // Into Personal, like quick add with no folder chosen.
                            val personal = findFolder(repository.getFolders(), DEFAULT_FOLDER)
                            repository.createTask(parsed.copy(parentId = personal?.id))
                            Toast.makeText(this@FocusActivity, "Added “${parsed.title}”", Toast.LENGTH_SHORT).show()
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { adding = false },
                        title = { Text("Add a task") },
                        text = {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                placeholder = { Text(Labels.TITLE) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { add() }),
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = { Button(onClick = add, enabled = text.isNotBlank()) { Text("Add") } },
                        dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } }
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

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }

    // Unpinned with Android's own gesture and back again: pin again.
    override fun onResume() {
        super.onResume()
        if (focusing && getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
    }
}
