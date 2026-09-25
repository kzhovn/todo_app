package com.kzhovn.todoapp.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.repository.BulkEdit
import com.kzhovn.todoapp.repository.DateChange
import com.kzhovn.todoapp.repository.FolderChange
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.launch

// Edits the properties that make sense across many tasks at once. Every row starts at "Keep", so
// only what the user explicitly changes is written.
class BulkEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        val taskIds = intent.getLongArrayExtra(EXTRA_TASK_IDS)?.toList().orEmpty()

        setContent {
            LedgerTheme {
                var edit by remember { mutableStateOf(BulkEdit()) }
                var folders by remember { mutableStateOf<List<Task>>(emptyList()) }
                var allTasks by remember { mutableStateOf<List<Task>>(emptyList()) }
                var allContexts by remember { mutableStateOf<List<TaskContext>>(emptyList()) }
                var showFolderPicker by remember { mutableStateOf(false) }
                var showDependencyPicker by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    allTasks = app.repository.getAllTasks()
                    folders = allTasks.filter { it.type == TaskType.FOLDER }
                    allContexts = app.contextRepository.getAllContexts()
                }
                val byId = remember(allTasks) { allTasks.associateBy { it.id } }

                Column(Modifier.fillMaxSize().background(LedgerBackground).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("Edit ${taskIds.size} tasks", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerInk)

                    Section("Star") {
                        Choice("Keep", edit.starred == null) { edit = edit.copy(starred = null) }
                        Choice("Star", edit.starred == true) { edit = edit.copy(starred = true, maybe = edit.maybe.takeIf { it != true }) }
                        Choice("Unstar", edit.starred == false) { edit = edit.copy(starred = false) }
                    }
                    Section("Maybe (?)") {
                        Choice("Keep", edit.maybe == null) { edit = edit.copy(maybe = null) }
                        Choice("Maybe", edit.maybe == true) { edit = edit.copy(maybe = true, starred = edit.starred.takeIf { it != true }) }
                        Choice("Not maybe", edit.maybe == false) { edit = edit.copy(maybe = false) }
                    }
                    DateSection("Start date", edit.startDate) { edit = edit.copy(startDate = it) }
                    DateSection("Due date", edit.dueDate) { edit = edit.copy(dueDate = it) }
                    Section("Folder") {
                        Choice("Keep", edit.moveTo == null) { edit = edit.copy(moveTo = null) }
                        Choice("Top level", edit.moveTo == FolderChange(null)) { edit = edit.copy(moveTo = FolderChange(null)) }
                        val target = edit.moveTo?.folderId?.let { byId[it]?.title }
                        Choice(target ?: "Move to…", target != null) { showFolderPicker = true }
                    }
                    Label("Add contexts")
                    SearchableMultiSelectDropdown(
                        label = "Choose contexts", items = allContexts, selectedIds = edit.addContextIds,
                        idOf = { it.id }, labelOf = { it.name },
                        onToggle = { id -> edit = edit.copy(addContextIds = edit.addContextIds.toggle(id), removeContextIds = edit.removeContextIds - id) }
                    )
                    Label("Remove contexts")
                    SearchableMultiSelectDropdown(
                        label = "Choose contexts", items = allContexts, selectedIds = edit.removeContextIds,
                        idOf = { it.id }, labelOf = { it.name },
                        onToggle = { id -> edit = edit.copy(removeContextIds = edit.removeContextIds.toggle(id), addContextIds = edit.addContextIds - id) }
                    )
                    Section("Wait for") {
                        Choice("Nothing new", edit.dependsOnId == null) { edit = edit.copy(dependsOnId = null) }
                        val blocker = edit.dependsOnId?.let { byId[it]?.title }
                        Choice(blocker ?: "Choose task…", blocker != null) { showDependencyPicker = true }
                    }

                    Spacer(Modifier.height(20.dp))
                    Button(enabled = taskIds.isNotEmpty() && edit != BulkEdit(), onClick = {
                        lifecycleScope.launch {
                            app.repository.applyBulkEdit(taskIds, edit)
                            TodoWidget().updateAll(applicationContext)
                            Toast.makeText(this@BulkEditActivity, "Updated ${taskIds.size} tasks", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }) { Text("Apply to ${taskIds.size} tasks") }
                }

                if (showFolderPicker) {
                    FolderPickerDialog(
                        folders = folders,
                        showNoFolderOption = false,
                        onPick = { picked -> edit = edit.copy(moveTo = FolderChange(picked?.id)); showFolderPicker = false },
                        onDismiss = { showFolderPicker = false }
                    )
                }
                if (showDependencyPicker) {
                    TaskPickerDialog(
                        title = "All selected tasks wait for…",
                        tasks = allTasks.filter { it.type == TaskType.TASK && !it.isComplete && it.id !in taskIds },
                        onPick = { edit = edit.copy(dependsOnId = it.id); showDependencyPicker = false },
                        onCreateNew = null,
                        onDismiss = { showDependencyPicker = false }
                    )
                }
            }
        }
    }

    @Composable
    private fun DateSection(title: String, change: DateChange?, onChange: (DateChange?) -> Unit) {
        Section(title) {
            Choice("Keep", change == null) { onChange(null) }
            Choice("Clear", change == DateChange(null)) { onChange(DateChange(null)) }
            val date = change?.date
            Choice(date?.let(::formatChipDate) ?: "Set…", date != null) {
                pickDate(this@BulkEditActivity, date) { onChange(DateChange(it)) }
            }
        }
    }

    companion object {
        const val EXTRA_TASK_IDS = "task_ids"
    }
}

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LedgerMuted)
}

@Composable
private fun Section(title: String, options: @Composable () -> Unit) {
    Label(title)
    Row(Modifier.padding(top = 6.dp)) { options() }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    SelectablePill(label, selected = selected, fontSize = 14.sp, onClick = onClick)
    Spacer(Modifier.width(8.dp))
}
