package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.wouldCreateCycle
import com.kzhovn.todoapp.sync.SyncJson
import androidx.compose.material3.AlertDialog
import com.kzhovn.todoapp.repository.InheritedField
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.notifications.PinnedTask
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextDecoration
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.contexts.ContextsActivity
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.wouldCreateDependencyCycle
import com.kzhovn.todoapp.data.nextRollover
import com.kzhovn.todoapp.AppSettings
import android.widget.Toast
import com.kzhovn.todoapp.quickadd.QuickAddActivity
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.RecurrenceUnit
import com.kzhovn.todoapp.recurrence.recurrenceSelectionFromTask
import com.kzhovn.todoapp.recurrence.toTaskFields
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerStar
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.launch

class TaskEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        val repository = app.repository
        val contextRepository = app.contextRepository
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        val initialType = when {
            intent.getBooleanExtra(EXTRA_CREATE_AS_FOLDER, false) -> TaskType.FOLDER
            intent.getBooleanExtra(EXTRA_CREATE_AS_PROJECT, false) -> TaskType.PROJECT
            else -> TaskType.TASK
        }
        setContent {
            LedgerTheme {
            val viewModel = remember { TaskEditViewModel(repository) }
            val scope = rememberCoroutineScope()
            var task by remember { mutableStateOf(Task(id = taskId, title = "", type = initialType)) }
            // For an existing task, Save must stay disabled until the real task data has
            // loaded — otherwise a tap before the load completes commits this empty
            // placeholder, wiping the task's title and every other field.
            var isLoaded by remember { mutableStateOf(taskId == 0L) }
            var showDeleteConfirm by remember { mutableStateOf(false) }
            var descendantCount by remember { mutableStateOf(0) }
            var folders by remember { mutableStateOf<List<Task>>(emptyList()) }
            var showFolderPicker by remember { mutableStateOf(false) }
            var showNewFolderDialog by remember { mutableStateOf(false) }
            var newFolderName by remember { mutableStateOf("") }
            var recurrence by remember { mutableStateOf(RecurrenceSelection(RecurrencePreset.NONE)) }
            // The picker only knows simple rules; an imported one like "monthly on the second-to-last
            // day" displays approximately, so it's only rewritten if the user actually changes it.
            var loadedRecurrence by remember { mutableStateOf<RecurrenceSelection?>(null) }
            var allTasks by remember { mutableStateOf<List<Task>>(emptyList()) }
            var selectedDependencyIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
            var allDependencyEdges by remember { mutableStateOf<List<TaskDependency>>(emptyList()) }
            var allContexts by remember { mutableStateOf<List<TaskContext>>(emptyList()) }
            var selectedContextIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
            val allById = remember(allTasks) { allTasks.associateBy { it.id } }
            var showDependentPicker by remember { mutableStateOf(false) }
            var showSubtaskPicker by remember { mutableStateOf(false) }
            val focusManager = LocalFocusManager.current

            // ContextsActivity is launched with plain startActivity (not for a result), so
            // returning here via back needs an explicit re-pull to pick up newly created contexts.
            // Also re-pulls tasks, so a subtask just added (or edited) from here shows up on return.
            LifecycleResumeEffect(Unit) {
                scope.launch {
                    allContexts = contextRepository.getAllContexts()
                    if (taskId != 0L) allTasks = repository.getAllTasks()
                }
                onPauseOrDispose { }
            }

            // Folders don't carry task-only fields/relations (a due date would keep scheduling
            // a reminder alarm; a folder can't be completed, so leaving it as someone's
            // dependency would block that task forever) — clear them on save regardless of what
            // the (hidden, for folders) recurrence/deps UI holds.
            // As loaded, to tell which inherited fields this edit changes.
            var original by remember { mutableStateOf<Task?>(null) }
            var originalContextIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
            // A project needs a first step; asked on save when it has none.
            var askFirstSubtask by remember { mutableStateOf(false) }
            var firstSubtaskTitle by remember { mutableStateOf("") }
            // Subtasks with their own value for a changed inherited field, pending "update them too?".
            var pendingInherit by remember { mutableStateOf<Pair<List<Task>, Set<InheritedField>>?>(null) }

            fun save(clearOn: List<Task>, fields: Set<InheritedField>, firstSubtask: String?) {
                val toSave: Task
                val dependenciesToSave: Set<Long>
                if (task.type == TaskType.FOLDER) {
                    toSave = task.copy(dueDate = null, recurrenceType = null, recurrenceRule = null, reminderOffsetMinutes = null)
                    dependenciesToSave = emptySet()
                } else {
                    val (recurrenceType, recurrenceRule) =
                        if (recurrence == loadedRecurrence) task.recurrenceType to task.recurrenceRule else recurrence.toTaskFields()
                    toSave = task.copy(recurrenceType = recurrenceType, recurrenceRule = recurrenceRule)
                    dependenciesToSave = selectedDependencyIds
                }
                // Folders keep contexts: children inherit them (e.g. Work only active in work hours).
                val contextsToSave = selectedContextIds
                viewModel.save(toSave) { savedId ->
                    repository.setDependencies(savedId, dependenciesToSave)
                    contextRepository.setTaskContexts(savedId, contextsToSave)
                    repository.clearInherited(clearOn, fields)
                    firstSubtask?.let { repository.createTask(Task(title = it, parentId = savedId)) }
                    TodoWidget().updateAll(applicationContext)
                    finish()
                }
            }

            val onSave: () -> Unit = {
                val hasSubtasks = allTasks.any { it.parentId == taskId && taskId != 0L }
                if (task.type == TaskType.PROJECT && !hasSubtasks) {
                    firstSubtaskTitle = ""
                    askFirstSubtask = true
                } else {
                    val before = original
                    val changed = if (before == null) emptySet() else buildSet {
                        if (task.startDate != before.startDate) add(InheritedField.START)
                        if (task.dueDate != before.dueDate) add(InheritedField.DUE)
                        if (task.icon != before.icon) add(InheritedField.ICON)
                        if (selectedContextIds != originalContextIds) add(InheritedField.CONTEXTS)
                    }
                    scope.launch {
                        // Only subtasks that override a changed field need asking; the rest already follow it.
                        val overriding = if (changed.isEmpty()) emptyList() else repository.descendantsOverriding(taskId, changed)
                        if (overriding.isEmpty()) save(emptyList(), emptySet(), null)
                        else pendingInherit = overriding to changed.filterTo(mutableSetOf()) { f -> overriding.any { overrides(it, f) } }
                    }
                }
            }

            LaunchedEffect(taskId) {
                if (taskId == 0L) {
                    intent.getStringExtra(EXTRA_DRAFT)?.let { task = SyncJson.decodeFromString(Task.serializer(), it) }
                    intent.getLongExtra(EXTRA_DRAFT_DEPENDS_ON, 0L).takeIf { it != 0L }?.let { selectedDependencyIds = setOf(it) }
                }
                if (taskId != 0L) {
                    viewModel.load(taskId)?.let { loaded ->
                        task = loaded
                        recurrence = recurrenceSelectionFromTask(loaded.recurrenceType, loaded.recurrenceRule)
                        loadedRecurrence = recurrence
                    }
                    selectedDependencyIds = repository.getDependencyIds(taskId)
                    selectedContextIds = contextRepository.getContextsForTask(taskId).map { it.id }.toSet()
                    original = task
                    originalContextIds = selectedContextIds
                    isLoaded = true
                }
                allTasks = repository.getAllTasks()
                folders = allTasks.filter { it.type == TaskType.FOLDER }
                allDependencyEdges = repository.getAllDependencyEdges()
                allContexts = contextRepository.getAllContexts()
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LedgerBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = task.title,
                        onValueChange = { task = task.copy(title = it) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            if (isLoaded && task.title.isNotBlank()) onSave()
                        }),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(enabled = !task.isMaybe, onClick = { task = task.copy(isStarred = !task.isStarred) }) {
                        Icon(
                            if (task.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Star",
                            tint = if (task.isStarred) LedgerStar else LedgerMuted
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row {
                    PropertyChip(
                        label = "Start",
                        valueText = task.startDate?.let(::formatChipDate),
                        icon = Icons.Filled.Event,
                        onClick = { pickDate(this@TaskEditActivity, task.startDate) { task = task.copy(startDate = it) } },
                        onClear = { task = task.copy(startDate = null) },
                        showLabelWhenSet = false
                    )
                    Spacer(Modifier.width(8.dp))
                    if (task.type != TaskType.FOLDER) {
                        PropertyChip(
                            label = "Due",
                            valueText = task.dueDate?.let(::formatChipDate),
                            icon = Icons.Filled.Flag,
                            onClick = { pickDate(this@TaskEditActivity, task.dueDate) { task = task.copy(dueDate = it) } },
                            onClear = { task = task.copy(dueDate = null) },
                            showLabelWhenSet = false
                        )
                    }
                }

                if (task.dueDate != null && task.type == TaskType.TASK) {
                    Spacer(Modifier.height(8.dp))
                    Text("Remind me", fontSize = 12.sp, color = LedgerMuted)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        LabelOptions(
                            options = listOf(
                                null to "No reminder",
                                0 to "At due time",
                                5 to "5 min before",
                                30 to "30 min before",
                                60 to "1 hour before",
                                1440 to "1 day before"
                            ),
                            selected = task.reminderOffsetMinutes,
                            onSelect = { offset -> task = task.copy(reminderOffsetMinutes = offset) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                PropertyChip(
                    label = "Folder",
                    valueText = allById[task.parentId]?.title,
                    icon = Icons.Filled.Folder,
                    onClick = { showFolderPicker = true },
                    showLabelWhenSet = false
                )

                if (task.type != TaskType.FOLDER) {
                    Spacer(Modifier.height(12.dp))
                    Text("Repeat", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerInk)
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        LabelOptions(
                            options = listOf(
                                RecurrencePreset.NONE to "None",
                                RecurrencePreset.CALENDAR to "Every",
                                RecurrencePreset.AFTER_COMPLETION_N_DAYS to "After completion"
                            ),
                            selected = recurrence.preset,
                            onSelect = { preset -> recurrence = recurrence.copy(preset = preset) }
                        )
                    }
                    if (recurrence.preset == RecurrencePreset.CALENDAR) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Every", fontSize = 12.sp, color = LedgerMuted)
                            Spacer(Modifier.width(6.dp))
                            CompactNumberField(value = recurrence.n) { n -> recurrence = recurrence.copy(n = n) }
                            Spacer(Modifier.width(6.dp))
                            LabelOptions(
                                options = listOf(
                                    RecurrenceUnit.DAY to "day(s)",
                                    RecurrenceUnit.WEEK to "week(s)",
                                    RecurrenceUnit.MONTH to "month(s)"
                                ),
                                selected = recurrence.unit,
                                trailingPadding = 8.dp,
                                verticalPadding = 0.dp,
                                onSelect = { unit -> recurrence = recurrence.copy(unit = unit) }
                            )
                        }
                        if (recurrence.unit == RecurrenceUnit.WEEK) {
                            Spacer(Modifier.height(4.dp))
                            DayOfWeekToggle(recurrence.weekdaysMask) { mask -> recurrence = recurrence.copy(weekdaysMask = mask) }
                        }
                    }
                    if (recurrence.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Repeat", fontSize = 12.sp, color = LedgerMuted)
                            Spacer(Modifier.width(6.dp))
                            CompactNumberField(value = recurrence.n) { n -> recurrence = recurrence.copy(n = n) }
                            Spacer(Modifier.width(6.dp))
                            Text("days after completion", fontSize = 12.sp, color = LedgerMuted)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Depends on", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerInk)
                    val dependencyCandidates = remember(allTasks, allDependencyEdges, task.id) {
                        allTasks.filter {
                            it.id != task.id && it.type == TaskType.TASK && !it.isComplete &&
                                !wouldCreateDependencyCycle(it.id, task.id, allDependencyEdges)
                        }
                    }
                    SearchableMultiSelectDropdown(
                        label = "Choose tasks",
                        items = dependencyCandidates,
                        selectedIds = selectedDependencyIds,
                        idOf = { it.id },
                        labelOf = { it.title },
                        onToggle = { id ->
                            selectedDependencyIds = if (id in selectedDependencyIds) selectedDependencyIds - id else selectedDependencyIds + id
                        },
                        searchable = true
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Contexts", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerInk)
                SearchableMultiSelectDropdown(
                    label = "Choose contexts",
                    items = allContexts,
                    selectedIds = selectedContextIds,
                    idOf = { it.id },
                    labelOf = { it.name },
                    onToggle = { id ->
                        selectedContextIds = if (id in selectedContextIds) selectedContextIds - id else selectedContextIds + id
                    },
                    onCreateNew = { startActivity(Intent(this@TaskEditActivity, ContextsActivity::class.java)) },
                    createNewLabel = "Create new context"
                )

                val subtasks = remember(allTasks, taskId) { allTasks.filter { it.parentId == taskId && taskId != 0L }.sortedWith(TaskOrder) }
                if (subtasks.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Subtasks", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerInk)
                    subtasks.forEach { sub ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (sub.type == TaskType.TASK) {
                                TaskCheckbox(checked = sub.isComplete, overdue = isOverdue(sub.isComplete, sub.dueDate), size = 18.dp, touchSize = 34.dp, onCheckedChange = {
                                    scope.launch {
                                        repository.toggleComplete(sub.id, System.currentTimeMillis())
                                        allTasks = repository.getAllTasks()
                                        TodoWidget().updateAll(applicationContext)
                                    }
                                })
                            } else {
                                Icon(Icons.Filled.Folder, contentDescription = null, tint = LedgerMuted, modifier = Modifier.size(34.dp).padding(8.dp))
                            }
                            Text(
                                sub.title,
                                fontSize = 14.sp,
                                textDecoration = if (sub.isComplete) TextDecoration.LineThrough else null,
                                color = if (sub.isComplete) LedgerMuted else LedgerInk,
                                modifier = Modifier.weight(1f).clickable {
                                    startActivity(Intent(this@TaskEditActivity, TaskEditActivity::class.java).putExtra(EXTRA_TASK_ID, sub.id))
                                }.padding(vertical = 6.dp)
                            )
                        }
                    }
                }

                if (taskId != 0L) {
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Text(
                            "+ Add subtask",
                            color = LedgerAccent,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { showSubtaskPicker = true }
                        )
                        // A dependent task is one blocked until this one is done. Folders can't be completed,
                        // so they can't be depended on (projects can: they complete as a whole).
                        if (task.type != TaskType.FOLDER) {
                            Spacer(Modifier.width(24.dp))
                            Text(
                                "+ Add dependent task",
                                color = LedgerAccent,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { showDependentPicker = true }
                            )
                        }
                    }
                    if (task.type == TaskType.TASK && !task.isComplete) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Pin to notification (doing now)",
                            color = LedgerAccent,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                PinnedTask.pin(this@TaskEditActivity, task)
                                Toast.makeText(this@TaskEditActivity, "Pinned", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                if (task.type != TaskType.FOLDER) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Maybe (?)", fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.weight(1f))
                        Switch(checked = task.isMaybe, onCheckedChange = { task = task.copy(isMaybe = it).starRule() })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Just for today", fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.weight(1f))
                        Switch(checked = task.expiresAt != null, onCheckedChange = { on ->
                            val hour = AppSettings.rolloverHour(this@TaskEditActivity)
                            task = task.copy(expiresAt = if (on) nextRollover(System.currentTimeMillis(), hour) else null)
                        })
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(TaskType.TASK to "Task", TaskType.PROJECT to "Project", TaskType.FOLDER to "Folder").forEach { (type, label) ->
                        SelectablePill(label, selected = task.type == type) { task = task.copy(type = type) }
                        Spacer(Modifier.width(8.dp))
                    }
                }

                // Folders and tasks alike: only the first incomplete child counts as active.
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (task.type == TaskType.FOLDER) "Sequential (complete tasks in order)" else "Complete subtasks in order",
                        fontSize = 12.sp, color = LedgerMuted,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = task.sequential, onCheckedChange = { task = task.copy(sequential = it) })
                }

                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (taskId != 0L) {
                        IconButton(onClick = {
                            scope.launch {
                                descendantCount = repository.countDescendants(task.id)
                                showDeleteConfirm = true
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = LedgerOverdue)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onSave,
                        enabled = isLoaded && task.title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)
                    ) {
                        Text("Save")
                    }
                }
            }

            if (showDeleteConfirm) {
                ConfirmDialog(
                    title = "Delete this ${if (task.type == TaskType.FOLDER) "folder" else "task"}?",
                    body = if (descendantCount > 0) {
                        "This will also delete $descendantCount subtask${if (descendantCount == 1) "" else "s"}."
                    } else null,
                    confirmLabel = "Delete",
                    onConfirm = {
                        showDeleteConfirm = false
                        scope.launch {
                            repository.deleteTask(task)
                            TodoWidget().updateAll(applicationContext)
                            finish()
                        }
                    },
                    onDismiss = { showDeleteConfirm = false }
                )
            }

            if (askFirstSubtask) {
                TextInputDialog(
                    title = "First step of this project",
                    placeholder = "Subtask",
                    confirmLabel = "Save project",
                    value = firstSubtaskTitle,
                    onValueChange = { firstSubtaskTitle = it },
                    onConfirm = {
                        askFirstSubtask = false
                        save(emptyList(), emptySet(), firstSubtaskTitle.trim())
                    },
                    onDismiss = { askFirstSubtask = false }
                )
            }

            pendingInherit?.let { (overriding, fields) ->
                val what = fields.joinToString(" and ") { it.label }
                AlertDialog(
                    onDismissRequest = { pendingInherit = null },
                    title = { Text("Update subtasks too?") },
                    text = { Text("${overriding.size} subtask${if (overriding.size == 1) " has its" else "s have their"} own $what. Clear ${if (overriding.size == 1) "it" else "them"} so ${if (overriding.size == 1) "it follows" else "they follow"} this task?") },
                    confirmButton = {
                        Button(onClick = { pendingInherit = null; save(overriding, fields, null) }) { Text("Update subtasks") }
                    },
                    dismissButton = {
                        Button(onClick = { pendingInherit = null; save(emptyList(), emptySet(), null) }) { Text("Only this task") }
                    }
                )
            }

            if (showSubtaskPicker) {
                // Existing tasks can be moved under this one (anything but its own ancestors), or a new one created.
                val candidates = remember(allTasks, task.id) {
                    allTasks.filter {
                        it.id != task.id && it.type != TaskType.FOLDER && !it.isComplete && it.parentId != task.id &&
                            !wouldCreateCycle(task.id, it.id, allById)
                    }
                }
                TaskPickerDialog(
                    title = "Add a subtask",
                    tasks = candidates,
                    onPick = { picked ->
                        showSubtaskPicker = false
                        scope.launch {
                            repository.reparent(picked.id, task.id)
                            allTasks = repository.getAllTasks()
                            TodoWidget().updateAll(applicationContext)
                        }
                    },
                    onCreateNew = {
                        showSubtaskPicker = false
                        startActivity(
                            Intent(this@TaskEditActivity, QuickAddActivity::class.java)
                                .putExtra(QuickAddActivity.EXTRA_PARENT_ID, task.id)
                        )
                    },
                    onDismiss = { showSubtaskPicker = false }
                )
            }

            if (showDependentPicker) {
                val candidates = remember(allTasks, allDependencyEdges, task.id) {
                    allTasks.filter {
                        it.id != task.id && it.type == TaskType.TASK && !it.isComplete &&
                            allDependencyEdges.none { e -> e.taskId == it.id && e.dependsOnTaskId == task.id } &&
                            !wouldCreateDependencyCycle(task.id, it.id, allDependencyEdges)
                    }
                }
                TaskPickerDialog(
                    title = "Add a task that waits for this one",
                    tasks = candidates,
                    onPick = { picked ->
                        showDependentPicker = false
                        scope.launch {
                            repository.addDependency(picked.id, task.id)
                            allDependencyEdges = repository.getAllDependencyEdges()
                            TodoWidget().updateAll(applicationContext)
                            Toast.makeText(this@TaskEditActivity, "“${picked.title}” now waits for this", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCreateNew = {
                        showDependentPicker = false
                        // The new task goes in this task's folder by default, since dependent work usually belongs together.
                        val folderId = task.parentId?.takeIf { allById[it]?.type == TaskType.FOLDER }
                        startActivity(
                            Intent(this@TaskEditActivity, QuickAddActivity::class.java)
                                .putExtra(QuickAddActivity.EXTRA_DEPENDS_ON, task.id)
                                .apply { folderId?.let { putExtra(QuickAddActivity.EXTRA_FOLDER_ID, it) } }
                        )
                    },
                    onDismiss = { showDependentPicker = false }
                )
            }

            if (showFolderPicker) {
                FolderPickerDialog(
                    folders = folders,
                    excludeDescendantsOf = task.id,
                    allById = allById,
                    showNoFolderOption = true,
                    onPick = { picked -> task = task.copy(parentId = picked?.id); showFolderPicker = false },
                    onDismiss = { showFolderPicker = false },
                    onCreateNew = { showFolderPicker = false; showNewFolderDialog = true }
                )
            }

            if (showNewFolderDialog) {
                TextInputDialog(
                    title = "New folder",
                    placeholder = "Folder name",
                    confirmLabel = "Create",
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    onConfirm = {
                        scope.launch {
                            val newId = repository.createTask(Task(type = TaskType.FOLDER, title = newFolderName))
                            task = task.copy(parentId = newId)
                            folders = repository.getFolders()
                            newFolderName = ""
                            showNewFolderDialog = false
                        }
                    },
                    onDismiss = { showNewFolderDialog = false }
                )
            }
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_CREATE_AS_FOLDER = "create_as_folder"
        const val EXTRA_CREATE_AS_PROJECT = "create_as_project"
        // An unsaved new task (Task JSON) to start from, e.g. quick add's "Edit all details".
        const val EXTRA_DRAFT = "draft"
        const val EXTRA_DRAFT_DEPENDS_ON = "draft_depends_on"
    }
}

@Composable
private fun <T> LabelOptions(
    options: List<Pair<T, String>>,
    selected: T,
    trailingPadding: Dp = 12.dp,
    verticalPadding: Dp = 4.dp,
    onSelect: (T) -> Unit
) {
    options.forEach { (value, label) ->
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected == value) LedgerAccent else LedgerMuted,
            modifier = Modifier
                .clickable { onSelect(value) }
                .padding(end = trailingPadding, top = verticalPadding, bottom = verticalPadding)
        )
    }
}

@Composable
private fun CompactNumberField(value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    BasicTextField(
        value = text,
        onValueChange = { new ->
            text = new
            new.toIntOrNull()?.takeIf { it > 0 }?.let(onValueChange)
        },
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = LedgerInk),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .width(44.dp)
            .border(1.dp, LedgerBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    )
}

// Whether a subtask sets its own value for an inherited field (dates/icon here; contexts are checked
// by the repository, which knows the assignments).
private fun overrides(task: Task, field: InheritedField): Boolean = when (field) {
    InheritedField.START -> task.startDate != null
    InheritedField.DUE -> task.dueDate != null
    InheritedField.ICON -> task.icon != null
    InheritedField.CONTEXTS -> true
}
