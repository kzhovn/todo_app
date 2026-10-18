package com.kzhovn.todoapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Task::class, TaskDependency::class, TaskContext::class, TaskContextCrossRef::class, ContextTimeWindow::class],
    version = 20
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

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE tasks ADD COLUMN position INTEGER")
}

// Existing maybes start their one-month backburner clock at upgrade time.
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN maybeSince INTEGER")
        db.execSQL("UPDATE tasks SET maybeSince = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) WHERE isMaybe = 1")
    }
}

// Folders get their colour slots on the next list load (TaskRepository.listedTasks).
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE tasks ADD COLUMN colorIndex INTEGER")
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE tasks ADD COLUMN durationMinutes INTEGER")
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE tasks ADD COLUMN activeWithSubtasks INTEGER NOT NULL DEFAULT 0")
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE tasks ADD COLUMN pinnedAt INTEGER")
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN timerEndsAt INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN timerRemaining INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN focusedAt INTEGER")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE tasks ADD COLUMN notes TEXT")
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE tasks ADD COLUMN isHighPriority INTEGER NOT NULL DEFAULT 0")
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN remindAtStart INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN remindAt INTEGER")
    }
}

// Waiting items' check-in interval (the WAITING type itself is just a new value in the type column).
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE tasks ADD COLUMN checkInDays INTEGER")
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN isTemplate INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN fromTemplateId INTEGER")
    }
}

// Steps that don't hold up the next one (Task.alongsideNext).
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE tasks ADD COLUMN alongsideNext INTEGER NOT NULL DEFAULT 0")
}

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE contexts ADD COLUMN forced INTEGER")
}

// Google Calendar: an Event item's event (the EVENT type is just a new value), and Events contexts and "off while".
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN eventId TEXT")
        db.execSQL("ALTER TABLE tasks ADD COLUMN eventCancelled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE contexts ADD COLUMN calendarIds TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE contexts ADD COLUMN titleWords TEXT")
        db.execSQL("ALTER TABLE contexts ADD COLUMN busyOnly INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE contexts ADD COLUMN offWhile TEXT NOT NULL DEFAULT ''")
    }
}

val ALL_MIGRATIONS = arrayOf(
    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
    MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20
)
