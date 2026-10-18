package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.DueDates
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.dueText
import androidx.compose.ui.text.font.FontStyle
import com.kzhovn.todoapp.data.resolvesText
import com.kzhovn.todoapp.data.isTimedWait
import androidx.compose.material3.Switch
import com.kzhovn.todoapp.repository.stepAfter
import com.kzhovn.todoapp.data.chipDate
import com.kzhovn.todoapp.data.ReminderKind
import com.kzhovn.todoapp.data.reminderTimes
import com.kzhovn.todoapp.data.reminderSummary
import com.kzhovn.todoapp.data.reminderTimeText
import androidx.compose.material3.HorizontalDivider
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
import com.kzhovn.todoapp.data.DueChoice
import com.kzhovn.todoapp.data.checkInEvery
import com.kzhovn.todoapp.data.completed
import com.kzhovn.todoapp.data.checkInText
import com.kzhovn.todoapp.data.nextCheckIn
import com.kzhovn.todoapp.data.stillWaiting
import com.kzhovn.todoapp.data.withCheckInEvery
import com.kzhovn.todoapp.data.ancestors
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.AppSettings
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.TaskType
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
import com.kzhovn.todoapp.ui.theme.LedgerGood
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
        if (task.type.isDoable && !task.isComplete && !task.isTemplate) {
            // Tap pins; press and hold opens focus mode on this task or checklist (once it's saved).
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
        // A waiting item isn't yours to do, so it has no star.
        if (task.type != TaskType.FOLDER && task.type != TaskType.WAITING) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(38.dp).clip(CircleShape).clickable { vm.task = task.toggledStar() }
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

// A waiting item's timing: how often it comes up for a check-in, or the day it resolves itself (then
// no check-ins).
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaitingTiming(vm: TaskEditViewModel, activity: Activity, onTimer: () -> Unit) {
    val task = vm.task
    SectionLabel(Labels.WAITING)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var showCheckIn by remember { mutableStateOf(false) }
        PropertyChip(
            label = Labels.CHECK_IN,
            valueText = if (task.dueDate == null) Labels.checkIn(task.checkInEvery()) else null,
            icon = Icons.Filled.Repeat,
            onClick = { if (task.dueDate == null) showCheckIn = true },
            showLabelWhenSet = false
        )
        if (showCheckIn) {
            val now = System.currentTimeMillis()
            CheckInSheet(
                task.checkInEvery(), nextCheckIn(task, now), now,
                onEvery = { vm.task = vm.task.withCheckInEvery(it, now) },
                onNext = { vm.task = vm.task.copy(startDate = it) },
                onDismiss = { showCheckIn = false }
            )
        }
        PropertyChip(
            label = Labels.RESOLVES_ON,
            valueText = task.dueDate?.let { "Resolves ${chipDate(it)}" },
            icon = Icons.Filled.Event,
            onClick = { pickDate(activity, task.dueDate, title = Labels.RESOLVES_ON) { vm.task = vm.task.copy(dueDate = it) } },
            onClear = { vm.task = vm.task.copy(dueDate = null) },
            showLabelWhenSet = false
        )
        // A timed wait (a workflow's 50 minutes) resolves itself that long after it starts, with a reminder then.
        PropertyChip(
            label = Labels.TIMER,
            valueText = task.durationMinutes?.let(::formatDuration),
            icon = Icons.Filled.Timer,
            onClick = onTimer,
            onClear = { vm.task = vm.task.copy(durationMinutes = null) },
            showLabelWhenSet = false
        )
        if (task.isTimedWait && task.dueDate != null) TogglePill(Labels.REMIND_WHEN_DONE, Icons.Filled.Notifications, on = task.remindAt != null) {
            vm.task = vm.task.copy(remindAt = if (vm.task.remindAt == null) vm.task.dueDate else null)
        }
    }
    val now = System.currentTimeMillis()
    val resolves = resolvesText(task)
    if (resolves != null) Text(resolves, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(top = 6.dp))
    else if (!vm.isNew) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(checkInText(task, now), fontSize = 12.sp, color = LedgerMuted)
            // Restarts the clock: the next check-in an interval from today (saved with the rest on leaving).
            Text(
                Labels.STILL_WAITING, fontSize = 12.sp, color = LedgerAccent,
                modifier = Modifier.padding(start = 8.dp).border(1.dp, LedgerAccent, RoundedCornerShape(5.dp)).clip(RoundedCornerShape(5.dp))
                    .clickable { vm.task = stillWaiting(vm.task, System.currentTimeMillis()) }.padding(horizontal = 9.dp, vertical = 3.dp)
            )
        }
}

