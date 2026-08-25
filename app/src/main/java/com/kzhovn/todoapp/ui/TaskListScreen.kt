package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerCheckBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMonoFont
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerNeutralBg
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerOverdueBg
import com.kzhovn.todoapp.ui.theme.LedgerStar
import com.kzhovn.todoapp.ui.theme.LedgerTitleFont
import com.kzhovn.todoapp.ui.theme.LedgerToday
import com.kzhovn.todoapp.ui.theme.LedgerTodayBg
import com.kzhovn.todoapp.ui.theme.folderColor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun TaskListScreen(
    viewModel: TaskListViewModel,
    onCheck: (Long) -> Unit,
    onStar: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (Long) -> Unit
) {
    val tasks by viewModel.tasks.collectAsState()
    val subtaskCounts by viewModel.subtaskCounts.collectAsState()
    LazyColumn {
        itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
            TaskRow(task, subtaskCounts[task.id], onCheck, onStar, onDelete, onEdit)
            if (index < tasks.lastIndex) {
                HorizontalDivider(color = LedgerBorder)
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    subtasks: Pair<Int, Int>?,
    onCheck: (Long) -> Unit,
    onStar: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (Long) -> Unit
) {
    val barColor = task.parentId?.let { folderColor(it) } ?: LedgerBorder
    Row(
        // LazyColumn measures items with unbounded height, so fillMaxHeight() alone is a no-op
        // here; the intrinsic-min pass gives the Row (and the bar Box's fillMaxHeight below) a
        // real height to fill, sized to the tallest child.
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(barColor))
        Spacer(Modifier.width(8.dp))
        TaskCheckbox(checked = task.isComplete, overdue = isOverdue(task), onCheckedChange = { onCheck(task.id) })
        Spacer(Modifier.width(8.dp))
        Text(
            text = task.title,
            fontFamily = LedgerTitleFont,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            textDecoration = if (task.isComplete) TextDecoration.LineThrough else null,
            color = if (task.isComplete) LedgerMuted else LedgerInk,
            modifier = Modifier.weight(1f).clickable { onEdit(task.id) }
        )
        task.dueDate?.let { due -> DueChip(due, isOverdue(task)) }
        if (subtasks != null && subtasks.second > 0) {
            Text(
                text = "${subtasks.first}/${subtasks.second}",
                fontFamily = LedgerMonoFont,
                fontSize = 10.sp,
                color = LedgerMuted,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        IconButton(onClick = { onStar(task.id) }) {
            Icon(
                if (task.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "Star",
                tint = if (task.isStarred) LedgerStar else LedgerCheckBorder
            )
        }
        IconButton(onClick = { onDelete(task.id) }) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete")
        }
    }
}

@Composable
fun TaskCheckbox(checked: Boolean, overdue: Boolean, size: Dp = 16.dp, onCheckedChange: () -> Unit) {
    val borderColor = if (overdue) LedgerOverdue else LedgerCheckBorder
    val borderWidth = if (overdue) 2.dp else 1.5.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(if (checked) Modifier.background(LedgerAccent) else Modifier)
            .border(borderWidth, borderColor, CircleShape)
            .clickable { onCheckedChange() },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(Icons.Filled.Check, contentDescription = "Complete", tint = Color.White, modifier = Modifier.size(size * 0.65f))
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
    val label = if (isToday) "Today" else SimpleDateFormat("MMM d", Locale.US).format(Date(dueDate))
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(label, fontFamily = LedgerMonoFont, fontSize = 10.sp, color = fg)
    }
}

fun isOverdue(task: Task, now: Long = System.currentTimeMillis()): Boolean =
    !task.isComplete && task.dueDate != null && task.dueDate < now

fun isSameDay(a: Long, b: Long): Boolean {
    val calA = Calendar.getInstance().apply { timeInMillis = a }
    val calB = Calendar.getInstance().apply { timeInMillis = b }
    return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
        calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
}
