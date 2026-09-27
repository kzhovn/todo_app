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

// Shared by every "are you sure?" dialog; the confirm button is painted as the destructive action.
@Composable
fun ConfirmDialog(
    title: String,
    body: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = LedgerOverdue, contentColor = Color.White)
            ) { Text(confirmLabel) }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}

// Shared by every "one text field, Add/Cancel" dialog. The caller owns `value`'s state, so onConfirm
// decides what to do with it, including resetting it.
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

// Pick one task from a searchable list, or (when onCreateNew is given) create a new one instead.
@Composable
fun TaskPickerDialog(
    title: String,
    tasks: List<Task>,
    onPick: (Task) -> Unit,
    onCreateNew: (() -> Unit)?,
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
        confirmButton = { onCreateNew?.let { Button(onClick = it) { Text("New task") } } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}
