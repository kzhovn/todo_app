package com.kzhovn.todoapp.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
