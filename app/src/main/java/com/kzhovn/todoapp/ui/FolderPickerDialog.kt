package com.kzhovn.todoapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.wouldCreateCycle
import com.kzhovn.todoapp.ui.theme.LedgerAccent

// Shared by TaskEditActivity (editing an existing task: needs cycle protection + "No folder" +
// inline folder creation) and QuickAddActivity (creating a brand-new task: needs none of those,
// since a not-yet-created task can never be its own ancestor).
@Composable
fun FolderPickerDialog(
    folders: List<Task>,
    excludeDescendantsOf: Long? = null,
    allById: Map<Long, Task> = emptyMap(),
    showNoFolderOption: Boolean,
    onPick: (Task?) -> Unit,
    onDismiss: () -> Unit,
    onCreateNew: (() -> Unit)? = null
) {
    val visibleFolders = if (excludeDescendantsOf != null) {
        folders.filter { !wouldCreateCycle(it.id, excludeDescendantsOf, allById) }
    } else {
        folders
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("Choose a folder") },
        text = {
            Column {
                if (showNoFolderOption) {
                    Text(
                        "No folder",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(null) }
                            .padding(vertical = 8.dp)
                    )
                }
                visibleFolders.forEach { f ->
                    Text(
                        f.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(f) }
                            .padding(vertical = 8.dp)
                    )
                }
                if (onCreateNew != null) {
                    HorizontalDivider()
                    Text(
                        "+ New folder",
                        color = LedgerAccent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCreateNew() }
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }
    )
}
