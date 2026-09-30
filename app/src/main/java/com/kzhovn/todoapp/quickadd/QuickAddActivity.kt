package com.kzhovn.todoapp.quickadd

import com.kzhovn.todoapp.focus.FocusActivity
import com.kzhovn.todoapp.data.splitItems
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.findFolder
import com.kzhovn.todoapp.data.DEFAULT_FOLDER
import com.kzhovn.todoapp.ui.theme.folderColors
import com.kzhovn.todoapp.data.nextRollover
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.AppSettings
import androidx.compose.material.icons.filled.AcUnit
import com.kzhovn.todoapp.ui.StarIcon
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
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
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
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.sync.SyncJson
import com.kzhovn.todoapp.ui.FolderPickerDialog
import com.kzhovn.todoapp.ui.PropertyChip
import com.kzhovn.todoapp.ui.TaskEditActivity
import com.kzhovn.todoapp.ui.formatChipDate
import com.kzhovn.todoapp.ui.pickDate
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import com.kzhovn.todoapp.ui.theme.LedgerTheme
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
        // "Add dependent task": the new task depends on this one.
        val dependsOnId = intent.getLongExtra(EXTRA_DEPENDS_ON, 0L).takeIf { it != 0L }
        val startStarred = intent.getBooleanExtra(EXTRA_STARRED, false)
        setContent {
            LedgerTheme {
            var title by remember { mutableStateOf("") }
            var starred by remember { mutableStateOf(startStarred) }
            var startDate by remember { mutableStateOf<Long?>(null) }
            var dueDate by remember { mutableStateOf<Long?>(null) }
            var folder by remember { mutableStateOf<Task?>(null) }
            var showFolderPicker by remember { mutableStateOf(false) }
            var folders by remember { mutableStateOf<List<Task>>(emptyList()) }
            var todayOnly by remember { mutableStateOf(false) }
            var dependsOnTitle by remember { mutableStateOf<String?>(null) }
            val focus = remember { FocusRequester() }

            LaunchedEffect(Unit) {
                // Checklists too: picking one makes the text its new items ("milk, eggs").
                folders = repository.getAllTasks().filter { it.type == TaskType.FOLDER || (it.type == TaskType.CHECKLIST && !it.isComplete) }
                // With no folder asked for, new tasks land in folder mode's folder, or Personal (if it exists),
                // as Discord adds do.
                folder = if (initialFolderId != null) folders.firstOrNull { it.id == initialFolderId }
                else AppSettings.modeFolderId(this@QuickAddActivity)?.let { id -> folders.firstOrNull { it.id == id && it.type == TaskType.FOLDER } }
                    ?: findFolder(folders, DEFAULT_FOLDER)
                dependsOnTitle = dependsOnId?.let { repository.getTask(it)?.title }
                focus.requestFocus()
            }

            // Everything the text asks for (the syntax every device shares), plus what's set on the chips.
            suspend fun buildAdd(): QuickAdd {
                // Read before suspending: "hold to add another" clears the form straight after.
                val text = title; val star = starred; val start = startDate; val due = dueDate; val today = todayOnly; val chip = folder
                val now = System.currentTimeMillis()
                val rollover = AppSettings.rolloverHour(this@QuickAddActivity)
                if (fixedParentId == null && chip?.type == TaskType.CHECKLIST) return QuickAdd(task = null, items = splitItems(text), intoChecklist = chip.id)
                val add = repository.planQuickAdd(text, rollover, now)
                val task = add.task ?: return add
                return add.copy(task = task.copy(
                    isStarred = star || task.isStarred,
                    // Chip values are an explicit, later user action, so they override whatever
                    // the shorthand parser found in the title text.
                    startDate = start ?: task.startDate,
                    dueDate = due ?: task.dueDate,
                    // Adding a subtask fixes the parent to the task it was launched from. Otherwise a
                    // folder named in the text ("work: …") beats the chip, which starts on Personal.
                    parentId = fixedParentId ?: task.parentId ?: chip?.id,
                    expiresAt = if (today) nextRollover(now, rollover) else task.expiresAt
                ))
            }

            // keepOpen: "hold to add another" saves and clears the form for the next task. The
            // folder stays, since a burst of adds usually goes to the same place.
            fun create(keepOpen: Boolean) {
                if (title.isBlank()) return
                lifecycleScope.launch {
                    val add = buildAdd()
                    val id = repository.quickAdd(add, defaultParent = null, System.currentTimeMillis()) ?: return@launch
                    if (add.task != null) dependsOnId?.let { repository.addDependency(id, it) }
                    // "-f": every device goes into focus on it, this one included.
                    if (add.focus) startActivity(Intent(this@QuickAddActivity, FocusActivity::class.java))
                    if (keepOpen) {
                        Toast.makeText(this@QuickAddActivity, add.task?.let { "Added “${it.title}”" } ?: "Added ${add.items.size} items", Toast.LENGTH_SHORT).show()
                    } else {
                        finish()
                    }
                }
                if (keepOpen) {
                    title = ""; starred = startStarred; startDate = null; dueDate = null; todayOnly = false
                    focus.requestFocus()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LedgerSearchBackground, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                dependsOnTitle?.let { Text("Depends on: $it", color = LedgerMuted, fontSize = 12.sp) }
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
                        StarIcon(starred)
                    }
                }
                Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PropertyChip(
                        label = "Start",
                        valueText = startDate?.let(::formatChipDate),
                        icon = Icons.Filled.Event,
                        onClick = { pickDate(this@QuickAddActivity, startDate, title = Labels.START) { startDate = it } },
                        showLabelWhenSet = false
                    )
                    Spacer(Modifier.width(8.dp))
                    PropertyChip(
                        label = "Due",
                        valueText = dueDate?.let(::formatChipDate),
                        icon = Icons.Filled.Flag,
                        onClick = { pickDate(this@QuickAddActivity, dueDate, title = Labels.DUE) { dueDate = it } },
                        showLabelWhenSet = false
                    )
                    Spacer(Modifier.width(8.dp))
                    PropertyChip(
                        label = Labels.TODAY_ONLY,
                        valueText = Labels.TODAY_ONLY.takeIf { todayOnly },
                        icon = Icons.Filled.AcUnit,
                        onClick = { todayOnly = !todayOnly },
                        iconOnly = true
                    )
                    if (fixedParentId == null) {
                        Spacer(Modifier.width(8.dp))
                        PropertyChip(
                            label = Labels.FOLDER,
                            valueText = folder?.title,
                            icon = Icons.Filled.Folder,
                            onClick = { showFolderPicker = true },
                            showLabelWhenSet = false,
                            tint = folder?.let { folderColors(folders.filter { f -> f.type == TaskType.FOLDER })[it.id] } ?: LedgerAccent
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Opens the full editor on an unsaved draft of what's typed so far; backing out of
                    // the editor leaves nothing behind.
                    TextButton(onClick = {
                        lifecycleScope.launch {
                            val draft = buildAdd().task ?: Task(title = title)
                            startActivity(
                                Intent(this@QuickAddActivity, TaskEditActivity::class.java)
                                    .putExtra(TaskEditActivity.EXTRA_DRAFT, SyncJson.encodeToString(Task.serializer(), draft))
                                    .apply { dependsOnId?.let { putExtra(TaskEditActivity.EXTRA_DRAFT_DEPENDS_ON, it) } }
                            )
                            finish()
                        }
                    }) {
                        Text("EDIT ALL DETAILS", color = LedgerAccent, fontWeight = FontWeight.Bold)
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
                    onDismiss = { showFolderPicker = false },
                    selectedId = folder?.id
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
        const val EXTRA_DEPENDS_ON = "depends_on"
        const val EXTRA_STARRED = "starred"
    }
}
