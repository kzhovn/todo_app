package com.kzhovn.todoapp.quickadd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.ui.PropertyChip
import com.kzhovn.todoapp.ui.TaskEditActivity
import com.kzhovn.todoapp.ui.formatChipDate
import com.kzhovn.todoapp.ui.pickDate
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import com.kzhovn.todoapp.ui.theme.LedgerStar
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.ui.theme.LedgerUiFont
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.launch

class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as TodoApp).repository
        setContent {
            LedgerTheme {
            var title by remember { mutableStateOf("") }
            var starred by remember { mutableStateOf(false) }
            var startDate by remember { mutableStateOf<Long?>(null) }
            var dueDate by remember { mutableStateOf<Long?>(null) }
            var folder by remember { mutableStateOf<Task?>(null) }
            var showFolderPicker by remember { mutableStateOf(false) }
            var folders by remember { mutableStateOf<List<Task>>(emptyList()) }

            LaunchedEffect(Unit) { folders = repository.getFolders() }

            fun buildTask() = Task(
                title = title,
                isStarred = starred,
                startDate = startDate,
                dueDate = dueDate,
                parentId = folder?.id
            )

            val focusManager = LocalFocusManager.current
            val onAdd: () -> Unit = {
                lifecycleScope.launch {
                    repository.createTask(buildTask())
                    TodoWidget().updateAll(applicationContext)
                    finish()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LedgerSearchBackground, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = { Text("Task name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            if (title.isNotBlank()) onAdd()
                        }),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { starred = !starred }) {
                        Icon(
                            if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Star",
                            tint = if (starred) LedgerStar else LedgerBorder
                        )
                    }
                }
                Row(modifier = Modifier.padding(vertical = 12.dp)) {
                    PropertyChip(
                        label = "Start",
                        valueText = startDate?.let(::formatChipDate),
                        icon = Icons.Filled.Event,
                        onClick = { pickDate(this@QuickAddActivity, startDate) { startDate = it } }
                    )
                    Spacer(Modifier.width(8.dp))
                    PropertyChip(
                        label = "Due",
                        valueText = dueDate?.let(::formatChipDate),
                        icon = Icons.Filled.Flag,
                        onClick = { pickDate(this@QuickAddActivity, dueDate) { dueDate = it } }
                    )
                    Spacer(Modifier.width(8.dp))
                    PropertyChip(
                        label = "Folder",
                        valueText = folder?.title,
                        icon = Icons.Filled.Folder,
                        onClick = { showFolderPicker = true }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Edit all details",
                        fontFamily = LedgerUiFont,
                        fontSize = 12.sp,
                        color = LedgerMuted,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable(enabled = title.isNotBlank()) {
                            lifecycleScope.launch {
                                val id = repository.createTask(buildTask())
                                startActivity(
                                    Intent(this@QuickAddActivity, TaskEditActivity::class.java)
                                        .putExtra(TaskEditActivity.EXTRA_TASK_ID, id)
                                )
                                finish()
                            }
                        }
                    )
                    Button(
                        onClick = onAdd,
                        enabled = title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)
                    ) {
                        Text("Add")
                    }
                }
            }

            if (showFolderPicker) {
                AlertDialog(
                    onDismissRequest = { showFolderPicker = false },
                    confirmButton = {},
                    title = { Text("Choose a folder") },
                    text = {
                        Column {
                            folders.forEach { f ->
                                Text(
                                    f.title,
                                    fontFamily = LedgerUiFont,
                                    color = LedgerInk,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { folder = f; showFolderPicker = false }
                                        .padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                )
            }
            }
        }
    }
}
