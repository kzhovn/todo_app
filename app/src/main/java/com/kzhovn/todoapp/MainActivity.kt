package com.kzhovn.todoapp

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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.context.WifiContextMonitor
import com.kzhovn.todoapp.contexts.ContextsActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            var selectedMode by remember { mutableStateOf(TaskListMode.DOING) }
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            var searchMode by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            var showFilters by remember { mutableStateOf(false) }
            var filters by remember { mutableStateOf(SearchFilters()) }
            var folders by remember { mutableStateOf<List<Task>>(emptyList()) }
            var contexts by remember { mutableStateOf<List<com.kzhovn.todoapp.data.TaskContext>>(emptyList()) }
            // Reloads on tab change AND on every resume, so returning from QuickAddActivity
            // (FAB) or TaskEditActivity (row tap) picks up whatever was just created or edited.
            LifecycleResumeEffect(selectedMode) {
                viewModel.load(selectedMode)
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
            LaunchedEffect(query, filters) {
                if (query.isBlank()) viewModel.load(selectedMode) else viewModel.search(query, filters)
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
                    if (query.isBlank()) viewModel.load(selectedMode) else viewModel.search(query, filters)
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
                            label = { Text("Contexts") },
                            icon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                startActivity(Intent(this@MainActivity, ContextsActivity::class.java))
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                        if (searchMode) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("Search") },
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
                        }
                    }
                    if (showFilters) {
                        FilterPanel(filters = filters, folders = folders, contexts = contexts, onFiltersChange = { filters = it })
                    }
                    TabRow(selectedTabIndex = TaskListMode.entries.indexOf(selectedMode)) {
                        TaskListMode.entries.forEach { mode ->
                            Tab(
                                selected = mode == selectedMode,
                                onClick = { selectedMode = mode },
                                text = { Text(mode.name) }
                            )
                        }
                    }
                    val onEdit: (Long) -> Unit = { taskId ->
                        startActivity(Intent(this@MainActivity, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_TASK_ID, taskId))
                    }
                    var completeDecision by remember { mutableStateOf<Pair<Long, Int>?>(null) }
                    var addSubtaskParentId by remember { mutableStateOf<Long?>(null) }
                    var newSubtaskTitle by remember { mutableStateOf("") }
                    val onCheck: (Long) -> Unit = { id ->
                        viewModel.requestComplete(id, selectedMode) { taskId, activeCount ->
                            completeDecision = taskId to activeCount
                        }
                    }
                    if (selectedMode == TaskListMode.ALL && query.isBlank()) {
                        val tasks by viewModel.tasks.collectAsState()
                        OutlinerScreen(
                            tasks = tasks,
                            onCheck = onCheck,
                            onEdit = onEdit,
                            onStar = { viewModel.toggleStar(it, selectedMode) },
                            onReparent = { taskId, newParentId -> viewModel.reparent(taskId, newParentId, selectedMode) },
                            onAddSubtask = { parentId -> addSubtaskParentId = parentId }
                        )
                    } else {
                        TaskListScreen(
                            viewModel = viewModel,
                            onCheck = onCheck,
                            onStar = { viewModel.toggleStar(it, selectedMode) },
                            onEdit = onEdit,
                            onSnooze = { id, duration -> viewModel.snooze(id, duration, selectedMode) }
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
                    addSubtaskParentId?.let { parentId ->
                        AlertDialog(
                            onDismissRequest = { addSubtaskParentId = null; newSubtaskTitle = "" },
                            title = { Text("Add subtask") },
                            text = {
                                OutlinedTextField(
                                    value = newSubtaskTitle,
                                    onValueChange = { newSubtaskTitle = it },
                                    placeholder = { Text("Subtask name") }
                                )
                            },
                            confirmButton = {
                                Button(
                                    enabled = newSubtaskTitle.isNotBlank(),
                                    onClick = {
                                        scope.launch {
                                            repository.createTask(Task(title = newSubtaskTitle, parentId = parentId))
                                            newSubtaskTitle = ""
                                            addSubtaskParentId = null
                                            viewModel.load(selectedMode)
                                        }
                                    }
                                ) { Text("Add") }
                            },
                            dismissButton = { Button(onClick = { addSubtaskParentId = null; newSubtaskTitle = "" }) { Text("Cancel") } }
                        )
                    }
                }
            }
            }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wifiMonitorStarted) wifiContextMonitor.stop()
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