// High (!), normal or maybe (?). A maybe is hidden from Active and Doing and never starred.
@Composable
internal fun PrioritySection(vm: TaskEditViewModel) {
    if (vm.task.type == TaskType.FOLDER || vm.task.type == TaskType.WAITING) return
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
internal fun TogglePill(label: String, icon: ImageVector, on: Boolean, onToggle: () -> Unit) {
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

// See DueChoice (shared with the web).
private val dueChoices = DueChoice.entries.map { c ->
    DateChoice(c.label, when (c) { DueChoice.TODAY -> Icons.Filled.Today; DueChoice.TOMORROW -> Icons.Filled.Bedtime; DueChoice.NEXT_WEEK -> Icons.Filled.DateRange }) { c.at(it) }
}

// A date chip: a tap picks a date (and time), ✕ clears it, a long press offers `choices`.
@Composable
private fun DateChip(activity: Activity, label: String, icon: ImageVector, value: Long?, choices: List<DateChoice>, set: (Long?) -> Unit) {
    var quick by remember { mutableStateOf(false) }
    Box {
        PropertyChip(
            label = label, valueText = value?.let(::chipDate), icon = icon,
            onClick = { pickDate(activity, value, title = label) { set(it) } },
            onClear = { set(null) },
            showLabelWhenSet = false,
            onLongClick = { quick = true }
        )
        QuickDateMenu(quick, label, choices, onDismiss = { quick = false }) { set(it) }
    }
}

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
    if (task.type == TaskType.WAITING) return WaitingTiming(vm, activity, onTimer)
    SectionLabel(Labels.TIMING)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // A long press offers quick choices: the snooze menu's for Start, today/tomorrow/next week for Due.
        DateChip(activity, Labels.START, Icons.Filled.Event, task.startDate, snoozeChoices(AppSettings.rolloverHour(activity))) { vm.task = vm.task.copy(startDate = it) }
        if (task.type != TaskType.FOLDER) DateChip(activity, Labels.DUE, Icons.Filled.Flag, task.dueDate, dueChoices) { vm.task = vm.task.copy(dueDate = it) }
        // Reminders: when it starts (if that's still to come), before it's due (with a due date), and/or at
        // a time of its own. A date with no time rings at the reminder hour in Settings.
        if (task.type == TaskType.TASK) {
            var showRemindMenu by remember { mutableStateOf(false) }
            val hour = AppSettings.reminderHour(activity)
            val now = System.currentTimeMillis()
            val startAt = task.startDate?.let { reminderTimes(task.copy(remindAtStart = true), hour)[ReminderKind.START] }
            Box {
                PropertyChip(
                    label = Labels.REMIND,
                    valueText = reminderSummary(task, hour),
                    icon = Icons.Filled.Notifications,
                    onClick = { showRemindMenu = true },
                    onClear = { vm.task = vm.task.copy(reminderOffsetMinutes = null, remindAtStart = false, remindAt = null) },
                    showLabelWhenSet = false
                )
                DropdownMenu(expanded = showRemindMenu, onDismissRequest = { showRemindMenu = false }, containerColor = LedgerSearchBackground) {
                    @Composable
                    fun Item(text: String, on: Boolean, enabled: Boolean = true, trailing: String? = null, onClick: () -> Unit) = DropdownMenuItem(
                        text = { Text(text, fontWeight = if (on) FontWeight.SemiBold else null) },
                        leadingIcon = { Text(if (on) "✓" else "", color = LedgerAccent, modifier = Modifier.width(14.dp)) },
                        trailingIcon = trailing?.let { { Text(it, fontSize = 12.sp, color = LedgerMuted) } },
                        enabled = enabled, onClick = onClick
                    )
                    // Offered while the start is still to come (or to turn one off).
                    if (task.remindAtStart || (startAt != null && startAt > now)) {
                        Item("When it starts", task.remindAtStart, trailing = startAt?.let(::reminderTimeText)) { vm.task = vm.task.copy(remindAtStart = !task.remindAtStart) }
                        HorizontalDivider(color = LedgerBorder)
                    }
                    Text("BEFORE IT'S DUE", fontSize = 11.sp, color = LedgerMuted, letterSpacing = 0.4.sp, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp))
                    Labels.REMINDERS.filter { it.first != null }.forEach { (offset, label) ->
                        Item(label, task.reminderOffsetMinutes == offset, enabled = task.dueDate != null) {
                            vm.task = vm.task.copy(reminderOffsetMinutes = offset.takeUnless { it == task.reminderOffsetMinutes }); showRemindMenu = false
                        }
                    }
                    if (task.dueDate == null) Text("Set a due date for these", fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(start = 40.dp, bottom = 6.dp))
                    HorizontalDivider(color = LedgerBorder)
                    Item(if (task.remindAt != null) "At ${reminderTimeText(task.remindAt!!)}" else "At a time…", task.remindAt != null) {
                        showRemindMenu = false
                        pickDate(activity, task.remindAt, title = Labels.REMIND) { vm.task = vm.task.copy(remindAt = it) }
                    }
                    HorizontalDivider(color = LedgerBorder)
                    Item("No reminder", false) { vm.task = vm.task.copy(reminderOffsetMinutes = null, remindAtStart = false, remindAt = null); showRemindMenu = false }
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
    // Not set here, but shown as its due date: a task waiting on this one is due sooner. Worked out over every
    // task, so only again when what it goes by changes (not on each keystroke in the title).
    val dueFor = remember(vm.allTasks, vm.dependencyEdges, task.dueDate, task.parentId, task.isComplete) {
        if (vm.isNew || task.isComplete) null else DueDates(vm.allTasks.map { if (it.id == task.id) task else it }, vm.dependencyEdges).of(task)?.takeIf { it.forTask != null }
    }
    dueFor?.let { Text(Labels.dueFromWaiting(dueText(it.at, System.currentTimeMillis()), it.forTask!!.title), fontSize = 12.sp, color = LedgerMuted, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 6.dp)) }
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
            // Under a project or checklist (or another task), its icon, not a folder's.
            icon = when (vm.allById[task.parentId]?.type) { null, TaskType.FOLDER -> Icons.Filled.Folder; TaskType.CHECKLIST -> Icons.Filled.Checklist; else -> Icons.Filled.AccountTree },
            onClick = onPickFolder,
            showLabelWhenSet = false,
            tint = task.parentId?.let { vm.folderColors[it] } ?: LedgerAccent
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
    // A step of an in-order parent can leave the next one free to come up beside it (Workflows.kt).
    if (vm.allById[task.parentId]?.sequential == true) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(Labels.ALONGSIDE_NEXT, fontSize = 14.sp, color = LedgerInk, modifier = Modifier.weight(1f))
            Switch(checked = task.alongsideNext, onCheckedChange = { vm.task = vm.task.copy(alongsideNext = it) })
        }
        if (task.alongsideNext) Text(
            stepAfter(task, vm.allTasks)?.let { Labels.comesUpWith(it.title) } ?: Labels.NOTHING_AFTER,
            fontSize = 12.sp, color = LedgerMuted
        )
    }
}

