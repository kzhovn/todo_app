package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.folderTree
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.folderColors

// Shared by TaskEditActivity (editing an existing task: needs cycle protection + Labels.NO_FOLDER +
// inline folder creation), BulkEditActivity and QuickAddActivity (whose list has checklists too).
// A tree: each folder in its own colour, subfolders indented under their parent, the current one
// (selectedId) highlighted with a tick.
@Composable
fun FolderPickerDialog(
    folders: List<Task>,
    excludeDescendantsOf: Long? = null,
    allById: Map<Long, Task> = emptyMap(),
    showNoFolderOption: Boolean,
    onPick: (Task?) -> Unit,
    onDismiss: () -> Unit,
    onCreateNew: (() -> Unit)? = null,
    selectedId: Long? = null,
    title: String = "Choose a folder",
    noFolderLabel: String = Labels.NO_FOLDER
) {
    val colors = remember(folders) { folderColors(folders) }
    val rows = remember(folders, excludeDescendantsOf) { folderTree(folders, excludeDescendantsOf, allById.ifEmpty { folders.associateBy { it.id } }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (showNoFolderOption) PickerRow(noFolderLabel, Icons.Filled.FolderOff, LedgerMuted, depth = 0, selected = selectedId == null, muted = true) { onPick(null) }
                rows.forEach { (f, depth) ->
                    val checklist = f.type == TaskType.CHECKLIST
                    PickerRow(
                        f.title, if (checklist) Icons.Filled.Checklist else Icons.Filled.Folder, if (checklist) LedgerMuted else colors[f.id] ?: LedgerMuted,
                        depth, selected = f.id == selectedId
                    ) { onPick(f) }
                }
                if (onCreateNew != null) {
                    HorizontalDivider(color = LedgerBorder, modifier = Modifier.padding(top = 6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onCreateNew() }.padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = LedgerAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("New folder", color = LedgerAccent, fontSize = 15.sp)
                    }
                }
            }
        }
    )
}

@Composable
private fun PickerRow(title: String, icon: ImageVector, tint: Color, depth: Int, selected: Boolean, muted: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) LedgerAccentSoft else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp + 24.dp * depth, end = 12.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            title, fontSize = 15.sp, modifier = Modifier.weight(1f),
            color = when { selected -> LedgerAccent; muted -> LedgerMuted; else -> LedgerInk },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Current", tint = LedgerAccent, modifier = Modifier.size(18.dp))
    }
}
