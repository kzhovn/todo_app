package com.kzhovn.todoapp.quickadd

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.ui.FolderPickerDialog
import com.kzhovn.todoapp.ui.PropertyChip
import com.kzhovn.todoapp.ui.TaskEditActivity
import com.kzhovn.todoapp.ui.formatChipDate
import com.kzhovn.todoapp.ui.pickDate
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import com.kzhovn.todoapp.ui.theme.LedgerStar
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.launch

// A bottom-sheet overlay (MLO-style). Launched from the widget it runs in its own task
// (manifest: empty taskAffinity), so closing it returns to the home screen, not the app.
class QuickAddActivity : ComponentActivity() {
    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as TodoApp).repository
        val fixedParentId = intent.getLongExtra(EXTRA_PARENT_ID, 0L).takeIf { it != 0L }
        val initialFolderId = intent.getLongExtra(EXTRA_FOLDER_ID, 0L).takeIf { it != 0L }
        setContent {
            LedgerTheme {
            var title by remember { mutableStateOf("") }
            var starred by remember { mutableStateOf(false) }
            var startDate by remember { mutableStateOf<Long?>(null) }
            var dueDate by remember { mutableStateOf<Long?>(null) }
            var folder by remember { mutableStateOf<Task?>(null) }
            var showFolderPicker by remember { mutableStateOf(false) }
            var folders by remember { mutableStateOf<List<Task>>(emptyList()) }
            val focus = remember { FocusRequester() }

            LaunchedEffect(Unit) {
                folders = repository.getFolders()
                folder = folders.firstOrNull { it.id == initialFolderId }
                focus.requestFocus()
            }

            fun buildTask(): Task {
                val parsed = QuickAddParser.parse(title)
                return Task(
                    title = parsed.title,
                    isStarred = starred,
                    // Chip values are an explicit, later user action, so they override whatever
                    // the shorthand parser found in the title text.
                    startDate = startDate ?: parsed.startDate,
                    dueDate = dueDate ?: parsed.dueDate,
                    // Adding a subtask fixes the parent to the task it was launched from — the
                    // Folder chip doesn't apply, since the subtask's position in the tree is
                    // already decided by that relationship.
                    parentId = fixedParentId ?: folder?.id
                )
            }

            // keepOpen: "hold to add another" saves and clears the form for the next task. The
            // folder stays, since a burst of adds usually goes to the same place.
            fun create(keepOpen: Boolean) {
                if (title.isBlank()) return
                val task = buildTask()
                lifecycleScope.launch {
                    repository.createTask(task)
                    TodoWidget().updateAll(applicationContext)
                    if (keepOpen) {
                        Toast.makeText(this@QuickAddActivity, "Added “${task.title}”", Toast.LENGTH_SHORT).show()
                    } else {
                        finish()
                    }
                }
                if (keepOpen) {
                    title = ""; starred = false; startDate = null; dueDate = null
                    focus.requestFocus()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LedgerSearchBackground, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = { Text("Title") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { create(keepOpen = false) }),
                        modifier = Modifier.weight(1f).focusRequester(focus)
                    )
                    IconButton(onClick = { starred = !starred }) {
                        Icon(
                            if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Star",
                            tint = if (starred) LedgerStar else LedgerBorder
                        )
                    }
                }
                Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PropertyChip(
                        label = "Start",
                        valueText = startDate?.let(::formatChipDate),
                        icon = Icons.Filled.Event,
                        onClick = { pickDate(this@QuickAddActivity, startDate) { startDate = it } },
                        showLabelWhenSet = false
                    )
                    Spacer(Modifier.width(8.dp))
                    PropertyChip(
                        label = "Due",
                        valueText = dueDate?.let(::formatChipDate),
                        icon = Icons.Filled.Flag,
                        onClick = { pickDate(this@QuickAddActivity, dueDate) { dueDate = it } },
                        showLabelWhenSet = false
                    )
                    if (fixedParentId == null) {
                        Spacer(Modifier.width(8.dp))
                        PropertyChip(
                            label = "Folder",
                            valueText = folder?.title,
                            icon = Icons.Filled.Folder,
                            onClick = { showFolderPicker = true }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        enabled = title.isNotBlank(),
                        onClick = {
                            lifecycleScope.launch {
                                val id = repository.createTask(buildTask())
                                startActivity(
                                    Intent(this@QuickAddActivity, TaskEditActivity::class.java)
                                        .putExtra(TaskEditActivity.EXTRA_TASK_ID, id)
                                )
                                finish()
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit all details", tint = LedgerMuted)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { finish() }) {
                        Text("CANCEL", color = LedgerAccent, fontWeight = FontWeight.Bold)
                    }
                    // Material buttons have no long-press, hence a clickable column styled to match.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .combinedClickable(
                                enabled = title.isNotBlank(),
                                onClick = { create(keepOpen = false) },
                                onLongClick = { create(keepOpen = true) }
                            )
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "CREATE",
                            color = if (title.isNotBlank()) LedgerAccent else LedgerBorder,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text("Hold to add another", color = LedgerMuted, fontSize = 11.sp)
                    }
                }
            }

            if (showFolderPicker) {
                FolderPickerDialog(
                    folders = folders,
                    showNoFolderOption = false,
                    onPick = { picked -> folder = picked; showFolderPicker = false },
                    onDismiss = { showFolderPicker = false }
                )
            }
            }
        }
        // The dialog theme otherwise centres a narrow window; this makes it a full-width bottom sheet.
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
    }

    companion object {
        const val EXTRA_PARENT_ID = "parent_id"
        const val EXTRA_FOLDER_ID = "folder_id"
    }
}
