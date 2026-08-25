package com.kzhovn.todoapp.ui

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.recurrenceSelectionFromTask
import com.kzhovn.todoapp.recurrence.toTaskFields
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerStar
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.ui.theme.LedgerUiFont
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.launch
import java.util.Calendar

class TaskEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        val repository = app.repository
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        setContent {
            LedgerTheme {
            val viewModel = remember { TaskEditViewModel(repository) }
            val scope = rememberCoroutineScope()
            var task by remember { mutableStateOf(Task(id = taskId, title = "")) }
            // For an existing task, Save must stay disabled until the real task data has
            // loaded — otherwise a tap before the load completes commits this empty
            // placeholder, wiping the task's title and every other field.
            var isLoaded by remember { mutableStateOf(taskId == 0L) }
            var showDeleteConfirm by remember { mutableStateOf(false) }
            var deleteBlockedMessage by remember { mutableStateOf<String?>(null) }
            var folders by remember { mutableStateOf<List<Task>>(emptyList()) }
            var showFolderPicker by remember { mutableStateOf(false) }
            var showNewFolderDialog by remember { mutableStateOf(false) }
            var newFolderName by remember { mutableStateOf("") }
            var recurrence by remember { mutableStateOf(RecurrenceSelection(RecurrencePreset.NONE)) }

            LaunchedEffect(taskId) {
                if (taskId != 0L) {
                    viewModel.load(taskId)?.let { loaded ->
                        task = loaded
                        recurrence = recurrenceSelectionFromTask(loaded.recurrenceType, loaded.recurrenceRule)
                    }
                    isLoaded = true
                }
                folders = repository.getFolders()
            }

            Column(
                modifier = Modifier
                    .background(LedgerBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = task.title,
                        onValueChange = { task = task.copy(title = it) },
                        placeholder = { Text("Task name") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { task = task.copy(isStarred = !task.isStarred) }) {
                        Icon(
                            if (task.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Star",
                            tint = if (task.isStarred) LedgerStar else LedgerMuted
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("This is a folder", fontFamily = LedgerUiFont, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.weight(1f))
                    Switch(
                        checked = task.type == TaskType.FOLDER,
                        onCheckedChange = { task = task.copy(type = if (it) TaskType.FOLDER else TaskType.TASK) }
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row {
                    PropertyChip(
                        label = "Start",
                        valueText = task.startDate?.let(::formatChipDate),
                        icon = Icons.Filled.Event,
                        onClick = { pickDate(this@TaskEditActivity) { task = task.copy(startDate = it) } }
                    )
                    Spacer(Modifier.width(8.dp))
                    if (task.type == TaskType.TASK) {
                        PropertyChip(
                            label = "Due",
                            valueText = task.dueDate?.let(::formatChipDate),
                            icon = Icons.Filled.Flag,
                            onClick = { pickDate(this@TaskEditActivity) { task = task.copy(dueDate = it) } }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                PropertyChip(
                    label = "Folder",
                    valueText = folders.find { it.id == task.parentId }?.title,
                    icon = Icons.Filled.Folder,
                    onClick = { showFolderPicker = true }
                )

                if (task.type == TaskType.FOLDER) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Sequential (complete tasks in order)",
                            fontFamily = LedgerUiFont, fontSize = 12.sp, color = LedgerMuted,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = task.sequential, onCheckedChange = { task = task.copy(sequential = it) })
                    }
                }

                if (task.type == TaskType.TASK) {
                    Spacer(Modifier.height(12.dp))
                    Text("Repeat", fontFamily = LedgerUiFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerInk)
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        listOf(
                            RecurrencePreset.NONE to "None",
                            RecurrencePreset.DAILY to "Daily",
                            RecurrencePreset.WEEKLY to "Weekly",
                            RecurrencePreset.MONTHLY to "Monthly"
                        ).forEach { (preset, label) ->
                            Text(
                                label,
                                fontFamily = LedgerUiFont,
                                fontSize = 12.sp,
                                color = if (recurrence.preset == preset) LedgerAccent else LedgerMuted,
                                modifier = Modifier
                                    .clickable { recurrence = RecurrenceSelection(preset) }
                                    .padding(end = 12.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            "Every",
                            fontFamily = LedgerUiFont,
                            fontSize = 12.sp,
                            color = if (recurrence.preset == RecurrencePreset.EVERY_N_DAYS) LedgerAccent else LedgerMuted,
                            modifier = Modifier
                                .clickable { recurrence = RecurrenceSelection(RecurrencePreset.EVERY_N_DAYS, recurrence.n) }
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = if (recurrence.preset == RecurrencePreset.EVERY_N_DAYS) recurrence.n.toString() else "",
                            onValueChange = { text ->
                                val n = text.toIntOrNull()?.coerceAtLeast(1) ?: return@OutlinedTextField
                                recurrence = RecurrenceSelection(RecurrencePreset.EVERY_N_DAYS, n)
                            },
                            modifier = Modifier.width(56.dp),
                            singleLine = true
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("days", fontFamily = LedgerUiFont, fontSize = 12.sp, color = LedgerMuted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            "Repeat",
                            fontFamily = LedgerUiFont,
                            fontSize = 12.sp,
                            color = if (recurrence.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS) LedgerAccent else LedgerMuted,
                            modifier = Modifier
                                .clickable { recurrence = RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, recurrence.n) }
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = if (recurrence.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS) recurrence.n.toString() else "",
                            onValueChange = { text ->
                                val n = text.toIntOrNull()?.coerceAtLeast(1) ?: return@OutlinedTextField
                                recurrence = RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n)
                            },
                            modifier = Modifier.width(56.dp),
                            singleLine = true
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("days after completion", fontFamily = LedgerUiFont, fontSize = 12.sp, color = LedgerMuted)
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (taskId != 0L) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = LedgerOverdue)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            val (recurrenceType, recurrenceRule) = recurrence.toTaskFields()
                            viewModel.save(task.copy(recurrenceType = recurrenceType, recurrenceRule = recurrenceRule)) {
                                TodoWidget().updateAll(applicationContext)
                                finish()
                            }
                        },
                        enabled = isLoaded && task.title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)
                    ) {
                        Text("Save")
                    }
                }
                deleteBlockedMessage?.let {
                    Text(it, fontFamily = LedgerUiFont, fontSize = 12.sp, color = LedgerOverdue)
                }
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Delete this ${if (task.type == TaskType.FOLDER) "folder" else "task"}?") },
                    text = { Text("This can't be undone.") },
                    confirmButton = {
                        Button(onClick = {
                            showDeleteConfirm = false
                            scope.launch {
                                if (repository.deleteTask(task)) {
                                    TodoWidget().updateAll(applicationContext)
                                    finish()
                                } else {
                                    deleteBlockedMessage = "Can't delete: still has tasks inside"
                                }
                            }
                        }) { Text("Delete") }
                    },
                    dismissButton = { Button(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
                )
            }

            if (showFolderPicker) {
                AlertDialog(
                    onDismissRequest = { showFolderPicker = false },
                    confirmButton = {},
                    title = { Text("Choose a folder") },
                    text = {
                        Column {
                            Text(
                                "No folder",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { task = task.copy(parentId = null); showFolderPicker = false }
                                    .padding(vertical = 8.dp)
                            )
                            folders.filter { it.id != task.id }.forEach { f ->
                                Text(
                                    f.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { task = task.copy(parentId = f.id); showFolderPicker = false }
                                        .padding(vertical = 8.dp)
                                )
                            }
                            HorizontalDivider()
                            Text(
                                "+ New folder",
                                color = LedgerAccent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showFolderPicker = false; showNewFolderDialog = true }
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                )
            }

            if (showNewFolderDialog) {
                AlertDialog(
                    onDismissRequest = { showNewFolderDialog = false },
                    title = { Text("New folder") },
                    text = {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            placeholder = { Text("Folder name") }
                        )
                    },
                    confirmButton = {
                        Button(
                            enabled = newFolderName.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    val newId = repository.createTask(Task(type = TaskType.FOLDER, title = newFolderName))
                                    task = task.copy(parentId = newId)
                                    folders = repository.getFolders()
                                    newFolderName = ""
                                    showNewFolderDialog = false
                                }
                            }
                        ) { Text("Create") }
                    },
                    dismissButton = { Button(onClick = { showNewFolderDialog = false }) { Text("Cancel") } }
                )
            }
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }
}

// Duplicated from QuickAddActivity's private pickDate rather than shared across packages —
// keeps this task's diff scoped to this one file, per the brief.
private fun pickDate(activity: android.app.Activity, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance()
    DatePickerDialog(
        activity,
        { _, year, month, day ->
            val picked = Calendar.getInstance().apply {
                set(year, month, day, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            onPicked(picked.timeInMillis)
        },
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
    ).show()
}
