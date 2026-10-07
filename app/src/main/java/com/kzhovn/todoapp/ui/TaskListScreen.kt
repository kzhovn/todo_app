package com.kzhovn.todoapp.ui

import androidx.compose.material.icons.filled.SkipNext
import com.kzhovn.todoapp.data.notesMatch
import androidx.compose.material.icons.automirrored.filled.Notes
import com.kzhovn.todoapp.data.countdown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.kzhovn.todoapp.ui.theme.LedgerTile
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.BorderStroke
import com.kzhovn.todoapp.focus.FocusActivity
import android.content.Intent
import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.dueText
import com.kzhovn.todoapp.ui.theme.LedgerDueToday
import com.kzhovn.todoapp.ui.theme.LedgerDueTodayText
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import androidx.compose.ui.text.withStyle
import com.kzhovn.todoapp.data.Labels
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import com.kzhovn.todoapp.data.formatDuration
import com.kzhovn.todoapp.notifications.TaskTimer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import com.kzhovn.todoapp.data.subtaskParentTitle
import androidx.compose.ui.draw.alpha
import com.kzhovn.todoapp.data.TaskType
import androidx.compose.material.icons.filled.AccountTree
import com.kzhovn.todoapp.notifications.PinnedTask
import androidx.compose.ui.platform.LocalContext
import com.kzhovn.todoapp.AppSettings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.kzhovn.todoapp.data.OutlinerPreferences
import com.kzhovn.todoapp.data.sectionsByTopFolder
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.EffectiveTask
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.SnoozeChoice
import com.kzhovn.todoapp.data.chipDate
import com.kzhovn.todoapp.data.startText
import com.kzhovn.todoapp.data.waitingFor
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerCheckBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerStar
import com.kzhovn.todoapp.ui.theme.folderColors
import com.kzhovn.todoapp.data.walkParentChain

@Composable
fun TaskListScreen(
    viewModel: TaskListViewModel,
    onCheck: (Long) -> Unit,
    onStar: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onSnooze: (Long, Long) -> Unit,
    onAddSubtask: (Long) -> Unit,
    selectedIds: Set<Long> = emptySet(),
    // Active's layout: foldable sections by top-level folder (see sectionsByTopFolder).
    sectioned: Boolean = false
) {
    val tasks by viewModel.tasks.collectAsState()
    val subtaskCounts by viewModel.subtaskCounts.collectAsState()
    val allById by viewModel.allById.collectAsState()
    val contextsByTaskId by viewModel.contextsByTaskId.collectAsState()
    val allContexts by viewModel.allContexts.collectAsState()
    val colors = remember(allById) { folderColors(allById.values) }
    val context = LocalContext.current
    val prefs = remember { OutlinerPreferences(context) }
    val scope = rememberCoroutineScope()
    var folded by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(Unit) { folded = prefs.foldedSectionIds() }

    @Composable
    fun Row(task: Task, last: Boolean) {
        val effective = remember(task, allById, contextsByTaskId) { resolveEffective(task, allById, contextsByTaskId) }
        // The bar shows the nearest folder ancestor's color, even for a subtask of a task.
        val barColor = task.parentId?.let { walkParentChain(it, allById) { id -> colors[id] } } ?: LedgerBorder
        val notesLine = viewModel.searchQuery?.let { notesMatch(task, it) }
        // Search results come from anywhere, so they say where: the folder, then the contexts.
        val metaLine = viewModel.searchQuery?.let {
            listOfNotNull(task.parentId?.let { p -> walkParentChain(p, allById) { id -> allById[id]?.takeIf { f -> f.type == TaskType.FOLDER }?.title } }) +
                contextsByTaskId[task.id].orEmpty().mapNotNull { id -> allContexts[id]?.name?.let { "@$it" } }.sorted()
        }?.joinToString(" · ")?.ifEmpty { null }
        val row = @Composable { TaskRow(task, effective, allContexts, subtaskCounts[task.id], barColor, task.id in selectedIds, subtaskParentTitle(task, allById), onCheck, onStar, onEdit, onSnooze, notesLine, metaLine) }
        // Swipe right to add a subtask (a checklist's are items), as in the All tree.
        SwipeToAddSubtask({ onAddSubtask(task.id) }, row)
        if (!last) HorizontalDivider(color = LedgerBorder)
    }

    LazyColumn {
        if (!sectioned) {
            itemsIndexed(tasks, key = { _, task -> task.id }) { index, task -> Row(task, last = index == tasks.lastIndex) }
        } else {
            sectionsByTopFolder(tasks, allById).forEach { (folder, sectionTasks) ->
                // The folderless section folds under id 0.
                val sectionId = folder?.id ?: 0L
                val isFolded = sectionId in folded
                item(key = "section-$sectionId") {
                    SectionHeader(folder?.title ?: Labels.NO_FOLDER, folder?.let { colors[it.id] }, sectionTasks.size, isFolded) {
                        folded = if (isFolded) folded - sectionId else folded + sectionId
                        scope.launch { prefs.setSectionFolded(sectionId, !isFolded) }
                    }
                }
                if (!isFolded) {
                    itemsIndexed(sectionTasks, key = { _, task -> task.id }) { index, task -> Row(task, last = index == sectionTasks.lastIndex) }
                }
            }
        }
    }
}

