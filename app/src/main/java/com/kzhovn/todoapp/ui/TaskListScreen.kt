package com.kzhovn.todoapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerCheckBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerNeutralBg
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerOverdueBg
import com.kzhovn.todoapp.ui.theme.LedgerStar
import com.kzhovn.todoapp.ui.theme.LedgerToday
import com.kzhovn.todoapp.ui.theme.LedgerTodayBg
import com.kzhovn.todoapp.ui.theme.folderColors
import com.kzhovn.todoapp.data.walkParentChain
import java.util.Calendar

@Composable
fun TaskListScreen(
    viewModel: TaskListViewModel,
    onCheck: (Long) -> Unit,
    onStar: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onSnooze: (Long, Long) -> Unit,
    selectedIds: Set<Long> = emptySet()
) {
    val tasks by viewModel.tasks.collectAsState()
    val subtaskCounts by viewModel.subtaskCounts.collectAsState()
    val allById by viewModel.allById.collectAsState()
    val contextsByTaskId by viewModel.contextsByTaskId.collectAsState()
    val allContexts by viewModel.allContexts.collectAsState()
    val colors = remember(allById) { folderColors(allById.values) }
    LazyColumn {
        itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
            val effective = remember(task, allById, contextsByTaskId) { resolveEffective(task, allById, contextsByTaskId) }
            // The bar shows the nearest folder ancestor's color, even for a subtask of a task.
            val barColor = task.parentId?.let { walkParentChain(it, allById) { id -> colors[id] } } ?: LedgerBorder
            TaskRow(task, effective, allContexts, subtaskCounts[task.id], barColor, task.id in selectedIds, onCheck, onStar, onEdit, onSnooze)
            if (index < tasks.lastIndex) {
                HorizontalDivider(color = LedgerBorder)
            }
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    task: Task,
    effective: EffectiveTask,
    allContexts: Map<Long, TaskContext>,
    subtasks: Pair<Int, Int>?,
    barColor: Color,
    selected: Boolean,
    onCheck: (Long) -> Unit,
    onStar: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onSnooze: (Long, Long) -> Unit
) {
    var showSnoozeMenu by remember { mutableStateOf(false) }
    val contextName = effective.effectiveContextIds.firstOrNull()?.let { allContexts[it]?.name }
    // Overdue styling must track the *displayed* (effective/inherited) due date, not the task's
    // own possibly-null field, or an inherited overdue date would render in the neutral color.
    val effectiveOverdue = isOverdue(task.isComplete, effective.effectiveDueDate)
    // DropdownMenu is Popup-based (SubcomposeLayout internally) and can't answer the intrinsic
    // width queries an IntrinsicSize.Min row needs from its children, so it must live outside
    // the Row below as a plain sibling rather than nested inside one of the Row's children.
    Box(Modifier.background(if (selected) LedgerAccentSoft else Color.Transparent)) {
        Row(
            // LazyColumn measures items with unbounded height, so fillMaxHeight() alone is a no-op
            // here; the intrinsic-min pass gives the Row (and the bar Box's fillMaxHeight below) a
            // real height to fill, sized to the tallest child.
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(barColor))
            TaskCheckbox(checked = task.isComplete, overdue = effectiveOverdue, onCheckedChange = { onCheck(task.id) })
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.title,
                        fontWeight = FontWeight.Bold,
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
                }
                if (effective.effectiveDueDate != null || contextName != null || (subtasks != null && subtasks.second > 0)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        effective.effectiveDueDate?.let { due -> DueChip(due, effectiveOverdue) }
                        if (subtasks != null && subtasks.second > 0) {
                            Text(
                                text = "${subtasks.first}/${subtasks.second}",
                                fontSize = 10.sp,
                                color = LedgerMuted,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                        if (contextName != null) {
                            Spacer(Modifier.weight(1f))
                            Text("@$contextName", fontSize = 10.sp, color = LedgerMuted)
                        }
                    }
                }
            }
            if (task.isMaybe) {
                MaybeMark()
            } else {
                IconButton(onClick = { onStar(task.id) }) {
                    Icon(
                        if (task.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Star",
                        tint = if (task.isStarred) LedgerStar else LedgerCheckBorder
                    )
                }
            }
        }
        DropdownMenu(expanded = showSnoozeMenu, onDismissRequest = { showSnoozeMenu = false }) {
            DropdownMenuItem(text = { Text("Snooze 1 hour") }, onClick = { showSnoozeMenu = false; onSnooze(task.id, HOUR_MILLIS) })
            DropdownMenuItem(text = { Text("Snooze to tomorrow") }, onClick = { showSnoozeMenu = false; onSnooze(task.id, DAY_MILLIS) })
            DropdownMenuItem(text = { Text("Snooze 1 week") }, onClick = { showSnoozeMenu = false; onSnooze(task.id, WEEK_MILLIS) })
        }
    }
}

private const val HOUR_MILLIS = 60 * 60 * 1000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private const val WEEK_MILLIS = 7 * DAY_MILLIS

// Shown in the star's place: a maybe can't be starred.
@Composable
fun MaybeMark() {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Text("?", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerMuted)
    }
}

@Composable
fun TaskCheckbox(checked: Boolean, overdue: Boolean, size: Dp = 22.dp, onCheckedChange: () -> Unit) {
    val borderColor = if (overdue) LedgerOverdue else LedgerCheckBorder
    val borderWidth = if (overdue) 2.dp else 1.5.dp
    // The tap target is larger than the drawn circle so it's easy to hit with a thumb.
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable { onCheckedChange() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .then(if (checked) Modifier.background(LedgerAccent) else Modifier)
                .border(borderWidth, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(Icons.Filled.Check, contentDescription = "Complete", tint = Color.White, modifier = Modifier.size(size * 0.65f))
            }
        }
    }
}

@Composable
private fun DueChip(dueDate: Long, overdue: Boolean) {
    val now = System.currentTimeMillis()
    val isToday = isSameDay(dueDate, now)
    val (bg, fg) = when {
        overdue -> LedgerOverdueBg to LedgerOverdue
        isToday -> LedgerTodayBg to LedgerToday
        else -> LedgerNeutralBg to LedgerMuted
    }
    val label = if (isToday) "Today" else formatChipDate(dueDate)
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(label, fontSize = 10.sp, color = fg)
    }
}

fun isOverdue(isComplete: Boolean, dueDate: Long?, now: Long = System.currentTimeMillis()): Boolean =
    !isComplete && dueDate != null && dueDate < now

fun isSameDay(a: Long, b: Long): Boolean {
    val calA = Calendar.getInstance().apply { timeInMillis = a }
    val calB = Calendar.getInstance().apply { timeInMillis = b }
    return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
        calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
}
