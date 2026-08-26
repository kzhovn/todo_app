package com.kzhovn.todoapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Task::class, TaskDependency::class, TaskContext::class, TaskContextCrossRef::class, ContextTimeWindow::class],
    version = 2
)
@TypeConverters(Converters::class)
abstract class TodoDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskContextDao(): TaskContextDao
}