// A divider: "Work · 5" centered between two rules in the folder's colour.
@Composable
private fun SectionHeader(title: String, color: Color?, count: Int, folded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 6.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)
    ) {
        Icon(
            if (folded) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
            contentDescription = if (folded) "Expand" else "Collapse",
            tint = LedgerMuted, modifier = Modifier.padding(end = 6.dp).size(16.dp)
        )
        val rule = Modifier.weight(1f).height(1.dp).background((color ?: LedgerMuted).copy(alpha = 0.55f))
        Box(rule)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(title) }
                append(" · $count")
            },
            fontSize = 12.sp, color = LedgerMuted, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp)
        )
        Box(rule)
    }
}

@Composable
private fun RecurrenceBadge() {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(LedgerAccentSoft),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Repeat, contentDescription = "Recurring", tint = LedgerAccent, modifier = Modifier.size(10.dp))
    }
}

// High priority, after the title.
@Composable
internal fun PriorityMark() = Text(" !", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LedgerOverdue)

// "There's more": the task has a note.
@Composable
internal fun NotesMark() {
    Spacer(Modifier.width(4.dp))
    Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "Has notes", tint = LedgerMuted, modifier = Modifier.size(15.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    task: Task,
    effective: EffectiveTask,
    allContexts: Map<Long, TaskContext>,
    subtasks: Pair<Int, Int>?,
    barColor: Color,
    selected: Boolean,
    parentTitle: String?,
    onCheck: (Long) -> Unit,
    onStar: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onSnooze: (Long, Long) -> Unit,
    notesLine: String? = null, // search: the line of the notes it matched
    metaLine: String? = null // search: where it lives and its contexts, as the web's results show
) {
    var showSnoozeMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Due styling tracks the *displayed* (effective/inherited) due date, not the task's own
    // possibly-null field, or an inherited due date would render in the neutral colour.
    val now = System.currentTimeMillis()
    val due = effective.effectiveDueDate?.takeUnless { task.isComplete }
    val status = due?.let { dueStatus(it, now) }
    // A month-old maybe is dimmed (backburner) but stays listed.
    val dim = if (task.isBackburner(now)) BACKBURNER_ALPHA else 1f
    Box(Modifier.background(if (selected) LedgerAccentSoft else Color.Transparent).alpha(dim)) {
        Row(
            // LazyColumn measures items with unbounded height, so fillMaxHeight() alone is a no-op
            // here; the intrinsic-min pass gives the Row (and the bar Box's fillMaxHeight below) a
            // real height to fill, sized to the tallest child.
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(barColor))
            TaskMark(task, subtasks, status, onCheck = { onCheck(task.id) }, onOpen = { onEdit(task.id) })
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    // A subtask reads "Parent: subtask", with the parent dimmer; tapping the parent
                    // opens the parent (a link, so the rest of the row keeps its own tap/long-press).
                    text = buildAnnotatedString {
                        val parentId = task.parentId
                        if (parentTitle != null && parentId != null) {
                            val style = TextLinkStyles(SpanStyle(color = LedgerMuted, fontWeight = FontWeight.Normal))
                            withLink(LinkAnnotation.Clickable("parent", style) { onEdit(parentId) }) { append("$parentTitle: ") }
                        }
                        append(task.title)
                        // One line with the title (it wraps along), in the due colour.
                        if (due != null) {
                            val color = when (status) { DueStatus.OVERDUE -> LedgerOverdue; DueStatus.TODAY -> LedgerDueTodayText; else -> LedgerMuted }
                            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Normal, fontSize = 12.sp)) { append("  · ${dueText(due, now)}") }
                        }
                        // A start still to come (search shows snoozed and future tasks), or a waiting item's own line, as on the web.
                        val later = if (task.isComplete) null else if (task.type == TaskType.WAITING) {
                            task.dueDate?.let { "resolves ${chipDate(it)}" } ?: waitingFor(task, now)?.let { "waiting $it" }
                        } else task.startDate?.takeIf { it > now }?.let { startText(it, now) }
                        if (later != null) withStyle(SpanStyle(color = LedgerMuted, fontWeight = FontWeight.Normal, fontSize = 12.sp)) { append("  · $later") }
                        if (subtasks != null && subtasks.second > 0 && task.type != TaskType.CHECKLIST) {
                            withStyle(SpanStyle(color = LedgerMuted, fontWeight = FontWeight.Normal, fontSize = 12.sp)) { append("  · ${subtasks.first}/${subtasks.second}") }
                        }
                        if (metaLine != null) withStyle(SpanStyle(color = LedgerMuted, fontWeight = FontWeight.Normal, fontSize = 12.sp)) { append("\n$metaLine") }
                        if (notesLine != null) withStyle(SpanStyle(color = LedgerMuted, fontWeight = FontWeight.Normal, fontSize = 13.sp)) { append("\n$notesLine") }
                    },
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    textDecoration = if (task.isComplete) TextDecoration.LineThrough else null,
                    color = if (task.isComplete) LedgerMuted else LedgerInk,
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(onClick = { onEdit(task.id) }, onLongClick = { showSnoozeMenu = true })
                )
                if (task.recurrenceType != null) {
                    Spacer(Modifier.width(4.dp))
                    RecurrenceBadge()
                }
                if (task.isHighPriority) PriorityMark()
                if (!task.notes.isNullOrBlank()) NotesMark()
            }
            task.durationMinutes?.let { TimerButton(task, it) }
            if (task.isMaybe) {
                MaybeMark(34.dp)
            } else {
                Box(Modifier.size(34.dp).clip(CircleShape).clickable { onStar(task.id) }, contentAlignment = Alignment.Center) {
                    StarIcon(task.isStarred)
                }
            }
        }
        // DropdownMenu is Popup-based (SubcomposeLayout internally) and can't answer the intrinsic
        // width queries an IntrinsicSize.Min row needs from its children, so it lives outside the Row
        // as a plain sibling.
        DropdownMenu(
            expanded = showSnoozeMenu,
            onDismissRequest = { showSnoozeMenu = false },
            shape = RoundedCornerShape(8.dp),
            containerColor = LedgerSearchBackground,
            border = BorderStroke(1.dp, LedgerBorder)
        ) {
            val context = LocalContext.current
            fun snooze(until: (now: Long) -> Long) {
                showSnoozeMenu = false
                onSnooze(task.id, until(System.currentTimeMillis()))
            }
            Column(Modifier.width(236.dp)) {
                Text(Labels.SNOOZE, fontSize = 11.sp, color = LedgerMuted, letterSpacing = 0.4.sp, modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 6.dp))
                // Three equal tiles lined up with the rows' icons below.
                DateTiles(snoozeChoices(AppSettings.rolloverHour(context))) { until -> snooze { until } }
                HorizontalDivider(color = LedgerBorder)
                MenuRow(Labels.PIN, Icons.Filled.PushPin) { showSnoozeMenu = false; scope.launch { PinnedTask.pin(context, task.id) } }
                MenuRow("Focus", Icons.Filled.CenterFocusStrong) {
                    showSnoozeMenu = false
                    context.startActivity(Intent(context, FocusActivity::class.java).putExtra(FocusActivity.EXTRA_TASK_ID, task.id))
                }
                // A repeat: on to its next time without doing this one. The list follows the change itself.
                if (task.recurrenceType != null) MenuRow(Labels.SKIP, Icons.Filled.SkipNext) {
                    showSnoozeMenu = false
                    scope.launch {
                        (context.applicationContext as com.kzhovn.todoapp.TodoApp).repository.skip(task.id, System.currentTimeMillis())
                        PinnedTask.refresh(context)
                    }
                }
            }
        }
    }
}

