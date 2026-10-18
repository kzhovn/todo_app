package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.CompleteQuestion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import com.kzhovn.todoapp.repository.TaskRepository
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
        dismissButton = { Button(onClick = onDismiss) { Text(Labels.CANCEL) } }
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
        dismissButton = { Button(onClick = onDismiss) { Text(Labels.CANCEL) } }
    )
}

// Completing a task with open subtasks asks first, wherever it's ticked (see completeQuestion).
@Composable
fun CompleteSubtasksDialog(question: CompleteQuestion, onCompleteAll: () -> Unit, onMoveOut: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Complete this task?") },
    text = { Text(question.text) },
    confirmButton = { Button(onClick = onCompleteAll) { Text(question.completeAll) } },
    dismissButton = {
        Row {
            Button(onClick = onMoveOut) { Text(question.moveOut) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDismiss) { Text(question.cancel) }
        }
    }
)

// Offers Undo for the latest delete (TaskRepository.lastDeleted) on the screen it was made from: the
// lists, or the editor (an item, a subtask, Clear checked).
@Composable
fun UndoDelete(repository: TaskRepository, hostState: SnackbarHostState, onUndone: suspend () -> Unit = {}) {
    val lastDeleted by repository.lastDeleted.collectAsState()
    // Undo clears lastDeleted, which restarts this effect; the undo and refresh run outside it so that
    // doesn't cut them short.
    val scope = rememberCoroutineScope()
    val undone by rememberUpdatedState(onUndone) // the screen's latest, should it change during the snackbar
    LaunchedEffect(lastDeleted) {
        val deleted = lastDeleted ?: return@LaunchedEffect
        val ids = deleted.tasks.map { it.id }.toSet()
        val title = (deleted.tasks.firstOrNull { it.parentId !in ids } ?: deleted.tasks.first()).title
        val extra = deleted.tasks.size - 1
        val result = hostState.showSnackbar(Labels.deleted(title, extra), actionLabel = Labels.UNDO, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) scope.launch { repository.undoDelete(deleted); undone() } else repository.clearLastDeleted()
    }
}
