package com.kzhovn.todoapp

import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import androidx.glance.appwidget.updateAll
import com.kzhovn.todoapp.widget.TodoWidget
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import com.kzhovn.todoapp.context.WifiContextMonitor
import androidx.core.content.ContextCompat
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.content.pm.PackageManager
import android.Manifest
import android.app.AlarmManager
import android.app.Application
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.withTransaction
import com.kzhovn.todoapp.data.ALL_MIGRATIONS
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.notifications.PinnedTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.kzhovn.todoapp.notifications.ReminderScheduler
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.sync.SyncClient
import com.kzhovn.todoapp.sync.SyncSettings
import com.kzhovn.todoapp.sync.SyncTracking
import com.kzhovn.todoapp.sync.SyncWorker
import com.kzhovn.todoapp.sync.TRACKED_TABLES

class TodoApp : Application() {
    val database: TodoDatabase by lazy {
        Room.databaseBuilder(this, TodoDatabase::class.java, "todo.db")
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(SyncTracking)
            .build()
    }
    val reminderScheduler: ReminderScheduler by lazy {
        ReminderScheduler(this, getSystemService(ALARM_SERVICE) as AlarmManager)
    }
    val repository: TaskRepository by lazy {
        TaskRepository(database.taskDao(), reminderScheduler, database.taskContextDao(), rolloverHour = { AppSettings.rolloverHour(this) }, transaction = ::inTransaction)
    }
    val contextRepository: ContextRepository by lazy { ContextRepository(database.taskContextDao(), ::inTransaction) }
    private suspend fun inTransaction(block: suspend () -> Unit) = database.withTransaction { block() }
    val syncClient: SyncClient by lazy { SyncClient(this, database, reminderScheduler) }
    private val wifiMonitor by lazy {
        WifiContextMonitor(getSystemService(ConnectivityManager::class.java), getSystemService(WifiManager::class.java), database.taskContextDao(), CoroutineScope(Dispatchers.IO))
    }

    // Place contexts need the wifi's name, so location access; MainActivity and ContextsActivity ask
    // for it. Also re-checks the wifi now, so opening the app (when the name is always readable) fixes a stale state.
    fun startWifiMonitor() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) wifiMonitor.start()
    }

    // Fires on any change to what decides a list: tasks, and also contexts (a place turning on at
    // home), their time windows, and dependencies, which don't touch the tasks table.
    // Folder mode switching (here or synced from elsewhere) changes every list too.
    fun listInputChanges(): Flow<Unit> = merge(
        callbackFlow {
            val observer = object : InvalidationTracker.Observer(TRACKED_TABLES) {
                override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
            }
            database.invalidationTracker.addObserver(observer)
            awaitClose { database.invalidationTracker.removeObserver(observer) }
        },
        AppSettings.modeChanges.drop(1).map { }
    ).conflate()

    override fun onCreate() {
        super.onCreate()
        if (SyncSettings.config(this) != null) SyncWorker.ensurePeriodic(this)
        // The "Now" notification and the timer's alarm, from the current task (they don't survive a restart).
        CoroutineScope(Dispatchers.IO).launch { PinnedTask.refresh(this@TodoApp) }
        startWifiMonitor()
        // Every widget redraws when tasks, contexts or dependencies change, from anywhere (the app, a
        // sync, another widget). A widget only watches the data while its own session is alive, so
        // an idle one would otherwise keep showing the old list. The pinned notification follows too:
        // the pin may have moved (another device), or its task changed or finished. Debounced, since a
        // sync writes many rows in a burst.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            listInputChanges().debounce(300).collect {
                TodoWidget().updateAll(this@TodoApp)
                PinnedTask.refresh(this@TodoApp)
            }
        }
        // Push shortly after any local edit, from any screen or the widget. Pull-applies clear
        // their own dirty marks, so they don't re-trigger this.
        database.invalidationTracker.addObserver(object : InvalidationTracker.Observer(TRACKED_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                if (SyncSettings.config(this@TodoApp) != null && syncClient.hasDirty()) SyncWorker.requestSoon(this@TodoApp)
            }
        })
    }
}
