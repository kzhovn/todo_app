package com.kzhovn.todoapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Task::class, TaskDependency::class, TaskContext::class, TaskContextCrossRef::class, ContextTimeWindow::class],
    version = 5
)
@TypeConverters(Converters::class)
abstract class TodoDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskContextDao(): TaskContextDao
}

// Real migrations only from here on: a destructive fallback would drop local tasks while the sync
// cursor (stored outside Room's tables) survives, so they'd never be pulled back.
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) =
        db.execSQL("ALTER TABLE tasks ADD COLUMN isMaybe INTEGER NOT NULL DEFAULT 0")
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE tasks ADD COLUMN expiresAt INTEGER")
}
