package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.quickadd.startOfDay
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DateRange
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Column
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.AppSettings
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.formatDuration
import com.kzhovn.todoapp.data.nextRollover
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.folderColors

// The task editor's sections, top to bottom; the state and data work live in TaskEditViewModel.

// Title box: pin in front, the star after; the title wraps (up to four lines). Everything is centred
// on one 44dp line, so a one-line title sits level with the icons. The note sits under it (NotesArea).
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TitleBox(vm: TaskEditViewModel, pinned: Boolean, onTogglePin: () -> Unit, onFocus: () -> Unit, onDone: () -> Unit) {
    val task = vm.task
    val focusManager = LocalFocusManager.current
    var titleFocused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, LedgerBorder, RoundedCornerShape(6.dp))) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 2.dp)
    ) {
        if (task.type == TaskType.TASK && !task.isComplete) {
            // Tap pins; press and hold opens focus mode on this task (once it's saved).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp).clip(CircleShape).combinedClickable(onClick = onTogglePin, onLongClick = onFocus)
            ) {
                Icon(Icons.Filled.PushPin, contentDescription = Labels.PIN, tint = if (pinned) LedgerAccent else LedgerMuted, modifier = Modifier.size(19.dp))
            }
            Box(Modifier.width(1.dp).height(24.dp).background(LedgerBorder))
        }
        BasicTextField(
            value = task.title,
            onValueChange = { vm.task = vm.task.copy(title = it.replace("\n", " ")) },
            maxLines = 4,
            textStyle = TextStyle(fontSize = 17.sp, color = LedgerInk),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); onDone() }),
            modifier = Modifier.weight(1f).padding(start = 8.dp, end = 2.dp, top = 10.dp, bottom = 10.dp).onFocusChanged { titleFocused = it.isFocused },
            decorationBox = { field ->
                if (task.title.isEmpty()) Text(Labels.TITLE, fontSize = 17.sp, color = LedgerMuted)
                field()
            }
        )
        // A starred task is never a maybe: turning either on turns the other off (Maybe is under Properties).
        if (task.type != TaskType.FOLDER) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(38.dp).clip(CircleShape).clickable { vm.task = task.copy(isStarred = !task.isStarred, isMaybe = task.isMaybe && task.isStarred) }
            ) { StarIcon(task.isStarred) }
        }
    }
    NotesArea(task.notes.orEmpty(), titleFocused) { vm.task = vm.task.copy(notes = it) }
    }
}

@Composable
internal fun TypeRow(vm: TaskEditViewModel) {
    Spacer(Modifier.height(10.dp))
    JoinedChoice(Labels.TYPES.map { it.second }, Labels.TYPES.indexOfFirst { it.first == vm.task.type }) { i -> vm.task = vm.task.copy(type = Labels.TYPES[i].first) }
}

// High (!), normal or maybe (?). A maybe is hidden from Active and Doing and never starred.
@Composable
internal fun PrioritySection(vm: TaskEditViewModel) {
    if (vm.task.type == TaskType.FOLDER) return
    SectionLabel(Labels.PRIORITY)
    val task = vm.task
    JoinedChoice(Labels.PRIORITIES.map { it.third }, Labels.PRIORITIES.indexOfFirst { (high, maybe) -> task.isHighPriority == high && task.isMaybe == maybe }) { i ->
        val (high, maybe) = Labels.PRIORITIES[i]
        vm.task = task.copy(isHighPriority = high, isMaybe = maybe, isStarred = task.isStarred && !maybe)
    }
}

// One joined bar, the chosen one filled: a pick-one set, unlike the chips.
@Composable
private fun JoinedChoice(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).border(1.dp, LedgerBorder, shape)) {
        labels.forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(LedgerBorder))
            val on = i == selected
            Text(
                label, fontSize = 13.sp, color = if (on) LedgerAccentInk else LedgerMuted, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).background(if (on) LedgerAccent else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onPick(i) }
                    .padding(vertical = 7.dp)
            )
        }
    }
}

// An on/off choice: a pill holding a small switch, the choice's icon and its label. Set apart from
// the chips (whose tap opens a picker) by its switch and round outline.
@Composable
private fun TogglePill(label: String, icon: ImageVector, on: Boolean, onToggle: () -> Unit) {
    val color = if (on) LedgerAccent else LedgerMuted
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(shape).background(if (on) LedgerAccentSoft else Color.Transparent)
            .border(1.dp, if (on) LedgerAccent else LedgerBorder, shape)
            .toggleable(value = on, role = Role.Switch) { onToggle() }
            .padding(start = 5.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)
    ) {
        Box(
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            modifier = Modifier.size(width = 24.dp, height = 14.dp).clip(shape).background(if (on) LedgerAccent else LedgerBorder).padding(2.dp)
        ) { Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White)) }
        Spacer(Modifier.width(5.dp))
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = color)
    }
}

