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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
            val viewModel = remember { TaskListViewModel(repository) }
            val snackbarHostState = remember { SnackbarHostState() }
            var selectedMode by remember { mutableStateOf(TaskListMode.DOING) }
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
            LaunchedEffect(Unit) {
                folders = repository.getFolders()
                contexts = contextRepository.getAllContexts()
            }
            LaunchedEffect(query, filters) {
                if (query.isBlank()) viewModel.load(selectedMode) else viewModel.search(query, filters)
            }

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
                    if (selectedMode == TaskListMode.ALL && query.isBlank()) {
                        val tasks by viewModel.tasks.collectAsState()
                        OutlinerScreen(
                            tasks = tasks,
                            onCheck = { viewModel.toggleComplete(it, selectedMode) },
                            onEdit = onEdit,
                            onStar = { viewModel.toggleStar(it, selectedMode) }
                        )
                    } else {
                        TaskListScreen(
                            viewModel = viewModel,
                            onCheck = { viewModel.toggleComplete(it, selectedMode) },
                            onStar = { viewModel.toggleStar(it, selectedMode) },
                            onEdit = onEdit
                        )
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
