package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.dueText
import com.kzhovn.todoapp.data.nearestFolder
import com.kzhovn.todoapp.data.subtaskParentTitle
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerDueTodayText
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import com.kzhovn.todoapp.ui.theme.LedgerStar
import com.kzhovn.todoapp.ui.theme.folderColors

// Pick one task, or (with onCreateNew) make a new one, from a sheet with its search at the bottom, by the
// thumb. `lists` are labelled lists of tasks: with `chips` one shows at a time (focus's Doing · Active · All,
// opening on the first that isn't empty), otherwise all of them under their labels (nearbyFirst's sections;
// a single list's label isn't shown). Typing searches: `searchAll` if given, else every task in `lists`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskPickerDialog(
    title: String,
    lists: List<Pair<String, List<Task>>>,
    allById: Map<Long, Task>,
    onPick: (Task) -> Unit,
    onCreateNew: (() -> Unit)?,
    onDismiss: () -> Unit,
    chips: Boolean = false,
    searchAll: ((String) -> List<Task>)? = null
) {
    var query by remember { mutableStateOf("") }
    var chosen by remember(lists) { mutableStateOf(lists.indexOfFirst { it.second.isNotEmpty() }.coerceAtLeast(0)) }
    val results = remember(lists, query) {
        val q = query.trim()
        when {
            q.isEmpty() -> null
            searchAll != null -> searchAll(q)
            else -> lists.flatMap { it.second }.distinctBy { it.id }.filter { it.title.contains(q, ignoreCase = true) }
        }
    }
    val shown: List<Pair<String?, List<Task>>> = when {
        results != null -> listOf(null to results)
        chips -> listOf(null to lists.getOrNull(chosen)?.second.orEmpty())
        lists.size == 1 -> listOf(null to lists.single().second)
        else -> lists
    }
    val colors = remember(allById) { folderColors(allById.values) }
    val now = remember { System.currentTimeMillis() }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LedgerSearchBackground) {
        Column(Modifier.heightIn(max = maxHeight).imePadding().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LedgerInk, modifier = Modifier.padding(bottom = 6.dp))
            if (chips && results == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                lists.forEachIndexed { i, (label, tasks) ->
                    FilterChip(
                        selected = i == chosen, onClick = { chosen = i },
                        label = { Text("$label ${tasks.size}") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = LedgerAccent, selectedLabelColor = LedgerAccentInk)
                    )
                }
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (onCreateNew != null) item {
                    Text("+ New task", color = LedgerAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onCreateNew).padding(vertical = 10.dp))
                }
                shown.forEach { (label, tasks) ->
                    if (label != null) item { Text(label.uppercase(), fontSize = 11.sp, color = LedgerMuted, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) }
                    items(tasks, key = { (label ?: "") + it.id }) { task ->
                        PickerRow(task, allById, colors[nearestFolder(task, allById)?.id], now) { onPick(task) }
                    }
                }
                if (shown.all { it.second.isEmpty() }) item { Text(if (results != null) "No task matches" else "Nothing here", color = LedgerMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp)) }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = LedgerMuted) },
                placeholder = { Text(if (searchAll != null) Labels.SEARCH_ALL_TASKS else "Search") },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
    }
}

// A row as the lists show it: the folder's colour bar, "Parent: title" (the parent dimmer), then the folder
// and the due date underneath.
@Composable
private fun PickerRow(task: Task, allById: Map<Long, Task>, barColor: Color?, now: Long, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 7.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(barColor ?: LedgerBorder, RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    buildAnnotatedString {
                        subtaskParentTitle(task, allById)?.let { withStyle(SpanStyle(color = LedgerMuted)) { append("$it: ") } }
                        append(task.title)
                    },
                    fontSize = 15.sp, color = LedgerInk, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                val folder = nearestFolder(task, allById)?.title
                val due = task.dueDate?.let { it to dueStatus(it, now) }
                if (folder != null || due != null) Text(
                    buildAnnotatedString {
                        folder?.let { append(it) }
                        due?.let { (at, status) ->
                            if (folder != null) append(" · ")
                            val color = when (status) { DueStatus.OVERDUE -> LedgerOverdue; DueStatus.TODAY -> LedgerDueTodayText; else -> LedgerMuted }
                            withStyle(SpanStyle(color = color)) { append(dueText(at, now)) }
                        }
                    },
                    fontSize = 12.sp, color = LedgerMuted
                )
            }
            if (task.isStarred) Text("★", color = LedgerStar, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
        }
        HorizontalDivider(color = LedgerBorder, thickness = 0.5.dp)
    }
}