// A choice of times as equal tiles: the snooze menu's, and the task editor's Start and Due on long press.
class DateChoice(val label: String, val icon: ImageVector, val at: (now: Long) -> Long)

// Snoozing (or setting a start): see SnoozeChoice (shared with the web).
fun snoozeChoices(rolloverHour: Int) = SnoozeChoice.entries.map { c ->
    DateChoice(c.label, when (c) { SnoozeChoice.HOUR -> Icons.Filled.Schedule; SnoozeChoice.TOMORROW -> Icons.Filled.Bedtime; SnoozeChoice.WEEK -> Icons.Filled.DateRange }) { c.at(it, rolloverHour) }
}

@Composable
fun DateTiles(choices: List<DateChoice>, onPick: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
        choices.forEach { c -> SnoozeTile(c.label, c.icon) { onPick(c.at(System.currentTimeMillis())) } }
    }
}

@Composable
private fun RowScope.SnoozeTile(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(LedgerTile).clickable(onClick = onClick).padding(vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = LedgerAccent, modifier = Modifier.size(17.dp))
        Text(label, fontSize = 11.sp, color = LedgerAccent, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onClick).padding(horizontal = 14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = LedgerMuted, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 13.sp, color = LedgerInk, modifier = Modifier.padding(start = 10.dp))
    }
}

