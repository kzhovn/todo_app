package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.eventWhen
import com.kzhovn.todoapp.CalendarCache
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import com.kzhovn.todoapp.data.checklistGroups
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.kzhovn.todoapp.data.OutlinerPreferences
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.HorizontalDivider
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.completed
import com.kzhovn.todoapp.data.isUnder
import com.kzhovn.todoapp.data.moveOutFolderId
import kotlin.math.roundToInt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerGood
import com.kzhovn.todoapp.ui.theme.LedgerGoodSoft
import com.kzhovn.todoapp.ui.theme.LedgerChip
import com.kzhovn.todoapp.ui.theme.LedgerUp
import com.kzhovn.todoapp.ui.theme.LedgerUpSoft

// The task editor's lists of other tasks: a checklist's items, and the related tasks (subtasks, prerequisites, dependents).

// A checklist's items: loose ones, then its sections (Clothes, Winter), each a compact heading with its own
// items, count and fold (stored as the All tree's folds are, per device). Items are rows like the subtasks:
// tap to open (and edit), press and hold to drag (across sections too, see planMoveItem), ✕ deletes (with
// undo). A heading's + opens an add field for that section, under it; the list's own add field and
// "+ Add section" come last.
@Composable
internal fun ItemsSection(vm: TaskEditViewModel, openTask: (Long) -> Unit, onClearChecked: () -> Unit) {
    SectionLabel(Labels.ITEMS)
    val context = LocalContext.current
    val prefs = remember { OutlinerPreferences(context) }
    val scope = rememberCoroutineScope()
    var folded by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(Unit) { folded = prefs.collapsedIds() }
    val groups = remember(vm.allTasks, vm.taskId) { if (vm.isNew) emptyList() else checklistGroups(vm.taskId, vm.allTasks) }
    val rows = groups.flatMap { (section, items) -> listOfNotNull(section) + if (section != null && section.id in folded) emptyList() else items }
    var addingTo by remember { mutableStateOf<Long?>(null) } // the section whose + is open
    var naming by remember { mutableStateOf<Task?>(null) } // a section being renamed; a blank one: a new section
    var name by remember { mutableStateOf("") }
    ReorderableRows(rows, vm::moveSubtask, canDrag = { it.type != TaskType.SECTION }) { t, dragModifier ->
        if (t.type == TaskType.SECTION) SectionHeading(
            t, vm.subtaskCounts[t.id], isFolded = t.id in folded,
            onFold = { val fold = t.id !in folded; folded = if (fold) folded + t.id else folded - t.id; scope.launch { prefs.setCollapsed(t.id, fold) } },
            onAdd = { addingTo = if (addingTo == t.id) null else t.id; if (t.id in folded) { folded = folded - t.id; scope.launch { prefs.setCollapsed(t.id, false) } } },
            onRename = { naming = t; name = t.title }, onCheckAll = { vm.checkAllIn(t.id) }, onUncheckAll = { vm.uncheckAllIn(t.id) }, onDelete = { vm.deleteItem(t) },
            modifier = dragModifier
        ) else RelatedRow(
            t.title, done = t.isComplete, maybe = t.isMaybe, onOpen = { openTask(t.id) }, modifier = dragModifier,
            leading = { if (t.parentId != vm.taskId) Spacer(Modifier.width(18.dp)); RelatedCheck(vm, t) }
        ) { RemoveButton { vm.deleteItem(t) } }
        if (t.id == addingTo) AddItemField(Labels.addTo(t.title), indent = 52.dp, autoFocus = true) { vm.addItemsTo(t.id, it) }
    }
    PendingRows(vm)
    // "+ Add item" and "+ Add section" side by side.
    Row(verticalAlignment = Alignment.CenterVertically) {
        AddItemField("+ ${Labels.ADD_ITEM}", modifier = Modifier.weight(1f)) { vm.addItems(it) }
        if (!vm.isNew) AddLink(Labels.ADD_SECTION) { naming = Task(title = "", type = TaskType.SECTION); name = "" }
    }
    Row {
        if (groups.any { (_, items) -> items.any { it.isComplete } }) {
            AddLink(Labels.UNCHECK_ALL) { vm.uncheckAll() }
            AddLink(Labels.CLEAR_CHECKED, onClearChecked)
        }
    }
    naming?.let { section ->
        val isNew = section.id == 0L
        TextInputDialog(
            title = if (isNew) Labels.ADD_SECTION.removePrefix("+ ") else Labels.RENAME, placeholder = Labels.SECTION_NAME,
            confirmLabel = Labels.SAVE, value = name, onValueChange = { name = it },
            onConfirm = { if (isNew) vm.addSection(name) else vm.renameSection(section, name); naming = null },
            onDismiss = { naming = null }
        )
    }
}

