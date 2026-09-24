package com.kzhovn.todoapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.ui.theme.LedgerOverdue

// Shared by every "are you sure?" dialog. destructive = true (the common case) paints the confirm
// button LedgerOverdue/white, matching the visual weight a delete/remove action needs; pass false
// for a confirm that isn't itself destructive but still needs a two-button choice.
@Composable
fun ConfirmDialog(
    title: String,
    body: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(containerColor = LedgerOverdue, contentColor = Color.White)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) { Text(confirmLabel) }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}

// Shared by every "one text field, Add/Cancel" dialog. A controlled component: the caller owns
// `value`'s backing state (matching how newSubtaskTitle/newFolderName are already held today), so
// onConfirm decides what to do with the current value, including resetting it afterward.
@Composable
fun TextInputDialog(
    title: String,
    placeholder: String,
    confirmLabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(placeholder) }
            )
        },
        confirmButton = {
            Button(enabled = value.isNotBlank(), onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}

// Pick one task from a searchable list, or create a new one instead.
@Composable
fun TaskPickerDialog(
    title: String,
    tasks: List<Task>,
    onPick: (Task) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(tasks, query) { tasks.filter { it.title.contains(query.trim(), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search tasks") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id }) { task ->
                        Text(task.title, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { onPick(task) }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onCreateNew) { Text("New task") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}
