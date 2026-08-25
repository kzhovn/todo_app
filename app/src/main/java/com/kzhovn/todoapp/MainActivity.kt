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
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.context.WifiContextMonitor
import com.kzhovn.todoapp.ui.TaskEditActivity
import com.kzhovn.todoapp.ui.TaskListMode
import com.kzhovn.todoapp.ui.TaskListScreen
import com.kzhovn.todoapp.ui.TaskListViewModel
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
            val viewModel = remember { TaskListViewModel(repository) }
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            var selectedMode by remember { mutableStateOf(TaskListMode.DOING) }
            LaunchedEffect(selectedMode) { viewModel.load(selectedMode) }

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                floatingActionButton = {
                    FloatingActionButton(onClick = { startActivity(Intent(this@MainActivity, TaskEditActivity::class.java)) }) {
                        Icon(Icons.Filled.Add, contentDescription = "New task")
                    }
                }
            ) {
                Column {
                    TabRow(selectedTabIndex = TaskListMode.entries.indexOf(selectedMode)) {
                        TaskListMode.entries.forEach { mode ->
                            Tab(
                                selected = mode == selectedMode,
                                onClick = { selectedMode = mode },
                                text = { Text(mode.name) }
                            )
                        }
                    }
                    TaskListScreen(
                        viewModel = viewModel,
                        onCheck = { viewModel.markComplete(it, selectedMode) },
                        onStar = { viewModel.toggleStar(it, selectedMode) },
                        onDelete = { taskId ->
                            viewModel.deleteWithUndo(taskId, selectedMode) { deletedTask ->
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar("Task deleted", actionLabel = "Undo")
                                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                        viewModel.undoDelete(deletedTask, selectedMode)
                                    }
                                }
                            }
                        },
                        onEdit = { taskId -> startActivity(Intent(this@MainActivity, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_TASK_ID, taskId)) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wifiMonitorStarted) wifiContextMonitor.stop()
    }
}