// A section's compact heading: fold arrow, name, "2/5" (tap to fold), and + to add to it. Press and hold it for
// rename, check all, uncheck all, delete.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionHeading(
    section: Task, counts: Pair<Int, Int>?, isFolded: Boolean, onFold: () -> Unit, onAdd: () -> Unit,
    onRename: () -> Unit, onCheckAll: () -> Unit, onUncheckAll: () -> Unit, onDelete: () -> Unit, modifier: Modifier
) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().heightIn(min = 32.dp).combinedClickable(onClick = onFold, onLongClick = { menu = true })) {
        Icon(if (isFolded) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore, contentDescription = if (isFolded) "Expand" else "Collapse", tint = LedgerMuted, modifier = Modifier.size(28.dp).padding(6.dp))
        Text(section.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (isFolded) LedgerMuted else LedgerInk)
        counts?.let { (done, total) -> Text("$done/$total", fontSize = 11.sp, color = LedgerMuted, modifier = Modifier.padding(start = 6.dp)) }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Add, contentDescription = Labels.addTo(section.title), tint = LedgerAccent, modifier = Modifier.size(18.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = LedgerSearchBackground) {
                listOf(Labels.RENAME to onRename, Labels.checkAllIn(section.title) to onCheckAll, Labels.uncheckAllIn(section.title) to onUncheckAll, Labels.DELETE_SECTION to onDelete)
                    .forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() }) }
            }
        }
    }
}