// Due dates are days: today, tomorrow, a week from today (no time).
private val dueChoices = listOf(
    DateChoice("Today", Icons.Filled.Today) { day(it, 0) },
    DateChoice(Labels.SNOOZE_TOMORROW, Icons.Filled.Bedtime) { day(it, 1) },
    DateChoice("Next week", Icons.Filled.DateRange) { day(it, 7) }
)

private fun day(now: Long, plusDays: Int) = java.util.Calendar.getInstance().apply { timeInMillis = now; add(java.util.Calendar.DAY_OF_YEAR, plusDays) }.startOfDay()

@Composable
private fun QuickDateMenu(expanded: Boolean, label: String, choices: List<DateChoice>, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = LedgerSearchBackground) {
        Column(Modifier.width(236.dp)) {
            Text(label, fontSize = 11.sp, color = LedgerMuted, letterSpacing = 0.4.sp, modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 6.dp))
            DateTiles(choices) { onDismiss(); onPick(it) }
        }
    }
}

// Everything about when: dates, reminder, repeat, timer, and "today only".
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimingSection(vm: TaskEditViewModel, activity: Activity, onRepeat: () -> Unit, onTimer: () -> Unit) {
    val task = vm.task
    SectionLabel(Labels.TIMING)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // A long press offers quick choices: the snooze menu's for Start, today/tomorrow/next week for Due.
        var quickStart by remember { mutableStateOf(false) }
        Box {
            PropertyChip(
                label = Labels.START,
                valueText = task.startDate?.let(::formatChipDate),
                icon = Icons.Filled.Event,
                onClick = { pickDate(activity, task.startDate, title = Labels.START) { vm.task = vm.task.copy(startDate = it) } },
                onClear = { vm.task = vm.task.copy(startDate = null) },
                showLabelWhenSet = false,
                onLongClick = { quickStart = true }
            )
            QuickDateMenu(quickStart, Labels.START, snoozeChoices(AppSettings.rolloverHour(activity)), onDismiss = { quickStart = false }) {
                vm.task = vm.task.copy(startDate = it)
            }
        }
        if (task.type != TaskType.FOLDER) {
            var quickDue by remember { mutableStateOf(false) }
            Box {
                PropertyChip(
                    label = Labels.DUE,
                    valueText = task.dueDate?.let(::formatChipDate),
                    icon = Icons.Filled.Flag,
                    onClick = { pickDate(activity, task.dueDate, title = Labels.DUE) { vm.task = vm.task.copy(dueDate = it) } },
                    onClear = { vm.task = vm.task.copy(dueDate = null) },
                    showLabelWhenSet = false,
                    onLongClick = { quickDue = true }
                )
                QuickDateMenu(quickDue, Labels.DUE, dueChoices, onDismiss = { quickDue = false }) { vm.task = vm.task.copy(dueDate = it) }
            }
        }
        if (task.type == TaskType.TASK && task.dueDate != null) {
            var showRemindMenu by remember { mutableStateOf(false) }
            Box {
                PropertyChip(
                    label = Labels.REMIND,
                    valueText = task.reminderOffsetMinutes?.let { offset -> Labels.REMINDERS.firstOrNull { it.first == offset }?.second },
                    icon = Icons.Filled.Notifications,
                    onClick = { showRemindMenu = true },
                    onClear = { vm.task = vm.task.copy(reminderOffsetMinutes = null) },
                    showLabelWhenSet = false
                )
                DropdownMenu(expanded = showRemindMenu, onDismissRequest = { showRemindMenu = false }) {
                    Labels.REMINDERS.forEach { (offset, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { vm.task = vm.task.copy(reminderOffsetMinutes = offset); showRemindMenu = false })
                    }
                }
            }
        }
        if (task.type != TaskType.FOLDER) {
            PropertyChip(
                // An imported rule the picker can't show still counts as set.
                label = Labels.REPEAT,
                valueText = Labels.repeat(vm.recurrence) ?: task.recurrenceType?.let { "Custom" },
                icon = Icons.Filled.Repeat,
                onClick = onRepeat,
                onClear = { vm.recurrence = RecurrenceSelection(RecurrencePreset.NONE); vm.task = vm.task.copy(recurrenceType = null, recurrenceRule = null) },
                showLabelWhenSet = false
            )
        }
        if (task.type == TaskType.TASK) {
            PropertyChip(
                label = Labels.TIMER,
                valueText = task.durationMinutes?.let(::formatDuration),
                icon = Icons.Filled.Timer,
                onClick = onTimer,
                onClear = { vm.task = vm.task.copy(durationMinutes = null) },
                showLabelWhenSet = false
            )
        }
        if (task.type != TaskType.FOLDER) {
            val toggleToday = {
                val hour = AppSettings.rolloverHour(activity)
                vm.task = vm.task.copy(expiresAt = if (vm.task.expiresAt == null) nextRollover(System.currentTimeMillis(), hour) else null)
            }
            TogglePill(Labels.TODAY_ONLY, Icons.Filled.AcUnit, on = task.expiresAt != null, onToggle = toggleToday)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PropertiesSection(vm: TaskEditViewModel, onPickFolder: () -> Unit, onManageContexts: () -> Unit) {
    val task = vm.task
    var showContextMenu by remember { mutableStateOf(false) }
    SectionLabel(Labels.PROPERTIES)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PropertyChip(
            label = Labels.FOLDER,
            valueText = vm.allById[task.parentId]?.title,
            icon = Icons.Filled.Folder,
            onClick = onPickFolder,
            showLabelWhenSet = false,
            tint = task.parentId?.let { folderColors(vm.allTasks)[it] } ?: LedgerAccent
        )
        // Folders keep contexts too: their tasks inherit them (e.g. Work only in work hours).
        vm.allContexts.filter { it.id in vm.contextIds }.sortedBy { it.name.lowercase() }.forEach { ctx ->
            PropertyChip(
                label = ctx.name,
                valueText = ctx.name,
                icon = Icons.Filled.AlternateEmail,
                onClick = {},
                onClear = { vm.contextIds = vm.contextIds - ctx.id },
                showLabelWhenSet = false
            )
        }
        Box {
            PropertyChip(label = Labels.CONTEXT, valueText = null, icon = Icons.Filled.Add, onClick = { showContextMenu = true })
            DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
                vm.allContexts.filter { it.id !in vm.contextIds }.sortedBy { it.name.lowercase() }.forEach { ctx ->
                    DropdownMenuItem(text = { Text("@${ctx.name}") }, onClick = { vm.contextIds = vm.contextIds + ctx.id; showContextMenu = false })
                }
                DropdownMenuItem(
                    text = { Text(Labels.MANAGE_CONTEXTS, color = LedgerAccent) },
                    onClick = { showContextMenu = false; onManageContexts() }
                )
            }
        }
    }
}

