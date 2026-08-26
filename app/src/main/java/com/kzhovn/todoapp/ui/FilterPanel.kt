package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerUiFont

@Composable
fun FilterPanel(
    filters: SearchFilters,
    folders: List<Task>,
    contexts: List<TaskContext>,
    onFiltersChange: (SearchFilters) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row {
            FilterChipItem("Starred", filters.starredOnly) {
                onFiltersChange(filters.copy(starredOnly = !filters.starredOnly))
            }
            Spacer(Modifier.width(6.dp))
            FilterChipItem("Completed", filters.includeCompleted) {
                onFiltersChange(filters.copy(includeCompleted = !filters.includeCompleted))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text("Folder:", fontFamily = LedgerUiFont, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(end = 4.dp))
            FilterChipItem("Any", filters.folderId == null) { onFiltersChange(filters.copy(folderId = null)) }
            folders.forEach { f ->
                Spacer(Modifier.width(6.dp))
                FilterChipItem(f.title, filters.folderId == f.id) { onFiltersChange(filters.copy(folderId = f.id)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text("Context:", fontFamily = LedgerUiFont, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(end = 4.dp))
            FilterChipItem("Any", filters.contextId == null) { onFiltersChange(filters.copy(contextId = null)) }
            contexts.forEach { c ->
                Spacer(Modifier.width(6.dp))
                FilterChipItem(c.name, filters.contextId == c.id) { onFiltersChange(filters.copy(contextId = c.id)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            val activity = LocalContext.current as android.app.Activity
            PropertyChip(
                label = "Due after",
                valueText = filters.dueAfter?.let(::formatChipDate),
                icon = Icons.Filled.Event,
                onClick = { pickDate(activity, filters.dueAfter) { onFiltersChange(filters.copy(dueAfter = it)) } },
                onClear = { onFiltersChange(filters.copy(dueAfter = null)) }
            )
            Spacer(Modifier.width(8.dp))
            PropertyChip(
                label = "Due before",
                valueText = filters.dueBefore?.let(::formatChipDate),
                icon = Icons.Filled.Flag,
                onClick = { pickDate(activity, filters.dueBefore) { onFiltersChange(filters.copy(dueBefore = it)) } },
                onClear = { onFiltersChange(filters.copy(dueBefore = null)) }
            )
        }
    }
}

@Composable
private fun FilterChipItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontFamily = LedgerUiFont,
        fontSize = 12.sp,
        color = if (selected) LedgerAccent else LedgerMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) LedgerAccentSoft else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