// Subtasks, what this task depends on (prerequisites), and what depends on it.
@Composable
internal fun RelatedSection(vm: TaskEditViewModel, openTask: (Long) -> Unit, onAddSubtask: () -> Unit, onAddPrerequisite: () -> Unit, onAddDependent: () -> Unit) {
    val task = vm.task
    val isChecklist = task.type == TaskType.CHECKLIST
    SectionLabel(Labels.RELATED)
    val subtasks = remember(vm.allTasks, vm.taskId, isChecklist) { vm.allTasks.filter { it.parentId == vm.taskId && !vm.isNew && !isChecklist }.sortedWith(TaskOrder) }
    val childrenByParent = remember(vm.allTasks) { vm.allTasks.groupBy { it.parentId }.mapValues { (_, kids) -> kids.sortedWith(TaskOrder) } }
    val isFolder = task.type == TaskType.FOLDER
    val dependentIds = if (isFolder || vm.isNew) emptySet() else vm.dependencyEdges.filter { it.dependsOnTaskId == vm.taskId }.map { it.taskId }.toSet()
    val prerequisiteIds = if (isFolder) emptySet() else vm.dependencyIds
    var deleting by remember { mutableStateOf<Task?>(null) }
    // Subtasks first, together on a faint tile; the prerequisites and dependents follow below it.
    val hasSubtasks = subtasks.isNotEmpty() || (!isChecklist && vm.pendingSubtasks.isNotEmpty()) || vm.pendingChildIds.isNotEmpty()
    if (hasSubtasks) Column(Modifier.fillMaxWidth().padding(bottom = 4.dp).background(LedgerChip, RoundedCornerShape(8.dp)).padding(horizontal = 4.dp)) {
    ReorderableRows(subtasks, vm::moveSubtask) { sub, dragModifier ->
        // A subtask that's also a prerequisite or dependent says so, as the rows below the tile do.
        val note = when (sub.id) { in prerequisiteIds -> RelatedKind.PREREQUISITE; in dependentIds -> RelatedKind.DEPENDENT; else -> null }
        RelatedRow(sub.title, done = sub.isComplete, maybe = sub.isMaybe, onOpen = { openTask(sub.id) }, modifier = dragModifier, note = note, leading = { RelatedCheck(vm, sub) }) {
            SubtaskRemoveMenu(vm, sub, isPrerequisite = sub.id in prerequisiteIds, isDependent = sub.id in dependentIds, onDelete = { deleting = sub })
        }
        // One level further, indented; anything deeper is only the "1/3" count on the row it's under.
        childrenByParent[sub.id].orEmpty().forEach { child ->
            val count = vm.subtaskCounts[child.id]?.takeIf { child.type != TaskType.CHECKLIST }
            RelatedRow(child.title, done = child.isComplete, maybe = child.isMaybe, onOpen = { openTask(child.id) }, count = count, leading = { Spacer(Modifier.width(22.dp)); RelatedCheck(vm, child) }) {}
        }
    }
    if (!isChecklist) PendingRows(vm)
    vm.pendingChildIds.mapNotNull(vm.allById::get).forEach { child ->
        RelatedRow(child.title, onOpen = { openTask(child.id) }) { RemoveButton { vm.pendingChildIds = vm.pendingChildIds - child.id } }
    }
    }
    vm.completeDecision?.let { (_, question) ->
        CompleteSubtasksDialog(question, onCompleteAll = { vm.decideComplete(moveOut = false) }, onMoveOut = { vm.decideComplete(moveOut = true) }, onDismiss = { vm.completeDecision = null })
    }
    deleting?.let { sub ->
        val below = vm.allTasks.count { isUnder(it, sub.id, vm.allById) }
        ConfirmDialog(
            title = "Delete “${sub.title}”?",
            body = if (below > 0) "This will also delete $below subtask${if (below == 1) "" else "s"}." else null,
            confirmLabel = Labels.DELETE,
            onConfirm = { deleting = null; vm.deleteItem(sub) },
            onDismiss = { deleting = null }
        )
    }
    val subtaskIds = subtasks.map { it.id }.toSet()
    if (task.type != TaskType.FOLDER) {
        // An event (Google Calendar's): its calendar's dot and its time, and it opens its sheet, not the editor.
        val context = androidx.compose.ui.platform.LocalContext.current
        @Composable
        fun Linked(t: Task, note: RelatedKind, done: Boolean, check: Boolean, remove: () -> Unit) {
            if (t.type == TaskType.EVENT) {
                val event = t.eventId?.let { id -> CalendarCache.events(context).firstOrNull { it.id == id } }
                RelatedRow(
                    t.title + (event?.let { " · " + eventWhen(it, System.currentTimeMillis()) } ?: ""), done = t.isComplete, note = note,
                    onOpen = { event?.let { context.startActivity(EventActivity.intent(context, it.id)) } },
                    leading = { Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) { event?.let { CalendarDot(calendarColor(context, it.calendarId)) } } }
                ) { RemoveButton(remove) }
            } else RelatedRow(t.title, done = done, onOpen = { openTask(t.id) }, note = note, leading = { if (check) RelatedCheck(vm, t) else Spacer(Modifier.width(34.dp)) }) { RemoveButton(remove) }
        }
        (vm.dependencyIds - subtaskIds).mapNotNull(vm.allById::get).sortedBy { it.title.lowercase() }.forEach { prereq ->
            Linked(prereq, RelatedKind.PREREQUISITE, prereq.isComplete, check = true) { vm.dependencyIds = vm.dependencyIds - prereq.id }
        }
        vm.pendingDependentIds.mapNotNull(vm.allById::get).forEach { dependent ->
            Linked(dependent, RelatedKind.DEPENDENT, false, check = false) { vm.pendingDependentIds = vm.pendingDependentIds - dependent.id }
        }
        (dependentIds - subtaskIds).mapNotNull(vm.allById::get).forEach { dependent ->
            Linked(dependent, RelatedKind.DEPENDENT, dependent.isComplete, check = true) { vm.removeDependent(dependent.id) }
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
    if (task.type == TaskType.CHECKLIST || task.type == TaskType.WAITING) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        // Only the first incomplete child counts as active.
        TogglePill(Labels.SEQUENTIAL, Icons.Filled.FormatListNumbered, on = task.sequential) { vm.task = task.copy(sequential = !task.sequential) }
        // Normally a task waits on its open subtasks; this keeps it in Doing/Active anyway.
        if (task.type == TaskType.TASK) TogglePill(Labels.ACTIVE_WITH_SUBTASKS, Icons.Filled.Bolt, on = task.activeWithSubtasks) {
            vm.task = task.copy(activeWithSubtasks = !task.activeWithSubtasks)
        }
    }
}

