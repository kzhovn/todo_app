package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.isDoable
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.contexts.ContextsActivity
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.formatDuration
import com.kzhovn.todoapp.data.wouldCreateCycle
import com.kzhovn.todoapp.data.wouldCreateDependencyCycle
import com.kzhovn.todoapp.focus.FocusActivity
import com.kzhovn.todoapp.notifications.PinnedTask
import com.kzhovn.todoapp.quickadd.QuickAddActivity
import com.kzhovn.todoapp.sync.SyncJson
import com.kzhovn.todoapp.ui.TaskEditViewModel.Question
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

// Which of the editor's dialogs is open; only ever one at a time.
private sealed interface EditorDialog {
    data class Delete(val descendants: Int) : EditorDialog
    data object FirstStep : EditorDialog
    data class UpdateSubtasks(val question: Question.UpdateSubtasks) : EditorDialog
    data object SubtaskPicker : EditorDialog
    data object DependentPicker : EditorDialog
    data object PrerequisitePicker : EditorDialog
    data object Repeat : EditorDialog
    data object Timer : EditorDialog
    data object NewPendingSubtask : EditorDialog
    data object NewPendingDependent : EditorDialog
    data object NewPrerequisite : EditorDialog
    data object FolderPicker : EditorDialog
    data object NewFolder : EditorDialog
    data object ClearChecked : EditorDialog
    data class UncheckedItems(val count: Int) : EditorDialog
}

class TaskEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        val initialType = when {
            intent.getBooleanExtra(EXTRA_CREATE_AS_FOLDER, false) -> TaskType.FOLDER
            intent.getBooleanExtra(EXTRA_CREATE_AS_PROJECT, false) -> TaskType.PROJECT
            else -> TaskType.TASK
        }
        setContent {
            LedgerTheme {
                val vm = remember { TaskEditViewModel(app.repository, app.contextRepository, taskId, initialType) }
                Editor(vm)
            }
        }
    }

    @Composable
    private fun Editor(vm: TaskEditViewModel) {
        val scope = rememberCoroutineScope()
        var dialog by remember { mutableStateOf<EditorDialog?>(null) }
        // Pinning takes effect at once (it's "what I'm doing now"), not on Save; a new task's, on save.
        var pinned by remember { mutableStateOf(!vm.isNew && PinnedTask.pinnedId(this) == vm.taskId) }
        val openTask = { id: Long -> startActivity(Intent(this, TaskEditActivity::class.java).putExtra(EXTRA_TASK_ID, id)) }

        LaunchedEffect(Unit) {
            vm.load(
                draft = intent.getStringExtra(EXTRA_DRAFT)?.let { SyncJson.decodeFromString(Task.serializer(), it) },
                draftDependsOn = intent.getLongExtra(EXTRA_DRAFT_DEPENDS_ON, 0L).takeIf { it != 0L }
            )
        }
        // Screens opened from here (contexts, a new subtask) are plain startActivity, so coming back
        // re-pulls what they may have changed.
        LifecycleResumeEffect(Unit) {
            scope.launch {
                vm.refreshContexts()
                if (!vm.isNew) vm.refresh()
            }
            onPauseOrDispose { }
        }

        fun save(clearOn: List<Task> = emptyList(), fields: Set<com.kzhovn.todoapp.repository.InheritedField> = emptySet(), firstStep: String? = null) =
            vm.saveEdits(clearOn, fields, firstStep) { savedId, saved ->
                if (vm.isNew && pinned) PinnedTask.pin(this, saved.copy(id = savedId))
                finish()
            }

        val onSave: () -> Unit = {
            scope.launch {
                when (val question = vm.questionBeforeSave()) {
                    null -> save()
                    Question.FirstStep -> dialog = EditorDialog.FirstStep
                    is Question.UpdateSubtasks -> dialog = EditorDialog.UpdateSubtasks(question)
                }
            }
        }

        // Back saves instead of discarding (Delete is how to throw a new task away).
        BackHandler(enabled = vm.isLoaded) { if (vm.isDirty() && vm.task.title.isNotBlank()) onSave() else finish() }
        // Switching away (another app, or a screen opened from here) saves an existing task in place.
        // Skipped when saving would need a question first (a project's first step, updating subtasks).
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            if (vm.isNew || !vm.isLoaded || isFinishing || !vm.isDirty() || vm.task.title.isBlank()) return@LifecycleEventEffect
            scope.launch { if (vm.questionBeforeSave() == null) vm.saveEdits { _, _ -> } }
        }

        Column(Modifier.fillMaxSize().background(LedgerBackground).verticalScroll(rememberScrollState()).padding(16.dp)) {
            TitleBox(
                vm, pinned,
                onTogglePin = {
                    if (!vm.isNew) { if (pinned) PinnedTask.unpin(this@TaskEditActivity) else PinnedTask.pin(this@TaskEditActivity, vm.task) }
                    pinned = !pinned
                },
                onFocus = {
                    if (!vm.isNew) startActivity(Intent(this@TaskEditActivity, FocusActivity::class.java).putExtra(FocusActivity.EXTRA_TASK_ID, vm.taskId))
                    else Toast.makeText(this@TaskEditActivity, "Save the task to focus on it", Toast.LENGTH_SHORT).show()
                },
                onDone = { if (vm.isLoaded && vm.task.title.isNotBlank()) onSave() }
            )
            TypeRow(vm)
            TimingSection(vm, this@TaskEditActivity, onRepeat = { dialog = EditorDialog.Repeat }, onTimer = { dialog = EditorDialog.Timer })
            PropertiesSection(
                vm,
                onPickFolder = { dialog = EditorDialog.FolderPicker },
                onManageContexts = { startActivity(Intent(this@TaskEditActivity, ContextsActivity::class.java)) }
            )
            if (vm.task.type == TaskType.CHECKLIST) ItemsSection(vm, onClearChecked = { dialog = EditorDialog.ClearChecked })
            RelatedSection(
                vm, openTask,
                onAddSubtask = { dialog = EditorDialog.SubtaskPicker },
                onAddPrerequisite = { dialog = EditorDialog.PrerequisitePicker },
                onAddDependent = { dialog = EditorDialog.DependentPicker }
            )
            SubtaskOptions(vm)
            BottomBar(
                vm,
                onDelete = { scope.launch { dialog = EditorDialog.Delete(vm.descendantCount()) } },
                onCompleteList = {
                    val unchecked = vm.allTasks.count { it.parentId == vm.taskId && !it.isComplete }
                    if (unchecked > 0) dialog = EditorDialog.UncheckedItems(unchecked)
                    else vm.completeChecklist(moveUncheckedToNewList = false) { finish() }
                },
                onSave = onSave
            )
        }

        val close = { dialog = null }
        when (val d = dialog) {
            null -> {}
            is EditorDialog.Delete -> ConfirmDialog(
                title = "Delete this ${if (vm.task.type == TaskType.FOLDER) "folder" else "task"}?",
                body = if (d.descendants > 0) "This will also delete ${d.descendants} subtask${if (d.descendants == 1) "" else "s"}." else null,
                confirmLabel = "Delete",
                onConfirm = {
                    close()
                    // A new task isn't saved yet: deleting it just discards it.
                    if (vm.isNew) finish() else vm.delete { finish() }
                },
                onDismiss = close
            )
            EditorDialog.FirstStep -> TextDialog(
                title = "First step of this project", placeholder = "Subtask", confirmLabel = "Save project", onDismiss = close
            ) { close(); save(firstStep = it.trim()) }
            is EditorDialog.UpdateSubtasks -> {
                val (overriding, fields) = d.question
                val one = overriding.size == 1
                AlertDialog(
                    onDismissRequest = close,
                    title = { Text("Update subtasks too?") },
                    text = { Text("${overriding.size} subtask${if (one) " has its" else "s have their"} own ${fields.joinToString(" and ") { it.label }}. Clear ${if (one) "it" else "them"} so ${if (one) "it follows" else "they follow"} this task?") },
                    confirmButton = { Button(onClick = { close(); save(overriding, fields) }) { Text("Update subtasks") } },
                    dismissButton = { Button(onClick = { close(); save() }) { Text("Only this task") } }
                )
            }
            EditorDialog.SubtaskPicker -> {
                val task = vm.task
                // Existing tasks can be moved under this one (anything but its own ancestors), or a new one created.
                val candidates = remember(vm.allTasks, task.id) {
                    vm.allTasks.filter {
                        it.id != task.id && it.type != TaskType.FOLDER && !it.isComplete && (task.id == 0L || it.parentId != task.id) && it.id !in vm.pendingChildIds &&
                            !wouldCreateCycle(task.id, it.id, vm.allById)
                    }
                }
                TaskPickerDialog(
                    title = "Add a subtask",
                    tasks = candidates,
                    onPick = { close(); vm.adoptSubtask(it) },
                    onCreateNew = {
                        if (vm.isNew) dialog = EditorDialog.NewPendingSubtask
                        else { close(); startActivity(Intent(this, QuickAddActivity::class.java).putExtra(QuickAddActivity.EXTRA_PARENT_ID, task.id)) }
                    },
                    onDismiss = close
                )
            }
            EditorDialog.DependentPicker -> {
                val task = vm.task
                val candidates = remember(vm.allTasks, vm.dependencyEdges, task.id) {
                    vm.allTasks.filter {
                        it.id != task.id && it.type.isDoable && !it.isComplete &&
                            (task.id == 0L || vm.dependencyEdges.none { e -> e.taskId == it.id && e.dependsOnTaskId == task.id }) && it.id !in vm.pendingDependentIds &&
                            !wouldCreateDependencyCycle(task.id, it.id, vm.dependencyEdges)
                    }
                }
                TaskPickerDialog(
                    title = "Add a dependent task",
                    tasks = candidates,
                    onPick = { picked ->
                        close()
                        vm.addDependent(picked) { Toast.makeText(this, "“${picked.title}” now depends on this", Toast.LENGTH_SHORT).show() }
                    },
                    onCreateNew = {
                        if (vm.isNew) dialog = EditorDialog.NewPendingDependent
                        else {
                            close()
                            // The new task goes in this task's folder by default, since dependent work usually belongs together.
                            startActivity(
                                Intent(this, QuickAddActivity::class.java)
                                    .putExtra(QuickAddActivity.EXTRA_DEPENDS_ON, task.id)
                                    .apply { vm.folderId()?.let { putExtra(QuickAddActivity.EXTRA_FOLDER_ID, it) } }
                            )
                        }
                    },
                    onDismiss = close
                )
            }
            // + Prerequisite: an existing task this one waits on, or a new one.
            EditorDialog.PrerequisitePicker -> {
                val task = vm.task
                val candidates = remember(vm.allTasks, vm.dependencyEdges, task.id, vm.dependencyIds) {
                    vm.allTasks.filter {
                        it.id != task.id && it.type.isDoable && !it.isComplete && it.id !in vm.dependencyIds &&
                            !wouldCreateDependencyCycle(it.id, task.id, vm.dependencyEdges)
                    }
                }
                TaskPickerDialog(
                    title = "Add a prerequisite",
                    tasks = candidates,
                    onPick = { picked -> vm.dependencyIds = vm.dependencyIds + picked.id; close() },
                    onCreateNew = { dialog = EditorDialog.NewPrerequisite },
                    onDismiss = close
                )
            }
            EditorDialog.Repeat -> RepeatSheet(
                current = vm.recurrence,
                anchor = vm.task.startDate ?: vm.task.dueDate ?: System.currentTimeMillis(),
                onDone = { vm.recurrence = it; close() },
                onDismiss = close
            )
            EditorDialog.Timer -> TimerDialog(vm, onDismiss = close)
            EditorDialog.NewPendingSubtask -> TextDialog(title = "Add a subtask", placeholder = "Subtask", confirmLabel = "Add", onDismiss = close) {
                it.trim().takeIf(String::isNotEmpty)?.let { title -> vm.pendingSubtasks = vm.pendingSubtasks + title }
                close()
            }
            EditorDialog.NewPendingDependent -> TextDialog(title = "Add a dependent task", placeholder = Labels.DEPENDENT, confirmLabel = "Add", onDismiss = close) {
                it.trim().takeIf(String::isNotEmpty)?.let { title -> vm.pendingDependents = vm.pendingDependents + title }
                close()
            }
            // Created right away (in this task's folder) and ticked; the dependency saves with the task.
            EditorDialog.NewPrerequisite -> TextDialog(title = "New task this depends on", placeholder = "Task", confirmLabel = "Add", onDismiss = close) {
                close()
                vm.createPrerequisite(it)
            }
            EditorDialog.FolderPicker -> FolderPickerDialog(
                folders = vm.folders,
                excludeDescendantsOf = vm.task.id,
                allById = vm.allById,
                showNoFolderOption = true,
                onPick = { picked -> vm.task = vm.task.copy(parentId = picked?.id); close() },
                onDismiss = close,
                onCreateNew = { dialog = EditorDialog.NewFolder }
            )
            EditorDialog.NewFolder -> TextDialog(title = "New folder", placeholder = "Folder name", confirmLabel = "Create", onDismiss = close) {
                vm.createFolder(it)
                close()
            }
            EditorDialog.ClearChecked -> ConfirmDialog(
                title = Labels.clearChecked(vm.allTasks.count { it.parentId == vm.taskId && it.isComplete }),
                body = null,
                confirmLabel = Labels.CLEAR_CHECKED,
                onConfirm = { close(); vm.clearChecked() },
                onDismiss = close
            )
            is EditorDialog.UncheckedItems -> {
                val complete = { moveToNewList: Boolean -> close(); vm.completeChecklist(moveToNewList) { finish() } }
                AlertDialog(
                    onDismissRequest = close,
                    title = { Text(Labels.COMPLETE_LIST) },
                    text = { Text(Labels.uncheckedItems(d.count)) },
                    confirmButton = { TextButton(onClick = { complete(true) }) { Text(Labels.MOVE_TO_NEW_LIST) } },
                    dismissButton = { TextButton(onClick = { complete(false) }) { Text(Labels.COMPLETE_THEM_TOO) } }
                )
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_CREATE_AS_FOLDER = "create_as_folder"
        const val EXTRA_CREATE_AS_PROJECT = "create_as_project"
        // An unsaved new task (Task JSON) to start from, e.g. quick add's "Edit all details".
        const val EXTRA_DRAFT = "draft"
        const val EXTRA_DRAFT_DEPENDS_ON = "draft_depends_on"
    }
}

// A one-field dialog that holds its own text.
@Composable
private fun TextDialog(title: String, placeholder: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    TextInputDialog(title = title, placeholder = placeholder, confirmLabel = confirmLabel, value = text, onValueChange = { text = it }, onConfirm = { onConfirm(text) }, onDismiss = onDismiss)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerDialog(vm: TaskEditViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Labels.TIMER) },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Labels.TIMER_PRESETS.forEach { minutes ->
                        SelectablePill(formatDuration(minutes), selected = vm.task.durationMinutes == minutes) { vm.task = vm.task.copy(durationMinutes = minutes) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    DurationField(vm.task.durationMinutes) { vm.task = vm.task.copy(durationMinutes = it) }
                    Spacer(Modifier.width(6.dp))
                    Text("minutes", fontSize = 12.sp, color = LedgerMuted)
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } }
    )
}
