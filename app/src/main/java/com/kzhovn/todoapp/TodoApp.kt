package com.kzhovn.todoapp

import android.app.AlarmManager
import android.app.Application
import androidx.room.InvalidationTracker
import androidx.room.Room
import com.kzhovn.todoapp.data.MIGRATION_3_4
import com.kzhovn.todoapp.data.MIGRATION_4_5
import com.kzhovn.todoapp.data.TodoDatabase
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
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            .addCallback(SyncTracking)
            .build()
    }
    val reminderScheduler: ReminderScheduler by lazy {
        ReminderScheduler(this, getSystemService(ALARM_SERVICE) as AlarmManager)
    }
    val repository: TaskRepository by lazy {
        TaskRepository(database.taskDao(), reminderScheduler, database.taskContextDao())
    }
    val contextRepository: ContextRepository by lazy { ContextRepository(database.taskContextDao()) }
    val syncClient: SyncClient by lazy { SyncClient(this, database, reminderScheduler) }

    override fun onCreate() {
        super.onCreate()
        if (SyncSettings.config(this) != null) SyncWorker.ensurePeriodic(this)
        // Push shortly after any local edit, from any screen or the widget. Pull-applies clear
        // their own dirty marks, so they don't re-trigger this.
        database.invalidationTracker.addObserver(object : InvalidationTracker.Observer(TRACKED_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                if (SyncSettings.config(this@TodoApp) != null && syncClient.hasDirty()) SyncWorker.requestSoon(this@TodoApp)
            }
        })
    }
}
