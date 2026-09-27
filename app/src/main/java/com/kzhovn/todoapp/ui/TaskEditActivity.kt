package com.kzhovn.todoapp.ui

import androidx.compose.material3.TextButton
import com.kzhovn.todoapp.data.splitItems
import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.ui.theme.folderColors
import com.kzhovn.todoapp.data.dueStatus
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.formatDuration
import com.kzhovn.todoapp.ui.theme.LedgerCheckBorder
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.kzhovn.todoapp.quickadd.QuickAddParser
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
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.launch

class TaskEditActivity : ComponentActivity() {
    @OptIn(ExperimentalLayoutApi::class)
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
            // A new, unsaved task can't have children yet: its subtasks wait here and are created on save.
            var pendingSubtasks by remember { mutableStateOf<List<String>>(emptyList()) }
            var showPendingSubtaskDialog by remember { mutableStateOf(false) }
            var pendingSubtaskTitle by remember { mutableStateOf("") }
            // A new task's other related tasks, linked on save (it has no id until then).
            var pendingChildIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
            var pendingDependentIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
            var pendingDependents by remember { mutableStateOf<List<String>>(emptyList()) }
            var showPendingDependentDialog by remember { mutableStateOf(false) }
            var pendingDependentTitle by remember { mutableStateOf("") }
            // A checklist's "Clear checked" and "Complete list" questions.
            var confirmClearChecked by remember { mutableStateOf(false) }
            var askUncheckedItems by remember { mutableStateOf(0) }
            var showNewBlockerDialog by remember { mutableStateOf(false) }
            var newBlockerTitle by remember { mutableStateOf("") }
            var showSubtaskPicker by remember { mutableStateOf(false) }
            var showPrereqPicker by remember { mutableStateOf(false) }
            var showRemindMenu by remember { mutableStateOf(false) }
            var showRepeatDialog by remember { mutableStateOf(false) }
            var showTimerDialog by remember { mutableStateOf(false) }
            var showContextMenu by remember { mutableStateOf(false) }
            // Pinning takes effect at once (it's "what I'm doing now"), not on Save; a new task's, on save.
            var pinned by remember { mutableStateOf(taskId != 0L && PinnedTask.pinnedId(this@TaskEditActivity) == taskId) }
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
                    toSave = task.copy(dueDate = null, recurrenceType = null, recurrenceRule = null, reminderOffsetMinutes = null, durationMinutes = null)
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
                    pendingSubtasks.forEach { repository.createTask(QuickAddParser.parse(it).copy(parentId = savedId)) }
                    pendingChildIds.forEach { repository.reparent(it, savedId) }
                    pendingDependentIds.forEach { repository.addDependency(it, savedId) }
                    // New dependents go in this task's folder, as related work usually belongs together.
                    val folderId = toSave.parentId?.takeIf { id -> allTasks.any { it.id == id && it.type == TaskType.FOLDER } }
                    pendingDependents.forEach { repository.addDependency(repository.createTask(QuickAddParser.parse(it).copy(parentId = folderId)), savedId) }
                    if (taskId == 0L && pinned) PinnedTask.pin(this@TaskEditActivity, toSave.copy(id = savedId))
                    TodoWidget().updateAll(applicationContext)
                    finish()
                }
            }

            val onSave: () -> Unit = {
                val hasSubtasks = pendingSubtasks.isNotEmpty() || pendingChildIds.isNotEmpty() || allTasks.any { it.parentId == taskId && taskId != 0L }
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
                // Title box: pin in front, then Maybe and the star after. It wraps (up to four lines),
                // with the icons staying level with the first line.
                val canPin = task.type == TaskType.TASK && !task.isComplete
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().border(1.dp, LedgerBorder, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp)
                ) {
                    if (canPin) {
                        IconButton(onClick = {
                            if (taskId != 0L) { if (pinned) PinnedTask.unpin(this@TaskEditActivity) else PinnedTask.pin(this@TaskEditActivity, task) }
                            pinned = !pinned
                        }) {
                            Icon(Icons.Filled.PushPin, contentDescription = Labels.PIN, tint = if (pinned) LedgerAccent else LedgerMuted, modifier = Modifier.size(20.dp))
                        }
                        Box(Modifier.padding(top = 10.dp).width(1.dp).height(28.dp).background(LedgerBorder))
                    }
                    BasicTextField(
                        value = task.title,
                        onValueChange = { task = task.copy(title = it.replace("\n", " ")) },
                        maxLines = 4,
                        textStyle = TextStyle(fontSize = 17.sp, color = LedgerInk),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            if (isLoaded && task.title.isNotBlank()) onSave()
                        }),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 11.dp),
                        decorationBox = { field ->
                            if (task.title.isEmpty()) Text(Labels.TITLE, fontSize = 17.sp, color = LedgerMuted)
                            field()
                        }
                    )
                    if (task.type != TaskType.FOLDER) {
                        // A maybe is never starred: turning either on turns the other off.
                        IconButton(onClick = { task = task.copy(isMaybe = !task.isMaybe, isStarred = task.isStarred && task.isMaybe) }) {
                            Text("?", fontWeight = FontWeight.Bold, fontSize = 19.sp, color = if (task.isMaybe) LedgerAccent else LedgerMuted)
                        }
                        IconButton(onClick = { task = task.copy(isStarred = !task.isStarred, isMaybe = task.isMaybe && task.isStarred) }) {
                            StarIcon(task.isStarred)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row {
                    Labels.TYPES.forEach { (type, label) ->
                        SelectablePill(label, selected = task.type == type) { task = task.copy(type = type) }
                        Spacer(Modifier.width(8.dp))
                    }
                }

                // Everything about when: dates, reminder, repeat, timer, and "today only".
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PropertyChip(
                        label = Labels.START,
                        valueText = task.startDate?.let(::formatChipDate),
                        icon = Icons.Filled.Event,
                        onClick = { pickDate(this@TaskEditActivity, task.startDate, title = Labels.START) { task = task.copy(startDate = it) } },
                        onClear = { task = task.copy(startDate = null) },
                        showLabelWhenSet = false
                    )
                    if (task.type != TaskType.FOLDER) {
                        PropertyChip(
                            label = Labels.DUE,
                            valueText = task.dueDate?.let(::formatChipDate),
                            icon = Icons.Filled.Flag,
                            onClick = { pickDate(this@TaskEditActivity, task.dueDate, title = Labels.DUE) { task = task.copy(dueDate = it) } },
                            onClear = { task = task.copy(dueDate = null) },
                            showLabelWhenSet = false
                        )
                    }
                    if (task.type == TaskType.TASK && task.dueDate != null) {
                        Box {
                            PropertyChip(
                                label = Labels.REMIND,
                                valueText = task.reminderOffsetMinutes?.let { offset -> Labels.REMINDERS.firstOrNull { it.first == offset }?.second },
                                icon = Icons.Filled.Notifications,
                                onClick = { showRemindMenu = true },
                                onClear = { task = task.copy(reminderOffsetMinutes = null) },
                                showLabelWhenSet = false
                            )
                            DropdownMenu(expanded = showRemindMenu, onDismissRequest = { showRemindMenu = false }) {
                                Labels.REMINDERS.forEach { (offset, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { task = task.copy(reminderOffsetMinutes = offset); showRemindMenu = false })
                                }
                            }
                        }
                    }
                    if (task.type != TaskType.FOLDER) {
                        PropertyChip(
                            // An imported rule the picker can't show still counts as set.
                            label = Labels.REPEAT,
                            valueText = Labels.repeat(recurrence) ?: task.recurrenceType?.let { "Custom" },
                            icon = Icons.Filled.Repeat,
                            onClick = { showRepeatDialog = true },
                            onClear = { recurrence = RecurrenceSelection(RecurrencePreset.NONE); task = task.copy(recurrenceType = null, recurrenceRule = null) },
                            showLabelWhenSet = false
                        )
                    }
                    if (task.type == TaskType.TASK) {
                        PropertyChip(
                            label = Labels.TIMER,
                            valueText = task.durationMinutes?.let(::formatDuration),
                            icon = Icons.Filled.Timer,
                            onClick = { showTimerDialog = true },
                            onClear = { task = task.copy(durationMinutes = null) },
                            showLabelWhenSet = false
                        )
                    }
                    if (task.type != TaskType.FOLDER) {
                        val toggleToday = {
                            val hour = AppSettings.rolloverHour(this@TaskEditActivity)
                            task = task.copy(expiresAt = if (task.expiresAt == null) nextRollover(System.currentTimeMillis(), hour) else null)
                        }
                        PropertyChip(
                            label = Labels.TODAY_ONLY,
                            valueText = Labels.TODAY_ONLY.takeIf { task.expiresAt != null },
                            icon = Icons.Filled.AcUnit,
                            onClick = toggleToday,
                            onClear = toggleToday,
                            showLabelWhenSet = false
                        )
                    }
                }

                SectionLabel(Labels.FOLDER_AND_CONTEXTS)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PropertyChip(
                        label = Labels.FOLDER,
                        valueText = allById[task.parentId]?.title,
                        icon = Icons.Filled.Folder,
                        onClick = { showFolderPicker = true },
                        showLabelWhenSet = false,
                        tint = task.parentId?.let { folderColors(allTasks)[it] } ?: LedgerAccent
                    )
                    // Folders keep contexts too: their tasks inherit them (e.g. Work only in work hours).
                    allContexts.filter { it.id in selectedContextIds }.sortedBy { it.name.lowercase() }.forEach { ctx ->
                        PropertyChip(
                            label = ctx.name,
                            valueText = ctx.name,
                            icon = Icons.Filled.AlternateEmail,
                            onClick = {},
                            onClear = { selectedContextIds = selectedContextIds - ctx.id },
                            showLabelWhenSet = false
                        )
                    }
                    Box {
                        PropertyChip(label = Labels.CONTEXT, valueText = null, icon = Icons.Filled.Add, onClick = { showContextMenu = true })
                        DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
                            allContexts.filter { it.id !in selectedContextIds }.sortedBy { it.name.lowercase() }.forEach { ctx ->
                                DropdownMenuItem(text = { Text("@${ctx.name}") }, onClick = { selectedContextIds = selectedContextIds + ctx.id; showContextMenu = false })
                            }
                            DropdownMenuItem(
                                text = { Text(Labels.MANAGE_CONTEXTS, color = LedgerAccent) },
                                onClick = { showContextMenu = false; startActivity(Intent(this@TaskEditActivity, ContextsActivity::class.java)) }
                            )
                        }
                    }
                }

                // Subtasks, what this task depends on (prerequisites), and what depends on it.
                val isChecklist = task.type == TaskType.CHECKLIST
                val openTask = { id: Long -> startActivity(Intent(this@TaskEditActivity, TaskEditActivity::class.java).putExtra(EXTRA_TASK_ID, id)) }
                if (isChecklist) {
                    // Items: a checkbox and a title each, open ones first; the add field stays for the next one.
                    SectionLabel(Labels.ITEMS)
                    val items = remember(allTasks, taskId) { if (taskId == 0L) emptyList() else checklistItems(taskId, allTasks) }
                    items.forEach { item ->
                        ItemRow(item.title, item.isComplete, onCheck = {
                            scope.launch {
                                repository.toggleComplete(item.id, System.currentTimeMillis())
                                allTasks = repository.getAllTasks()
                                TodoWidget().updateAll(applicationContext)
                            }
                        }) { scope.launch { repository.deleteTask(item); allTasks = repository.getAllTasks() } }
                    }
                    pendingSubtasks.forEachIndexed { index, title ->
                        ItemRow(title, false, onCheck = null) { pendingSubtasks = pendingSubtasks.filterIndexed { i, _ -> i != index } }
                    }
                    AddItemField { text ->
                        if (taskId == 0L) pendingSubtasks = pendingSubtasks + splitItems(text)
                        else scope.launch { repository.addItems(taskId, text); allTasks = repository.getAllTasks(); TodoWidget().updateAll(applicationContext) }
                    }
                    if (items.any { it.isComplete }) Row {
                        AddLink(Labels.UNCHECK_ALL) { scope.launch { repository.uncheckAll(taskId); allTasks = repository.getAllTasks(); TodoWidget().updateAll(applicationContext) } }
                        AddLink(Labels.CLEAR_CHECKED) { confirmClearChecked = true }
                    }
                }
                SectionLabel(Labels.RELATED_TASKS)
                val subtasks = remember(allTasks, taskId) { allTasks.filter { it.parentId == taskId && taskId != 0L && !isChecklist }.sortedWith(TaskOrder) }
                subtasks.forEach { sub ->
                    RelatedRow(Labels.SUBTASK, sub.title, done = sub.isComplete, onOpen = { openTask(sub.id) }) {
                        if (sub.type == TaskType.TASK) {
                            TaskCheckbox(checked = sub.isComplete, due = sub.dueDate?.takeUnless { sub.isComplete }?.let { dueStatus(it, System.currentTimeMillis()) }, size = 18.dp, touchSize = 34.dp, onCheckedChange = {
                                scope.launch {
                                    repository.toggleComplete(sub.id, System.currentTimeMillis())
                                    allTasks = repository.getAllTasks()
                                    TodoWidget().updateAll(applicationContext)
                                }
                            })
                        }
                    }
                }
                if (!isChecklist) pendingSubtasks.forEachIndexed { index, title ->
                    RelatedRow(Labels.SUBTASK, title, onOpen = null) { RemoveButton { pendingSubtasks = pendingSubtasks.filterIndexed { i, _ -> i != index } } }
                }
                pendingChildIds.mapNotNull(allById::get).forEach { child ->
                    RelatedRow(Labels.SUBTASK, child.title, onOpen = { openTask(child.id) }) { RemoveButton { pendingChildIds = pendingChildIds - child.id } }
                }
                if (task.type != TaskType.FOLDER) {
                    selectedDependencyIds.mapNotNull(allById::get).sortedBy { it.title.lowercase() }.forEach { prereq ->
                        RelatedRow(Labels.PREREQUISITE, prereq.title, done = prereq.isComplete, onOpen = { openTask(prereq.id) }) {
                            RemoveButton { selectedDependencyIds = selectedDependencyIds - prereq.id }
                        }
                    }
                    pendingDependentIds.mapNotNull(allById::get).forEach { dependent ->
                        RelatedRow(Labels.DEPENDENT, dependent.title, onOpen = { openTask(dependent.id) }) { RemoveButton { pendingDependentIds = pendingDependentIds - dependent.id } }
                    }
                    pendingDependents.forEachIndexed { index, title ->
                        RelatedRow(Labels.DEPENDENT, title, onOpen = null) { RemoveButton { pendingDependents = pendingDependents.filterIndexed { i, _ -> i != index } } }
                    }
                    allDependencyEdges.filter { it.dependsOnTaskId == taskId && taskId != 0L }.mapNotNull { allById[it.taskId] }.forEach { dependent ->
                        RelatedRow(Labels.DEPENDENT, dependent.title, done = dependent.isComplete, onOpen = { openTask(dependent.id) }) {
                            RemoveButton {
                                scope.launch {
                                    repository.removeDependency(dependent.id, taskId)
                                    allDependencyEdges = repository.getAllDependencyEdges()
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    if (!isChecklist) AddLink(Labels.ADD_SUBTASK) { showSubtaskPicker = true }
                    // A folder can't be completed, so it neither waits on tasks nor has any waiting on it.
                    if (task.type != TaskType.FOLDER) {
                        AddLink(Labels.ADD_PREREQUISITE) { showPrereqPicker = true }
                        AddLink(Labels.ADD_DEPENDENT) { showDependentPicker = true }
                    }
                }
                // Folders and tasks alike: only the first incomplete child counts as active.
                if (!isChecklist) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { task = task.copy(sequential = !task.sequential) }.padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = task.sequential,
                        onCheckedChange = { task = task.copy(sequential = it) },
                        colors = CheckboxDefaults.colors(checkedColor = LedgerAccent, uncheckedColor = LedgerCheckBorder)
                    )
                    Text(Labels.inOrder(task.type), fontSize = 13.sp, color = LedgerMuted)
                }
                // Normally a task waits on its open subtasks; this keeps it in Doing/Active anyway.
                if (task.type == TaskType.TASK) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { task = task.copy(activeWithSubtasks = !task.activeWithSubtasks) }.padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = task.activeWithSubtasks,
                        onCheckedChange = { task = task.copy(activeWithSubtasks = it) },
                        colors = CheckboxDefaults.colors(checkedColor = LedgerAccent, uncheckedColor = LedgerCheckBorder)
                    )
                    Text(Labels.ACTIVE_WITH_SUBTASKS, fontSize = 13.sp, color = LedgerMuted)
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        scope.launch {
                            descendantCount = if (taskId == 0L) 0 else repository.countDescendants(task.id)
                            showDeleteConfirm = true
                        }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = Labels.DELETE, tint = LedgerOverdue)
                    }
                    Spacer(Modifier.weight(1f))
                    // Checking the last item completes nothing; a checklist is completed here, on purpose.
                    if (isChecklist && taskId != 0L && !task.isComplete) TextButton(onClick = {
                        val unchecked = allTasks.count { it.parentId == taskId && !it.isComplete }
                        if (unchecked > 0) askUncheckedItems = unchecked
                        else scope.launch { repository.completeChecklist(taskId, false, System.currentTimeMillis()); TodoWidget().updateAll(applicationContext); finish() }
                    }) { Text(Labels.COMPLETE_LIST, color = LedgerAccent) }
                    Button(
                        onClick = onSave,
                        enabled = isLoaded && task.title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)
                    ) {
                        Text(Labels.SAVE)
                    }
                }
            }

            if (confirmClearChecked) {
                val checked = allTasks.count { it.parentId == taskId && it.isComplete }
                ConfirmDialog(
                    title = Labels.clearChecked(checked),
                    body = null,
                    confirmLabel = Labels.CLEAR_CHECKED,
                    onConfirm = {
                        confirmClearChecked = false
                        scope.launch { repository.clearChecked(taskId); allTasks = repository.getAllTasks(); TodoWidget().updateAll(applicationContext) }
                    },
                    onDismiss = { confirmClearChecked = false }
                )
            }

            if (askUncheckedItems > 0) {
                val complete = { moveToNewList: Boolean ->
                    askUncheckedItems = 0
                    scope.launch { repository.completeChecklist(taskId, moveToNewList, System.currentTimeMillis()); TodoWidget().updateAll(applicationContext); finish() }
                }
                AlertDialog(
                    onDismissRequest = { askUncheckedItems = 0 },
                    title = { Text(Labels.COMPLETE_LIST) },
                    text = { Text(Labels.uncheckedItems(askUncheckedItems)) },
                    confirmButton = { TextButton(onClick = { complete(true) }) { Text(Labels.MOVE_TO_NEW_LIST) } },
                    dismissButton = { TextButton(onClick = { complete(false) }) { Text(Labels.COMPLETE_THEM_TOO) } }
                )
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
                        // A new task isn't saved yet: deleting it just discards it.
                        if (taskId == 0L) finish() else scope.launch {
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
                        it.id != task.id && it.type != TaskType.FOLDER && !it.isComplete && (task.id == 0L || it.parentId != task.id) && it.id !in pendingChildIds &&
                            !wouldCreateCycle(task.id, it.id, allById)
                    }
                }
                TaskPickerDialog(
                    title = "Add a subtask",
                    tasks = candidates,
                    onPick = { picked ->
                        showSubtaskPicker = false
                        if (taskId == 0L) pendingChildIds = pendingChildIds + picked.id
                        else scope.launch {
                            repository.reparent(picked.id, task.id)
                            allTasks = repository.getAllTasks()
                            TodoWidget().updateAll(applicationContext)
                        }
                    },
                    onCreateNew = {
                        showSubtaskPicker = false
                        if (taskId == 0L) { pendingSubtaskTitle = ""; showPendingSubtaskDialog = true }
                        else startActivity(
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
                            (task.id == 0L || allDependencyEdges.none { e -> e.taskId == it.id && e.dependsOnTaskId == task.id }) && it.id !in pendingDependentIds &&
                            !wouldCreateDependencyCycle(task.id, it.id, allDependencyEdges)
                    }
                }
                TaskPickerDialog(
                    title = "Add a dependent task",
                    tasks = candidates,
                    onPick = { picked ->
                        showDependentPicker = false
                        if (taskId == 0L) pendingDependentIds = pendingDependentIds + picked.id
                        else scope.launch {
                            repository.addDependency(picked.id, task.id)
                            allDependencyEdges = repository.getAllDependencyEdges()
                            TodoWidget().updateAll(applicationContext)
                            Toast.makeText(this@TaskEditActivity, "“${picked.title}” now depends on this", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCreateNew = {
                        showDependentPicker = false
                        if (taskId == 0L) { pendingDependentTitle = ""; showPendingDependentDialog = true; return@TaskPickerDialog }
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

            if (showRepeatDialog) {
                RepeatSheet(
                    current = recurrence,
                    anchor = task.startDate ?: task.dueDate ?: System.currentTimeMillis(),
                    onDone = { recurrence = it; showRepeatDialog = false },
                    onDismiss = { showRepeatDialog = false }
                )
            }

            if (showTimerDialog) {
                AlertDialog(
                    onDismissRequest = { showTimerDialog = false },
                    title = { Text(Labels.TIMER) },
                    text = {
                        Column {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Labels.TIMER_PRESETS.forEach { minutes ->
                                    SelectablePill(formatDuration(minutes), selected = task.durationMinutes == minutes) { task = task.copy(durationMinutes = minutes) }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                                DurationField(task.durationMinutes) { task = task.copy(durationMinutes = it) }
                                Spacer(Modifier.width(6.dp))
                                Text("minutes", fontSize = 12.sp, color = LedgerMuted)
                            }
                        }
                    },
                    confirmButton = { Button(onClick = { showTimerDialog = false }) { Text("Done") } }
                )
            }

            // + Prerequisite: an existing task this one waits on, or a new one (the blocker dialog).
            if (showPrereqPicker) {
                val candidates = remember(allTasks, allDependencyEdges, task.id, selectedDependencyIds) {
                    allTasks.filter {
                        it.id != task.id && it.type == TaskType.TASK && !it.isComplete && it.id !in selectedDependencyIds &&
                            !wouldCreateDependencyCycle(it.id, task.id, allDependencyEdges)
                    }
                }
                TaskPickerDialog(
                    title = "Add a prerequisite",
                    tasks = candidates,
                    onPick = { picked -> selectedDependencyIds = selectedDependencyIds + picked.id; showPrereqPicker = false },
                    onCreateNew = { showPrereqPicker = false; newBlockerTitle = ""; showNewBlockerDialog = true },
                    onDismiss = { showPrereqPicker = false }
                )
            }

            if (showPendingDependentDialog) {
                TextInputDialog(
                    title = "Add a dependent task",
                    placeholder = Labels.DEPENDENT,
                    confirmLabel = "Add",
                    value = pendingDependentTitle,
                    onValueChange = { pendingDependentTitle = it },
                    onConfirm = {
                        pendingDependentTitle.trim().takeIf { it.isNotEmpty() }?.let { pendingDependents = pendingDependents + it }
                        showPendingDependentDialog = false
                    },
                    onDismiss = { showPendingDependentDialog = false }
                )
            }

            if (showPendingSubtaskDialog) {
                TextInputDialog(
                    title = "Add a subtask",
                    placeholder = "Subtask",
                    confirmLabel = "Add",
                    value = pendingSubtaskTitle,
                    onValueChange = { pendingSubtaskTitle = it },
                    onConfirm = {
                        pendingSubtaskTitle.trim().takeIf { it.isNotEmpty() }?.let { pendingSubtasks = pendingSubtasks + it }
                        showPendingSubtaskDialog = false
                    },
                    onDismiss = { showPendingSubtaskDialog = false }
                )
            }

            // "Depends on" → Create new: the blocker is created right away (in this task's folder, as
            // dependent work usually belongs together) and ticked; the dependency itself saves with the task.
            if (showNewBlockerDialog) {
                TextInputDialog(
                    title = "New task this depends on",
                    placeholder = "Task",
                    confirmLabel = "Add",
                    value = newBlockerTitle,
                    onValueChange = { newBlockerTitle = it },
                    onConfirm = {
                        showNewBlockerDialog = false
                        val parsed = QuickAddParser.parse(newBlockerTitle)
                        if (parsed.title.isNotBlank()) scope.launch {
                            val folderId = task.parentId?.takeIf { allById[it]?.type == TaskType.FOLDER }
                            val id = repository.createTask(parsed.copy(parentId = folderId))
                            selectedDependencyIds = selectedDependencyIds + id
                            allTasks = repository.getAllTasks()
                        }
                    },
                    onDismiss = { showNewBlockerDialog = false }
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

// A checklist item: checkbox, title (struck through once checked), ✕. A pending one (the checklist
// isn't saved yet) has no checkbox.
@Composable
private fun ItemRow(title: String, checked: Boolean, onCheck: (() -> Unit)?, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)) {
        if (onCheck != null) TaskCheckbox(checked = checked, due = null, size = 18.dp, touchSize = 34.dp, onCheckedChange = onCheck)
        else Spacer(Modifier.width(34.dp))
        Text(
            title, fontSize = 14.sp, color = if (checked) LedgerMuted else LedgerInk,
            textDecoration = if (checked) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f)
        )
        RemoveButton(onRemove)
    }
}

// Stays focused after each add, for typing a list in one go; "milk, eggs" adds two.
@Composable
private fun AddItemField(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = LedgerInk),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { onAdd(text); text = "" } }),
        modifier = Modifier.fillMaxWidth().padding(start = 34.dp, top = 6.dp, bottom = 6.dp),
        decorationBox = { field ->
            if (text.isEmpty()) Text("+ ${Labels.ADD_ITEM}", fontSize = 14.sp, color = LedgerMuted)
            field()
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerInk, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

// One related task: what it is to this one (Subtask, Prerequisite, Dependent), its title (tap opens
// it), and a trailing control (a checkbox or ✕).
@Composable
private fun RelatedRow(kind: String, title: String, done: Boolean = false, onOpen: (() -> Unit)?, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)) {
        Text(kind, fontSize = 11.sp, color = LedgerMuted, modifier = Modifier.width(84.dp))
        Text(
            title,
            fontSize = 14.sp,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            color = if (done) LedgerMuted else LedgerInk,
            modifier = Modifier.weight(1f).then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier).padding(vertical = 6.dp)
        )
        trailing()
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = LedgerMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun AddLink(text: String, onClick: () -> Unit) {
    Text(text, color = LedgerAccent, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClick).padding(end = 20.dp, top = 6.dp, bottom = 6.dp))
}

// Like CompactNumberField, but empty means "not timed".
@Composable
private fun DurationField(value: Int?, onValueChange: (Int?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    BasicTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter(Char::isDigit).take(4)
            onValueChange(text.toIntOrNull()?.takeIf { it > 0 })
        },
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = LedgerInk),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .width(56.dp)
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
