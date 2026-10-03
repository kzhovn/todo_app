package com.kzhovn.todoapp

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import com.kzhovn.todoapp.ui.setFolderMode
import com.kzhovn.todoapp.ui.TaskCheckbox
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.folderColors
import com.kzhovn.todoapp.ui.SearchBar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.focus.FocusActivity
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.Labels
import androidx.compose.material.icons.filled.AccountTree
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kzhovn.todoapp.contexts.ContextsActivity
import com.kzhovn.todoapp.ui.BulkEditActivity
import com.kzhovn.todoapp.ui.AppDrawer
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Checklist
import com.kzhovn.todoapp.sync.SyncSettings
import com.kzhovn.todoapp.sync.SyncWorker
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.quickadd.QuickAddActivity
import com.kzhovn.todoapp.ui.FilterPanel
import com.kzhovn.todoapp.ui.OutlinerScreen
import com.kzhovn.todoapp.ui.TaskEditActivity
import com.kzhovn.todoapp.ui.TaskListMode
import com.kzhovn.todoapp.ui.TaskListScreen
import com.kzhovn.todoapp.ui.TaskListViewModel
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) (application as TodoApp).startWifiMonitor()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // The widget's "open list" button says which tab to show, whether the app is starting or
    // already open (then the intent arrives via onNewIntent).
    private val requestedMode = mutableStateOf<TaskListMode?>(null)

    private fun readRequestedMode(intent: Intent?) {
        intent?.getStringExtra(EXTRA_MODE)?.let { name -> runCatching { TaskListMode.valueOf(name) }.getOrNull() }?.let { requestedMode.value = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readRequestedMode(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readRequestedMode(intent)
        val app = application as TodoApp
        val repository = app.repository
        val contextRepository = app.contextRepository

        // The wifi monitor itself runs app-wide (TodoApp); this only asks for the access it needs.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            LedgerTheme {
            val viewModel = remember { TaskListViewModel(repository, contextRepository, app.listInputChanges(), modeFolderId = { AppSettings.modeFolderId(app) }) }
            // Folder mode: switched in the side menu, or zoomed into from the All tree.
            val setMode: (Long?) -> Unit = { id -> setFolderMode(app, id) }
            val mode by viewModel.mode.collectAsState()
            // Searching in a mode keeps to its folder, unless this is on.
            var everywhere by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }
            var selectedMode by remember { mutableStateOf(requestedMode.value ?: TaskListMode.DOING) }
            LaunchedEffect(requestedMode.value) { requestedMode.value?.let { selectedMode = it } }
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            var searchMode by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            var showFilters by remember { mutableStateOf(false) }
            var filters by remember { mutableStateOf(SearchFilters()) }
            var folders by remember { mutableStateOf<List<Task>>(emptyList()) }
            var contexts by remember { mutableStateOf<List<com.kzhovn.todoapp.data.TaskContext>>(emptyList()) }
            // Non-null while in multi-select mode: the ids picked for a bulk edit.
            var selection by remember { mutableStateOf<Set<Long>?>(null) }
            BackHandler(enabled = selection != null) { selection = null }
            val focusManager = LocalFocusManager.current
            val closeSearch = {
                searchMode = false
                query = ""
                showFilters = false
                focusManager.clearFocus()
            }
            BackHandler(enabled = searchMode && selection == null) { closeSearch() }
            // What the list shows; it reloads itself on data changes (see TaskListViewModel). Resuming
            // reloads too, since time passing (a start date arriving) changes Doing/Active without any write.
            // In search mode the filters apply even with an empty query (every task matching them).
            LifecycleResumeEffect(selectedMode, searchMode, query, filters, everywhere, mode?.id) {
                if (searchMode) viewModel.search(query, filters.takeIf { it.folderId != null || everywhere } ?: filters.copy(folderId = mode?.id))
                else viewModel.load(selectedMode)
                onPauseOrDispose { }
            }
            // Folders and contexts are made on other screens (the FAB's long-press menu), so coming
            // back re-pulls them.
            LifecycleResumeEffect(Unit) {
                scope.launch {
                    folders = repository.getFolders()
                    contexts = contextRepository.getAllContexts()
                }
                onPauseOrDispose { }
            }

            val lastDeleted by repository.lastDeleted.collectAsState()
            LaunchedEffect(lastDeleted) {
                val deleted = lastDeleted ?: return@LaunchedEffect
                val topLevelTitle = deleted.firstOrNull { it.parentId !in deleted.map { d -> d.id } }?.title
                    ?: deleted.first().title
                val extra = deleted.size - 1
                val message = "Deleted \"$topLevelTitle\"" + if (extra > 0) " and $extra more" else ""
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    repository.undoDelete(deleted)
                } else {
                    repository.clearLastDeleted()
                }
            }

            AppDrawer(drawerState) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                floatingActionButton = {
                    var showFabMenu by remember { mutableStateOf(false) }
                    Box {
                        QuickAddFab(
                            onClick = { startActivity(Intent(this@MainActivity, QuickAddActivity::class.java)) },
                            onLongClick = { showFabMenu = true }
                        )
                        DropdownMenu(expanded = showFabMenu, onDismissRequest = { showFabMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("+ Contexts") },
                                leadingIcon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
                                onClick = {
                                    showFabMenu = false
                                    startActivity(Intent(this@MainActivity, ContextsActivity::class.java))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("+ Project") },
                                leadingIcon = { Icon(Icons.Filled.AccountTree, contentDescription = null) },
                                onClick = {
                                    showFabMenu = false
                                    startActivity(
                                        Intent(this@MainActivity, TaskEditActivity::class.java)
                                            .putExtra(TaskEditActivity.EXTRA_CREATE_AS_PROJECT, true)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("+ Folder") },
                                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                onClick = {
                                    showFabMenu = false
                                    startActivity(
                                        Intent(this@MainActivity, TaskEditActivity::class.java)
                                            .putExtra(TaskEditActivity.EXTRA_CREATE_AS_FOLDER, true)
                                    )
                                }
                            )
                        }
                    }
                }
            ) {
                Column(modifier = Modifier.background(LedgerBackground)) {
                    selection?.let { selected ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                            IconButton(onClick = { selection = null }) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                            }
                            Text("${selected.size} selected", fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Button(enabled = selected.isNotEmpty(), onClick = {
                                startActivity(
                                    Intent(this@MainActivity, BulkEditActivity::class.java)
                                        .putExtra(BulkEditActivity.EXTRA_TASK_IDS, selected.toLongArray())
                                )
                                selection = null
                            }) { Text("Edit") }
                        }
                    }
                    // The search bar sits in the top bar; typing in it searches right there. While searching,
                    // the menu makes way for a back arrow and Select for the filters.
                    if (selection == null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        if (searchMode) {
                            IconButton(onClick = closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                        }
                        SearchBar(
                            query, searching = searchMode, onStart = { searchMode = true }, onChange = { query = it }, modifier = Modifier.weight(1f),
                            placeholder = mode?.takeUnless { everywhere }?.let { "Search ${it.title}" } ?: "Search tasks"
                        )
                        if (searchMode) {
                            IconButton(onClick = { showFilters = !showFilters }) {
                                Icon(
                                    Icons.Filled.FilterList,
                                    contentDescription = "Filters",
                                    tint = if (filters != SearchFilters()) LedgerAccent else LedgerMuted
                                )
                            }
                        } else {
                            IconButton(onClick = { selection = emptySet() }) {
                                Icon(Icons.Filled.Checklist, contentDescription = "Select tasks")
                            }
                        }
                    }
                    if (showFilters) {
                        FilterPanel(filters = filters, folders = folders, contexts = contexts, onFiltersChange = { filters = it })
                    }
                    if (searchMode && mode != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                        FilterChip(selected = everywhere, onClick = { everywhere = !everywhere }, label = { Text("Everywhere, not just ${mode!!.title}") })
                    }

                    if (!searchMode) TabRow(selectedTabIndex = TaskListMode.entries.indexOf(selectedMode)) {
                        TaskListMode.entries.forEach { mode ->
                            Tab(
                                selected = mode == selectedMode,
                                onClick = { selectedMode = mode },
                                text = { Text(mode.name) }
                            )
                        }
                    }
                    // In select mode every tap on a row toggles its selection instead. Only tasks and
                    // checklists can be picked: none of the bulk-editable properties apply to folders or projects.
                    val toggleSelected: (Long) -> Unit = { id ->
                        if (viewModel.allById.value[id]?.type?.isDoable == true) {
                            selection = selection?.let { if (id in it) it - id else it + id }
                        }
                    }
                    val onEdit: (Long) -> Unit = { taskId ->
                        if (selection != null) toggleSelected(taskId)
                        else startActivity(Intent(this@MainActivity, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_TASK_ID, taskId))
                    }
                    var completeDecision by remember { mutableStateOf<Pair<Long, Int>?>(null) }
                    val onCheck: (Long) -> Unit = { id ->
                        if (selection != null) toggleSelected(id)
                        else viewModel.requestComplete(id) { taskId, activeCount ->
                            completeDecision = taskId to activeCount
                        }
                    }
                    val onStar: (Long) -> Unit = { id ->
                        if (selection != null) toggleSelected(id) else viewModel.toggleStar(id)
                    }
                    val selectedIds = selection.orEmpty()
                    if (selectedMode == TaskListMode.ALL && !searchMode) {
                        val tasks by viewModel.tasks.collectAsState()
                        OutlinerScreen(
                            tasks = tasks,
                            rootId = mode?.id,
                            onZoom = setMode,
                            onCheck = onCheck,
                            onEdit = onEdit,
                            onStar = onStar,
                            selectedIds = selectedIds,
                            onReparent = { taskId, newParentId -> viewModel.reparent(taskId, newParentId) },
                            onMove = { taskId, anchorId, after -> viewModel.move(taskId, anchorId, after) },
                            onAddSubtask = { parentId ->
                                startActivity(
                                    Intent(this@MainActivity, QuickAddActivity::class.java)
                                        .putExtra(QuickAddActivity.EXTRA_PARENT_ID, parentId)
                                )
                            }
                        )
                    } else Column {
                        Box(Modifier.weight(1f, fill = false)) { TaskListScreen(
                            viewModel = viewModel,
                            onCheck = onCheck,
                            onStar = onStar,
                            onEdit = onEdit,
                            selectedIds = selectedIds,
                            onSnooze = { id, until -> viewModel.snooze(id, until) },
                            // The parent now waits on its new subtask, so in Doing a starred parent's subtask starts
                            // starred and takes its place there.
                            onAddSubtask = { parentId ->
                                val starred = selectedMode == TaskListMode.DOING && viewModel.allById.value[parentId]?.isStarred == true
                                startActivity(
                                    Intent(this@MainActivity, QuickAddActivity::class.java)
                                        .putExtra(QuickAddActivity.EXTRA_PARENT_ID, parentId)
                                        .putExtra(QuickAddActivity.EXTRA_STARRED, starred)
                                )
                            },
                            sectioned = selectedMode == TaskListMode.ACTIVE && !searchMode
                        ) }
                        // Folder mode hides the rest; this keeps it from hiding something due today.
                        val dueOutside by viewModel.dueOutside.collectAsState()
                        if (!searchMode && dueOutside.isNotEmpty()) DueOutsideLine(dueOutside, mode?.title.orEmpty(), onOpen = onEdit, onCheck = onCheck)
                    }
                    // A project whose subtasks are all done asks what's next. "Later" only snoozes it for
                    // this session; it's asked again next time the app starts.
                    val stalled by viewModel.stalledProjects.collectAsState()
                    var snoozedProjects by remember { mutableStateOf(emptySet<Long>()) }
                    stalled.firstOrNull { it.id !in snoozedProjects }?.let { project ->
                        AlertDialog(
                            onDismissRequest = { snoozedProjects = snoozedProjects + project.id },
                            title = { Text(Labels.allSubtasksDone(project.title)) },
                            text = { Text(Labels.IS_PROJECT_COMPLETE) },
                            confirmButton = {
                                Button(onClick = { viewModel.completeProject(project.id) }) { Text(Labels.COMPLETE_PROJECT) }
                            },
                            dismissButton = {
                                Row {
                                    // The usual quick add, as the next step under the project.
                                    Button(onClick = {
                                        snoozedProjects = snoozedProjects + project.id
                                        startActivity(Intent(this@MainActivity, QuickAddActivity::class.java).putExtra(QuickAddActivity.EXTRA_PARENT_ID, project.id))
                                    }) { Text(Labels.ADD_NEXT) }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = { snoozedProjects = snoozedProjects + project.id }) { Text(Labels.LATER) }
                                }
                            }
                        )
                    }
                    completeDecision?.let { (taskId, activeCount) ->
                        AlertDialog(
                            onDismissRequest = { completeDecision = null },
                            title = { Text("Complete this task?") },
                            text = { Text(Labels.activeSubtasks(activeCount)) },
                            confirmButton = {
                                Button(onClick = {
                                    viewModel.completeWithSubtasks(taskId)
                                    completeDecision = null
                                }) { Text(Labels.COMPLETE_SUBTASKS_TOO) }
                            },
                            dismissButton = {
                                Row {
                                    Button(onClick = {
                                        viewModel.completeAndPromoteSubtasks(taskId)
                                        completeDecision = null
                                    }) { Text(Labels.MOVE_SUBTASKS_OUT) }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = { completeDecision = null }) { Text("Cancel") }
                                }
                            }
                        )
                    }
                }
            }
            }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A focus session (started here or on another device) takes over the app until it's left.
        lifecycleScope.launch {
            if ((application as TodoApp).repository.getFocusSession() != null) startActivity(Intent(this@MainActivity, FocusActivity::class.java))
        }
        (application as TodoApp).startWifiMonitor()
        if (SyncSettings.config(this) != null) SyncWorker.requestSoon(this, delaySeconds = 0)
    }

    companion object {
        const val EXTRA_MODE = "mode"
    }
}

// Under Doing in a mode: "2 due today outside Work", opening to the tasks themselves.
@Composable
private fun DueOutsideLine(tasks: List<Task>, modeTitle: String, onOpen: (Long) -> Unit, onCheck: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "${tasks.size} due today outside $modeTitle" + if (open) "" else " · show", fontSize = 13.sp, color = LedgerMuted,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 6.dp)
        )
        if (open) tasks.forEach { task ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TaskCheckbox(checked = false, due = task.dueDate?.let { dueStatus(it, System.currentTimeMillis()) }, size = 18.dp, touchSize = 36.dp) { onCheck(task.id) }
                Text(task.title, fontSize = 15.sp, color = LedgerInk, modifier = Modifier.weight(1f).clickable { onOpen(task.id) }.padding(vertical = 6.dp))
            }
        }
    }
}

// Material3's FloatingActionButton doesn't expose long-press, so this re-implements its look
// with Surface + combinedClickable to add the long-press create menu.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickAddFab(onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = CircleShape,
        color = LedgerAccent,
        contentColor = LedgerAccentInk,
        shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, contentDescription = "New task")
        }
    }
}
