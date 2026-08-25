package com.kzhovn.todoapp.quickadd

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.ui.TaskEditActivity
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import com.kzhovn.todoapp.ui.theme.LedgerStar
import com.kzhovn.todoapp.ui.theme.LedgerUiFont
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as TodoApp).repository
        setContent {
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
                    QuickAddChip(
                        label = startDate?.let { formatDate(it) } ?: "Start",
                        set = startDate != null,
                        onClick = { pickDate(this@QuickAddActivity) { startDate = it } }
                    )
                    Spacer(Modifier.width(8.dp))
                    QuickAddChip(
                        label = dueDate?.let { formatDate(it) } ?: "Due",
                        set = dueDate != null,
                        onClick = { pickDate(this@QuickAddActivity) { dueDate = it } }
                    )
                    Spacer(Modifier.width(8.dp))
                    QuickAddChip(
                        label = folder?.title ?: "Folder",
                        set = folder != null,
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
                        onClick = {
                            lifecycleScope.launch {
                                repository.createTask(buildTask())
                                TodoWidget().updateAll(applicationContext)
                                finish()
                            }
                        },
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

@Composable
private fun QuickAddChip(label: String, set: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (set) LedgerAccentSoft else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, fontFamily = LedgerUiFont, fontSize = 12.sp, color = if (set) LedgerAccent else LedgerMuted)
    }
}

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

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("MMM d", Locale.US).format(Date(epochMillis))