const val BACKBURNER_ALPHA = 0.45f
// A start still to come, in the All tree: dimmed a little, less than the back burner.
const val LATER_ALPHA = 0.65f

// Shown in the checkbox's place: a project is completed as a whole, not ticked off directly.
@Composable
fun ProjectMark(size: Dp = 40.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.AccountTree, contentDescription = "Project", tint = LedgerAccent, modifier = Modifier.size(size * 0.5f))
    }
}

// What leads a task's row (the lists' and the All tree's), by type: a project's icon, a waiting item's
// hourglass, a checklist's count (ticked item by item, inside it; tapping opens it), or a checkbox.
@Composable
fun TaskMark(task: Task, counts: Pair<Int, Int>?, due: DueStatus?, checkboxSize: Dp = 22.dp, onCheck: () -> Unit, onOpen: () -> Unit) = when (task.type) {
    TaskType.PROJECT -> ProjectMark(36.dp)
    TaskType.WAITING -> WaitingMark(36.dp)
    TaskType.CHECKLIST -> CountMark(counts?.first ?: 0, counts?.second ?: 0, touchSize = 36.dp, onClick = onOpen)
    else -> TaskCheckbox(checked = task.isComplete, due = due, size = checkboxSize, touchSize = 36.dp, onCheckedChange = onCheck)
}