// Press and hold a row, then drag it to a new place: the editor's subtasks and a checklist's items.
@Composable
private fun ReorderableRows(
    rows: List<Task>, onMove: (id: Long, anchorId: Long, after: Boolean) -> Unit, canDrag: (Task) -> Boolean = { true }, row: @Composable (Task, Modifier) -> Unit
) {
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    rows.forEachIndexed { index, t ->
        var rowHeight by remember(t.id) { mutableIntStateOf(1) }
        val dragging = dragId == t.id
        val dragModifier = Modifier
            .onSizeChanged { rowHeight = it.height }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (dragging) dragOffset else 0f
                shadowElevation = if (dragging) 8.dp.toPx() else 0f
                shape = RoundedCornerShape(6.dp)
            }
            .background(if (dragging) LedgerSearchBackground else Color.Transparent, RoundedCornerShape(6.dp))
            .pointerInput(t.id, rows) {
                if (!canDrag(t)) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); dragId = t.id; dragOffset = 0f },
                    onDrag = { change, amount -> change.consume(); dragOffset += amount.y },
                    onDragEnd = {
                        // ponytail: steps by the dragged row's own height, so among rows of very different heights the drop is approximate.
                        val target = (index + (dragOffset / rowHeight).roundToInt()).coerceIn(0, rows.lastIndex)
                        if (target != index) onMove(t.id, rows[target].id, target > index)
                        dragId = null
                    },
                    onDragCancel = { dragId = null }
                )
            }
        row(t, dragModifier)
    }
}

// Subtasks or items typed into a task not saved yet: tap one to change it, ✕ drops it.
@Composable
private fun PendingRows(vm: TaskEditViewModel) {
    var editing by remember { mutableStateOf<Int?>(null) }
    var text by remember { mutableStateOf("") }
    vm.pendingSubtasks.forEachIndexed { index, title ->
        RelatedRow(title, onOpen = { editing = index; text = title }) { RemoveButton { vm.pendingSubtasks = vm.pendingSubtasks.filterIndexed { i, _ -> i != index } } }
    }
    editing?.let { index ->
        TextInputDialog(
            title = Labels.TITLE, placeholder = Labels.TITLE, confirmLabel = Labels.SAVE, value = text, onValueChange = { text = it },
            onConfirm = { vm.pendingSubtasks = vm.pendingSubtasks.mapIndexed { i, t -> if (i == index) text.trim() else t }; editing = null },
            onDismiss = { editing = null }
        )
    }
}

// Stays focused after each add, for typing a list in one go; "milk, eggs" adds two.
@Composable
private fun AddItemField(placeholder: String, indent: Dp = 34.dp, modifier: Modifier = Modifier.fillMaxWidth(), autoFocus: Boolean = false, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = LedgerInk),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { onAdd(text); text = "" } }),
        modifier = modifier.padding(start = indent, top = 6.dp, bottom = 6.dp).focusRequester(focus),
        decorationBox = { field ->
            if (text.isEmpty()) Text(placeholder, fontSize = 14.sp, color = LedgerMuted)
            field()
        }
    )
}