// A checklist's items: a checkbox and a title each, open ones first; the add field stays for the next one.
@Composable
internal fun ItemsSection(vm: TaskEditViewModel, onClearChecked: () -> Unit) {
    SectionLabel(Labels.ITEMS)
    val items = remember(vm.allTasks, vm.taskId) { if (vm.isNew) emptyList() else checklistItems(vm.taskId, vm.allTasks) }
    items.forEach { item -> ItemRow(item.title, item.isComplete, onCheck = { vm.toggleComplete(item.id) }) { vm.deleteItem(item) } }
    vm.pendingSubtasks.forEachIndexed { index, title ->
        ItemRow(title, false, onCheck = null) { vm.pendingSubtasks = vm.pendingSubtasks.filterIndexed { i, _ -> i != index } }
    }
    AddItemField(vm::addItems)
    if (items.any { it.isComplete }) Row {
        AddLink(Labels.UNCHECK_ALL) { vm.uncheckAll() }
        AddLink(Labels.CLEAR_CHECKED, onClearChecked)
    }
}

// Subtasks, what this task depends on (prerequisites), and what depends on it.
@Composable
internal fun RelatedSection(vm: TaskEditViewModel, openTask: (Long) -> Unit, onAddSubtask: () -> Unit, onAddPrerequisite: () -> Unit, onAddDependent: () -> Unit) {
    val task = vm.task
    val isChecklist = task.type == TaskType.CHECKLIST
    SectionLabel(Labels.RELATED)
    val subtasks = remember(vm.allTasks, vm.taskId, isChecklist) { vm.allTasks.filter { it.parentId == vm.taskId && !vm.isNew && !isChecklist }.sortedWith(TaskOrder) }
    val isFolder = task.type == TaskType.FOLDER
    val dependentIds = if (isFolder || vm.isNew) emptySet() else vm.dependencyEdges.filter { it.dependsOnTaskId == vm.taskId }.map { it.taskId }.toSet()
    val prerequisiteIds = if (isFolder) emptySet() else vm.dependencyIds
    subtasks.forEach { sub ->
        val kind = when (sub.id) { in prerequisiteIds -> Labels.PREREQUISITE_SUBTASK; in dependentIds -> Labels.DEPENDENT_SUBTASK; else -> Labels.SUBTASK }
        RelatedRow(kind, sub.title, done = sub.isComplete, onOpen = { openTask(sub.id) }, leading = {
            if (sub.type == TaskType.TASK) {
                TaskCheckbox(
                    checked = sub.isComplete, due = sub.dueDate?.takeUnless { sub.isComplete }?.let { dueStatus(it, System.currentTimeMillis()) },
                    size = 18.dp, touchSize = 34.dp, onCheckedChange = { vm.toggleComplete(sub.id) }
                )
            } else Spacer(Modifier.width(34.dp))
        }) {
            // Removing unlinks the dependency; the subtask stays.
            if (sub.id in prerequisiteIds) RemoveButton { vm.dependencyIds = vm.dependencyIds - sub.id }
            else if (sub.id in dependentIds) RemoveButton { vm.removeDependent(sub.id) }
        }
    }
    val subtaskIds = subtasks.map { it.id }.toSet()
    if (!isChecklist) vm.pendingSubtasks.forEachIndexed { index, title ->
        RelatedRow(Labels.SUBTASK, title, onOpen = null) { RemoveButton { vm.pendingSubtasks = vm.pendingSubtasks.filterIndexed { i, _ -> i != index } } }
    }
    vm.pendingChildIds.mapNotNull(vm.allById::get).forEach { child ->
        RelatedRow(Labels.SUBTASK, child.title, onOpen = { openTask(child.id) }) { RemoveButton { vm.pendingChildIds = vm.pendingChildIds - child.id } }
    }
    if (task.type != TaskType.FOLDER) {
        (vm.dependencyIds - subtaskIds).mapNotNull(vm.allById::get).sortedBy { it.title.lowercase() }.forEach { prereq ->
            RelatedRow(Labels.PREREQUISITE, prereq.title, done = prereq.isComplete, onOpen = { openTask(prereq.id) }) {
                RemoveButton { vm.dependencyIds = vm.dependencyIds - prereq.id }
            }
        }
        vm.pendingDependentIds.mapNotNull(vm.allById::get).forEach { dependent ->
            RelatedRow(Labels.DEPENDENT, dependent.title, onOpen = { openTask(dependent.id) }) { RemoveButton { vm.pendingDependentIds = vm.pendingDependentIds - dependent.id } }
        }
        (dependentIds - subtaskIds).mapNotNull(vm.allById::get).forEach { dependent ->
            RelatedRow(Labels.DEPENDENT, dependent.title, done = dependent.isComplete, onOpen = { openTask(dependent.id) }) {
                RemoveButton { vm.removeDependent(dependent.id) }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row {
        if (!isChecklist) AddLink(Labels.ADD_SUBTASK, onAddSubtask)
        // A folder can't be completed, so it neither waits on tasks nor has any waiting on it.
        if (task.type != TaskType.FOLDER) {
            AddLink(Labels.ADD_PREREQUISITE, onAddPrerequisite)
            AddLink(Labels.ADD_DEPENDENT, onAddDependent)
        }
    }
}

// A checklist has neither option (its items are ticked in any order and never block it).
@Composable
internal fun SubtaskOptions(vm: TaskEditViewModel) {
    val task = vm.task
    if (task.type == TaskType.CHECKLIST) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        // Only the first incomplete child counts as active.
        TogglePill(Labels.SEQUENTIAL, Icons.Filled.FormatListNumbered, on = task.sequential) { vm.task = task.copy(sequential = !task.sequential) }
        // Normally a task waits on its open subtasks; this keeps it in Doing/Active anyway.
        if (task.type == TaskType.TASK) TogglePill(Labels.ACTIVE_WITH_SUBTASKS, Icons.Filled.Bolt, on = task.activeWithSubtasks) {
            vm.task = task.copy(activeWithSubtasks = !task.activeWithSubtasks)
        }
    }
}

@Composable
internal fun BottomBar(vm: TaskEditViewModel, onDelete: () -> Unit, onCompleteList: () -> Unit, onSave: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = Labels.DELETE, tint = LedgerOverdue) }
        Spacer(Modifier.weight(1f))
        // Checking the last item completes nothing; a checklist is completed here, on purpose.
        if (vm.task.type == TaskType.CHECKLIST && !vm.isNew && !vm.task.isComplete) TextButton(onClick = onCompleteList) { Text(Labels.COMPLETE_LIST, color = LedgerAccent) }
        Button(
            onClick = onSave,
            enabled = vm.isLoaded && vm.task.title.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)
        ) { Text(Labels.SAVE) }
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

// One related task: what it is to this one (Subtask, Prerequisite, Dependent), a subtask's checkbox
// (in front of the title, as every checkbox is; other rows keep its space so titles line up), its
// title (tap opens it), and a trailing ✕ where it can be removed.
@Composable
private fun RelatedRow(kind: String, title: String, done: Boolean = false, onOpen: (() -> Unit)?, leading: @Composable () -> Unit = { Spacer(Modifier.width(34.dp)) }, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)) {
        Text(kind, fontSize = 11.sp, color = LedgerMuted, modifier = Modifier.width(84.dp))
        leading()
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
internal fun DurationField(value: Int?, onValueChange: (Int?) -> Unit) {
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
        modifier = Modifier.width(56.dp).border(1.dp, LedgerBorder, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 8.dp)
    )
}
