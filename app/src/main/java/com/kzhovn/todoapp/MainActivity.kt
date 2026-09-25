package com.kzhovn.todoapp

import com.kzhovn.todoapp.ui.theme.LedgerInk
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.kzhovn.todoapp.ui.TextInputDialog
import androidx.compose.material.icons.filled.AccountTree
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
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
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.context.WifiContextMonitor
import com.kzhovn.todoapp.contexts.ContextsActivity
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.ui.BulkEditActivity
import com.kzhovn.todoapp.ui.ReviewActivity
import androidx.compose.material.icons.filled.BarChart
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Checklist
import com.kzhovn.todoapp.sync.SyncSettings
import com.kzhovn.todoapp.sync.SyncSettingsActivity
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
import com.kzhovn.todoapp.widget.TodoWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var wifiContextMonitor: WifiContextMonitor
    private var wifiMonitorStarted = false

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                wifiContextMonitor.start()
                wifiMonitorStarted = true
            }
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

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val wifiManager = getSystemService(WifiManager::class.java)
        wifiContextMonitor = WifiContextMonitor(
            connectivityManager, wifiManager, app.database.taskContextDao(), lifecycleScope
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            wifiContextMonitor.start()
            wifiMonitorStarted = true
        } else {
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
            val viewModel = remember { TaskListViewModel(repository, contextRepository) }
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
            // Reloads on tab change AND on every resume, so returning from QuickAddActivity
            // (FAB) or TaskEditActivity (row tap) picks up whatever was just created or edited.
            LifecycleResumeEffect(selectedMode, searchMode, query, filters) {
                if (searchMode) viewModel.search(query, filters) else viewModel.load(selectedMode)
                onPauseOrDispose { }
            }
            // FAB long-press can create a folder/context without leaving this activity, so
            // resume (not just initial composition) must re-pull to pick up the new one.
            LifecycleResumeEffect(Unit) {
                scope.launch {
                    folders = repository.getFolders()
                    contexts = contextRepository.getAllContexts()
                }
                onPauseOrDispose { }
            }
            val pulls by app.syncClient.pulls.collectAsState()
            // In search mode the filters apply even with an empty query (then it's "every task matching
            // the filters"); the tabs only drive the list outside search.
            LaunchedEffect(searchMode, query, filters, pulls) {
                if (searchMode) viewModel.search(query, filters) else viewModel.load(selectedMode)
            }
            // Independent subscription (rather than reusing the ALL-branch collectAsState below)
            // so the widget refreshes no matter which tab is active. Every mutation method routes
            // through load()/search(), which update viewModel.tasks, so watching it here catches
            // star/complete/snooze/reparent centrally instead of patching each call site.
            val widgetRefreshTasks by viewModel.tasks.collectAsState()
            LaunchedEffect(widgetRefreshTasks) {
                TodoWidget().updateAll(applicationContext)
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
                    TodoWidget().updateAll(applicationContext)
                    if (searchMode) viewModel.search(query, filters) else viewModel.load(selectedMode)
                } else {
                    repository.clearLastDeleted()
                }
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        NavigationDrawerItem(
                            label = { Text("Search") },
                            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            selected = searchMode,
                            onClick = {
                                searchMode = true
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        NavigationDrawerItem(
                            label = { Text("Review") },
                            icon = { Icon(Icons.Filled.BarChart, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                startActivity(Intent(this@MainActivity, ReviewActivity::class.java))
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        NavigationDrawerItem(
                            label = { Text("Settings") },
                            icon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                startActivity(Intent(this@MainActivity, SyncSettingsActivity::class.java))
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        NavigationDrawerItem(
                            label = { Text("Contexts") },
                            icon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                startActivity(Intent(this@MainActivity, ContextsActivity::class.java))
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        QuickAddKey()
                    }
                }
            ) {
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
                    if (selection == null) Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                        if (searchMode) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showFilters = !showFilters }) {
                                Icon(
                                    Icons.Filled.FilterList,
                                    contentDescription = "Filters",
                                    tint = if (filters != SearchFilters()) LedgerAccent else LedgerMuted
                                )
                            }
                            IconButton(onClick = {
                                searchMode = false
                                query = ""
                                showFilters = false
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close search")
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { selection = emptySet() }) {
                                Icon(Icons.Filled.Checklist, contentDescription = "Select tasks")
                            }
                        }
                    }
                    if (showFilters) {
                        FilterPanel(filters = filters, folders = folders, contexts = contexts, onFiltersChange = { filters = it })
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
                    // In select mode every tap on a row toggles its selection instead. Folders are
                    // skipped: none of the bulk-editable properties apply to them.
                    val toggleSelected: (Long) -> Unit = { id ->
                        if (viewModel.allById.value[id]?.type == TaskType.TASK) {
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
                        else viewModel.requestComplete(id, selectedMode) { taskId, activeCount ->
                            completeDecision = taskId to activeCount
                        }
                    }
                    val onStar: (Long) -> Unit = { id ->
                        if (selection != null) toggleSelected(id) else viewModel.toggleStar(id, selectedMode)
                    }
                    val selectedIds = selection.orEmpty()
                    if (selectedMode == TaskListMode.ALL && !searchMode) {
                        val tasks by viewModel.tasks.collectAsState()
                        OutlinerScreen(
                            tasks = tasks,
                            onCheck = onCheck,
                            onEdit = onEdit,
                            onStar = onStar,
                            selectedIds = selectedIds,
                            onReparent = { taskId, newParentId -> viewModel.reparent(taskId, newParentId, selectedMode) },
                            onMove = { taskId, anchorId, after -> viewModel.move(taskId, anchorId, after, selectedMode) },
                            onAddSubtask = { parentId ->
                                startActivity(
                                    Intent(this@MainActivity, QuickAddActivity::class.java)
                                        .putExtra(QuickAddActivity.EXTRA_PARENT_ID, parentId)
                                )
                            }
                        )
                    } else {
                        TaskListScreen(
                            viewModel = viewModel,
                            onCheck = onCheck,
                            onStar = onStar,
                            onEdit = onEdit,
                            selectedIds = selectedIds,
                            onSnooze = { id, until -> viewModel.snooze(id, until, selectedMode) }
                        )
                    }
                    // A project whose subtasks are all done asks what's next. "Later" only snoozes it for
                    // this session; it's asked again next time the app starts.
                    val stalled by viewModel.stalledProjects.collectAsState()
                    var snoozedProjects by remember { mutableStateOf(emptySet<Long>()) }
                    var nextStepFor by remember { mutableStateOf<Task?>(null) }
                    var nextStepTitle by remember { mutableStateOf("") }
                    stalled.firstOrNull { it.id !in snoozedProjects }?.takeIf { nextStepFor == null }?.let { project ->
                        AlertDialog(
                            onDismissRequest = { snoozedProjects = snoozedProjects + project.id },
                            title = { Text("“${project.title}”: all subtasks done") },
                            text = { Text("Is the project complete?") },
                            confirmButton = {
                                Button(onClick = { viewModel.completeProject(project.id, selectedMode) }) { Text("Complete project") }
                            },
                            dismissButton = {
                                Row {
                                    Button(onClick = { nextStepFor = project; nextStepTitle = "" }) { Text("Add next") }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = { snoozedProjects = snoozedProjects + project.id }) { Text("Later") }
                                }
                            }
                        )
                    }
                    nextStepFor?.let { project ->
                        TextInputDialog(
                            title = "Next step for “${project.title}”",
                            placeholder = "Subtask",
                            confirmLabel = "Add",
                            value = nextStepTitle,
                            onValueChange = { nextStepTitle = it },
                            onConfirm = {
                                viewModel.addSubtask(project.id, nextStepTitle.trim(), selectedMode)
                                nextStepFor = null
                            },
                            onDismiss = { nextStepFor = null }
                        )
                    }
                    completeDecision?.let { (taskId, activeCount) ->
                        AlertDialog(
                            onDismissRequest = { completeDecision = null },
                            title = { Text("Complete this task?") },
                            text = { Text("It has $activeCount active subtask${if (activeCount == 1) "" else "s"}.") },
                            confirmButton = {
                                Button(onClick = {
                                    viewModel.completeWithSubtasks(taskId, selectedMode)
                                    completeDecision = null
                                }) { Text("Complete subtasks too") }
                            },
                            dismissButton = {
                                Row {
                                    Button(onClick = {
                                        viewModel.completeAndPromoteSubtasks(taskId, selectedMode)
                                        completeDecision = null
                                    }) { Text("Move subtasks out") }
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
        if (SyncSettings.config(this) != null) SyncWorker.requestSoon(this, delaySeconds = 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wifiMonitorStarted) wifiContextMonitor.stop()
    }

    companion object {
        const val EXTRA_MODE = "mode"
    }
}

// Material3's FloatingActionButton doesn't expose long-press, so this re-implements its look
// with Surface + combinedClickable to add the "+Contexts/+Folder" menu trigger.
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

// Cheat sheet for quick-add syntax, at the bottom of the drawer.
@Composable
private fun QuickAddKey() {
    val rows = listOf(
        "-d fri · due 3pm" to "due date (and time)",
        "-s tomorrow · start mon 9am" to "start date",
        "today, tomorrow, mon–sun, next fri, 2026-10-01" to "dates",
        "5pm, 9:30am, 14:00" to "times",
        "ends with ?" to "maybe",
    )
    val discord = listOf(
        "--work: …" to "into a folder (else Personal)",
        "--d: …" to "just for today",
        "reply to a todo" to "it waits for the new one",
        "✅ ❌ ⭐" to "complete / delete / star",
    )
    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text("Quick add", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerMuted)
        rows.forEach { (syntax, meaning) -> KeyRow(syntax, meaning) }
        Spacer(Modifier.height(8.dp))
        Text("Discord (.help for more)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LedgerMuted)
        discord.forEach { (syntax, meaning) -> KeyRow(syntax, meaning) }
    }
}

@Composable
private fun KeyRow(syntax: String, meaning: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = LedgerInk)) { append(syntax) }
            append("  $meaning")
        },
        fontSize = 11.sp, color = LedgerMuted, modifier = Modifier.padding(top = 2.dp)
    )
}