// What a linked task is to this one, as a tinted tag: amber for what it waits on, green for what waits on it.
private enum class RelatedKind(val label: String, val ink: Color, val tint: Color) {
    PREREQUISITE(Labels.PREREQUISITE, LedgerUp, LedgerUpSoft),
    DEPENDENT(Labels.DEPENDENT, LedgerGood, LedgerGoodSoft)
}

@Composable
private fun KindTag(kind: RelatedKind, modifier: Modifier = Modifier) =
    Text(kind.label, fontSize = 10.sp, color = kind.ink, modifier = modifier.background(kind.tint, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))

// One related task: its checkbox (other rows keep its space so titles line up), its title (tap opens
// it), a Prerequisite or Dependent tag after it, and a trailing ✕ where it can be removed.
@Composable
private fun RelatedRow(
    title: String, done: Boolean = false, onOpen: (() -> Unit)?, modifier: Modifier = Modifier,
    note: RelatedKind? = null, count: Pair<Int, Int>? = null, maybe: Boolean = false,
    leading: @Composable () -> Unit = { Spacer(Modifier.width(34.dp)) }, trailing: @Composable () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().heightIn(min = 36.dp)) {
        leading()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier).padding(vertical = 6.dp)
        ) {
            Text(
                title,
                fontSize = 14.sp,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done || maybe) LedgerMuted else LedgerInk,
                modifier = Modifier.weight(1f, fill = false)
            )
            // A maybe, as the lists mark one: "?" after it, and dimmed (done ones are struck through instead).
            if (maybe && !done) Text("?", fontSize = 14.sp, color = LedgerMuted, modifier = Modifier.padding(start = 6.dp))
            note?.let { KindTag(it, Modifier.padding(start = 8.dp)) }
            count?.let { (done, total) -> Text("$done/$total", fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(start = 8.dp)) }
        }
        trailing()
    }
}

// A related task's mark, as in the lists: a checkbox, a waiting item's hourglass, a project's icon or a
// checklist's count.
@Composable
private fun RelatedCheck(vm: TaskEditViewModel, t: Task) = TaskMark(
    t, vm.subtaskCounts[t.id],
    t.dueDate?.takeUnless { t.isComplete }?.let { dueStatus(it, System.currentTimeMillis()) }, checkboxSize = 18.dp,
    onCheck = { vm.toggleComplete(t.id) }, onOpen = {}
)

// A subtask's ✕: move it out to this task's folder, stop it being a prerequisite or dependent (it stays
// a subtask), both, or delete it.
@Composable
private fun SubtaskRemoveMenu(vm: TaskEditViewModel, sub: Task, isPrerequisite: Boolean, isDependent: Boolean, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun unlink() = if (isPrerequisite) vm.dependencyIds = vm.dependencyIds - sub.id else vm.removeDependent(sub.id)
    Box {
        RemoveButton { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = LedgerSearchBackground) {
            @Composable
            fun item(text: String, color: Color = LedgerInk, action: () -> Unit) =
                DropdownMenuItem(text = { Text(text, color = color) }, onClick = { open = false; action() })
            item(Labels.moveOutTo(moveOutFolderId(vm.task, vm.allById)?.let { vm.allById[it]?.title })) { vm.moveOut(sub.id) }
            if (isPrerequisite) item(Labels.NOT_PREREQUISITE) { unlink() }
            if (isDependent) item(Labels.NOT_DEPENDENT) { unlink() }
            if (isPrerequisite || isDependent) item(Labels.MOVE_OUT_AND_UNLINK) { unlink(); vm.moveOut(sub.id) }
            HorizontalDivider()
            item(Labels.DELETE_TASK, LedgerOverdue, onDelete)
        }
    }
}
