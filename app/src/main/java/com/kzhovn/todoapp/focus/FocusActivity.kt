package com.kzhovn.todoapp.focus

import androidx.compose.foundation.shape.RoundedCornerShape
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import com.kzhovn.todoapp.ui.linkified
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import com.kzhovn.todoapp.AppSettings
import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.repository.focusSearch
import com.kzhovn.todoapp.repository.nextFocusTasks
import com.kzhovn.todoapp.data.findFolder
import com.kzhovn.todoapp.data.inMode
import com.kzhovn.todoapp.data.modeFolder
import com.kzhovn.todoapp.data.DEFAULT_FOLDER
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
class FocusActivity : ComponentActivity() {
    private var locked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        val repository = app.repository
        setContent {
            LedgerTheme {
                // The focus session (CurrentTask.focusSession), shared by every device: this screen
                // follows it wherever it started, and closes once it's over. Its task done, every device
                // asks what's next.
                var session by remember { mutableStateOf<Task?>(null) }
                val task = session?.takeUnless { it.isComplete }
                val askNext = session?.isComplete == true
                var effectiveDue by remember { mutableStateOf<Long?>(null) }
                // A checklist in focus: its items, to tick off here.
                var items by remember { mutableStateOf<List<Task>>(emptyList()) }
                var candidates by remember { mutableStateOf<List<Task>?>(null) }
                var leaving by remember { mutableStateOf<String?>(null) }
                var adding by remember { mutableStateOf(false) }
                // Adding from the "Focus on" picker: the new task is the one to focus on.
                var addToFocus by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                // Locked (screen pinning) whenever a session runs, wherever it was started.
                suspend fun reload() {
                    val s = repository.getFocusSession()
                    session = s
                    val all = if (s != null) repository.getAllTasks() else emptyList()
                    effectiveDue = s?.let { t -> resolveEffective(t, all.associateBy { it.id }, repository.getAllTaskContexts()).effectiveDueDate }
                    items = s?.takeIf { it.type == TaskType.CHECKLIST }?.let { checklistItems(it.id, all) }.orEmpty()
                    if (s != null && !locked) { locked = true; startLockTask() }
                }

                // Focusing pins the task, and moves the session to it on every device.
                fun focusOn(t: Task) = scope.launch {
                    repository.focus(t.id, System.currentTimeMillis())
                    PinnedTask.refresh(this@FocusActivity)
                    reload()
                }

                // Every task, for the picker's search (any open task, not just Doing or Active).
                var allTasks by remember { mutableStateOf<List<Task>>(emptyList()) }

                suspend fun pickable(finished: Task? = null): List<Task> {
                    val now = System.currentTimeMillis()
                    val all = repository.getAllTasks().also { allTasks = it }
                    val byId = all.associateBy { it.id }
                    val contexts = repository.getAllTaskContexts()
                    // In folder mode, the mode's tasks (its search too).
                    val mode = modeFolder(AppSettings.modeFolderId(this@FocusActivity), byId)?.id
                    val active = repository.getActiveTasksFrom(all, contexts, now, minuteOfDay(now), dayOfWeekMask(now)).filter { inMode(it, mode, byId) }
                    val doing = filterDoing(active, now, byId, contexts)
                    return nextFocusTasks(active, doing, finished, byId)
                }

                fun exit() {
                    locked = false
                    runCatching { stopLockTask() }
                    finish()
                }

                // Leaving (or "I'm done") ends the session on every device, and unpins.
                fun end() = scope.launch {
                    repository.unpin()
                    PinnedTask.refresh(this@FocusActivity)
                    exit()
                }

                // Opened on a task: focus on it. Otherwise join the running session, or ask what to focus on.
                LaunchedEffect(Unit) {
                    intent.getLongExtra(EXTRA_TASK_ID, 0L).takeIf { it != 0L }?.let {
                        repository.focus(it, System.currentTimeMillis())
                        PinnedTask.refresh(this@FocusActivity)
                    }
                    reload()
                    if (session == null) candidates = pickable()
                    // Follows changes from anywhere: the next task picked on the desktop, or the session left there.
                    app.listInputChanges().collect {
                        val had = session != null
                        reload()
                        if (had && session == null) exit()
                    }
                }
                BackHandler(enabled = session != null) { }

                Box(Modifier.fillMaxSize().background(LedgerBackground).padding(24.dp)) {
                    task?.let { t ->
                        Column(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Focus", fontSize = 13.sp, color = LedgerMuted, letterSpacing = 2.sp)
                            Spacer(Modifier.height(16.dp))
                            Text(t.title, fontSize = 28.sp, fontWeight = FontWeight.Medium, color = LedgerInk, textAlign = TextAlign.Center, lineHeight = 34.sp)
                            effectiveDue?.let { due -> Text(dueText(due, System.currentTimeMillis()), fontSize = 14.sp, color = LedgerMuted, modifier = Modifier.padding(top = 8.dp)) }
                            // The note: what to ask, the number to call. Links open.
                            t.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                                Text(
                                    linkified(notes), fontSize = 15.sp, lineHeight = 21.sp, color = LedgerInk,
                                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                                        .background(LedgerSearchBackground, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                            if (items.isNotEmpty()) Column(Modifier.padding(top = 20.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                                items.forEach { item ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TaskCheckbox(checked = item.isComplete, due = null, size = 26.dp, touchSize = 48.dp) {
                                            scope.launch { repository.toggleComplete(item.id, System.currentTimeMillis()); reload() }
                                        }
                                        Text(
                                            item.title, fontSize = 18.sp, color = if (item.isComplete) LedgerMuted else LedgerInk,
                                            textDecoration = if (item.isComplete) TextDecoration.LineThrough else null
                                        )
                                    }
                                }
                            }
                            t.durationMinutes?.let { minutes ->
                                Spacer(Modifier.height(20.dp))
                                TimerButton(t, minutes)
                            }
                            Spacer(Modifier.height(32.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TaskCheckbox(checked = false, due = effectiveDue?.let { dueStatus(it, System.currentTimeMillis()) }, size = 36.dp, touchSize = 56.dp) {
                                    // The session stays on the done task, so every device asks what's next.
                                    scope.launch {
                                        repository.completeWithDescendants(t.id, System.currentTimeMillis())
                                        PinnedTask.refresh(this@FocusActivity)
                                        reload()
                                    }
                                }
                                Text("Done", fontSize = 16.sp, color = LedgerMuted)
                            }
                        }
                        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            // Capture a stray thought without leaving. A dialog here, not QuickAddActivity: that runs
                            // in its own task, which a pinned screen won't open.
                            IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Add a task", tint = LedgerAccent) }
                            TextButton(onClick = { leaving = FocusPhrase.random() }) { Text(Labels.LEAVE_FOCUS, color = LedgerMuted) }
                        }
                    }
                }

                // Hidden while a new task is being typed; cancelling that brings it back.
                if (!adding) candidates?.let { list ->
                    TaskPickerDialog(
                        title = Labels.FOCUS_ON,
                        tasks = list,
                        searchAll = { query ->
                            val byId = allTasks.associateBy { it.id }
                            val mode = modeFolder(AppSettings.modeFolderId(this@FocusActivity), byId)?.id
                            focusSearch(allTasks, query).filter { inMode(it, mode, byId) }
                        },
                        onPick = { candidates = null; focusOn(it) },
                        onCreateNew = { adding = true; addToFocus = true },
                        // Nothing picked: with no session there's nothing to focus on; after a task is done,
                        // the "Next or finish?" question comes back.
                        onDismiss = { candidates = null; if (session == null) exit() }
                    )
                }

                if (askNext && candidates == null) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Done!") },
                        text = { Text(Labels.FOCUS_NEXT_OR_FINISH) },
                        confirmButton = { Button(onClick = { scope.launch { candidates = pickable(finished = session) } }) { Text("Next task") } },
                        dismissButton = { TextButton(onClick = { end() }) { Text(Labels.IM_DONE) } }
                    )
                }

                if (adding) {
                    var text by remember { mutableStateOf("") }
                    val focusOnIt = addToFocus
                    val close = { adding = false; addToFocus = false }
                    val add = {
                        close()
                        if (text.isNotBlank()) scope.launch {
                            val now = System.currentTimeMillis()
                            val add = repository.planQuickAdd(text, AppSettings.rolloverHour(this@FocusActivity), now)
                            // Into folder mode's folder or Personal unless it names a folder, like quick add with no folder chosen.
                            val folders = repository.getFolders()
                            val home = modeFolder(AppSettings.modeFolderId(this@FocusActivity), folders.associateBy { it.id }) ?: findFolder(folders, DEFAULT_FOLDER)
                            val created = repository.quickAdd(add, home?.id, now)?.let { repository.getTask(it) }
                            if (focusOnIt && created != null && add.task != null) {
                                candidates = null
                                focusOn(created)
                            } else if (created != null) {
                                Toast.makeText(this@FocusActivity, add.task?.let { "Added “${it.title}”" } ?: "Added to ${created.title}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    AlertDialog(
                        onDismissRequest = close,
                        title = { Text(if (focusOnIt) "New task to focus on" else "Add a task") },
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
                        confirmButton = { Button(onClick = add, enabled = text.isNotBlank()) { Text(if (focusOnIt) "Focus" else "Add") } },
                        dismissButton = { TextButton(onClick = close) { Text("Cancel") } }
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
                                onClick = { end() },
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
        if (locked && getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
    }
}