// A waiting item's plain hourglass, in the checkbox's place: it's resolved from its editor or the
// Waiting section, not ticked off.
@Composable
fun WaitingMark(size: Dp = 40.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.HourglassEmpty, contentDescription = Labels.WAITING, tint = LedgerMuted, modifier = Modifier.size(size * 0.5f))
    }
}

// A checklist's "3/8" (items checked of all), in the checkbox's place.
@Composable
fun CountMark(done: Int, total: Int, touchSize: Dp = 40.dp, onClick: () -> Unit) {
    Box(Modifier.size(touchSize).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            "$done/$total", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = LedgerAccent, maxLines = 1,
            modifier = Modifier.border(1.dp, LedgerBorder, RoundedCornerShape(50)).padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

// A timed task's play button: starts its countdown, or pauses/resumes it if it's the running one.
// Starting another task's timer replaces the running one (one timer at a time).
@Composable
fun TimerButton(task: Task, minutes: Int) {
    val context = LocalContext.current
    val timer by TaskTimer.state.collectAsState()
    val mine = timer?.takeIf { it.taskId == task.id }
    val running = mine != null && !mine.isPaused
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (running) LaunchedEffect(mine) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    // "▶ 1h" until started, then the time left ("⏸ 18:42" running, "▶ 18:42" paused).
    val text = mine?.let { countdown(it.remaining(now)) } ?: formatDuration(minutes)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(50))
            .then(if (running) Modifier.background(LedgerAccentSoft) else Modifier.border(1.dp, LedgerBorder, RoundedCornerShape(50)))
            .clickable {
                when {
                    mine == null -> TaskTimer.start(context, task, minutes)
                    mine.isPaused -> TaskTimer.resume(context)
                    else -> TaskTimer.pause(context)
                }
            }
            .padding(start = 4.dp, end = 7.dp, top = 3.dp, bottom = 3.dp)
    ) {
        Icon(
            if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (running) "Pause timer" else "Start timer",
            tint = LedgerAccent,
            modifier = Modifier.size(16.dp)
        )
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LedgerAccent)
    }
}

// Starred: a gold outline over a pale gold fill, like the due checkboxes; unstarred: a grey outline.
@Composable
fun StarIcon(starred: Boolean, size: Dp = 24.dp) {
    Box(contentAlignment = Alignment.Center) {
        if (starred) Icon(Icons.Filled.Star, contentDescription = null, tint = LedgerStar.copy(alpha = 0.28f), modifier = Modifier.size(size))
        Icon(Icons.Filled.StarBorder, contentDescription = if (starred) "Starred" else "Star", tint = if (starred) LedgerStar else LedgerCheckBorder, modifier = Modifier.size(size))
    }
}

// Shown in the star's place: a maybe can't be starred.
@Composable
fun MaybeMark(size: Dp = 40.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Text("?", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerMuted)
    }
}

@Composable
fun TaskCheckbox(checked: Boolean, due: DueStatus?, size: Dp = 22.dp, touchSize: Dp = 40.dp, onCheckedChange: () -> Unit) {
    // Due today: an orange ring; overdue: rust. Each with a pale fill of its own colour.
    val ring = when (due) { DueStatus.OVERDUE -> LedgerOverdue; DueStatus.TODAY -> LedgerDueToday; else -> null }
    val borderColor = ring ?: LedgerCheckBorder
    val borderWidth = if (ring != null) 2.dp else 1.5.dp
    // The tap target is larger than the drawn circle so it's easy to hit with a thumb.
    Box(
        modifier = Modifier.size(touchSize).clip(CircleShape).clickable { onCheckedChange() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .then(if (checked) Modifier.background(LedgerAccent) else if (ring != null) Modifier.background(ring.copy(alpha = 0.18f)) else Modifier)
                .border(borderWidth, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(Icons.Filled.Check, contentDescription = "Complete", tint = Color.White, modifier = Modifier.size(size * 0.65f))
            }
        }
    }
}