@Composable
internal fun BottomBar(vm: TaskEditViewModel, onDelete: () -> Unit, onSaveAsTemplate: () -> Unit, onCompleteList: () -> Unit, onSave: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = Labels.DELETE, tint = LedgerOverdue) }
        // A copy kept as a template (see Templates.kt); this one carries on as it is.
        if (!vm.isNew && !vm.task.isTemplate && vm.task.type != TaskType.SECTION) TextButton(onClick = onSaveAsTemplate) { Text(Labels.SAVE_AS_TEMPLATE, color = LedgerMuted, fontSize = 13.sp) }
        Spacer(Modifier.weight(1f))
        // Checking the last item completes nothing; a checklist is completed here, on purpose (a template never is).
        if (vm.task.type == TaskType.CHECKLIST && !vm.isNew && !vm.task.isComplete && !vm.task.isTemplate) TextButton(onClick = onCompleteList) { Text(Labels.COMPLETE_LIST, color = LedgerAccent) }
        // A waiting item is resolved here (Back still saves any edits).
        if (vm.task.type == TaskType.WAITING && !vm.isNew && !vm.task.isComplete) Button(
            onClick = { vm.task = vm.task.completed(System.currentTimeMillis()); onSave() },
            enabled = vm.isLoaded && vm.task.title.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = LedgerGood, contentColor = LedgerAccentInk)
        ) { Text(Labels.RESOLVED) }
        else Button(
            onClick = onSave,
            enabled = vm.isLoaded && vm.task.title.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)
        ) { Text(Labels.SAVE) }
    }
}

// Where this task lives, outermost first; each step opens that folder or task.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Breadcrumb(vm: TaskEditViewModel, openTask: (Long) -> Unit) {
    val path = ancestors(vm.task, vm.allById)
    if (path.isEmpty()) return
    FlowRow(Modifier.padding(start = 2.dp, bottom = 2.dp)) {
        path.forEach { step ->
            Text(step.title, color = LedgerAccent, fontSize = 12.sp, modifier = Modifier.clickable { openTask(step.id) }.padding(vertical = 4.dp))
            Text("  ›  ", color = LedgerMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerInk, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
internal fun RemoveButton(onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = LedgerMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun AddLink(text: String, onClick: () -> Unit) {
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
