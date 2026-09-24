package com.kzhovn.todoapp.sync

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

// Epoch millis in SQLite (julianday works on every Android SQLite version, unlike unixepoch()).
private const val NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

// (table, synced row type, column holding that row's id). Join tables dirty their owner, since
// assignments travel as fields of the task/context row.
private val TRACKED = listOf(
    Triple("tasks", TASKS, "id"),
    Triple("task_contexts", TASKS, "taskId"),
    Triple("task_dependencies", TASKS, "taskId"),
    Triple("contexts", CONTEXTS, "id"),
    Triple("context_time_windows", CONTEXTS, "contextId")
)
val TRACKED_TABLES: Array<String> = TRACKED.map { it.first }.distinct().toTypedArray()

// Change tracking lives in SQLite triggers, so every write path (repositories, widget, wifi
// monitor, future code) is captured without each remembering to mark rows dirty. The sync tables
// sit outside Room's schema on purpose: no DB version bump, so no destructive-migration risk.
object SyncTracking : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_dirty(tbl TEXT NOT NULL, id INTEGER NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY(tbl, id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_base(tbl TEXT NOT NULL, id INTEGER NOT NULL, row TEXT NOT NULL, PRIMARY KEY(tbl, id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_state(k TEXT PRIMARY KEY, v INTEGER NOT NULL)")
        for ((table, rowType, idColumn) in TRACKED) {
            for (event in listOf("INSERT", "UPDATE", "DELETE")) {
                val ref = if (event == "DELETE") "OLD" else "NEW"
                db.execSQL("DROP TRIGGER IF EXISTS sync_${table}_$event")
                // Delete-then-insert rather than INSERT OR REPLACE: an outer statement's conflict
                // clause (Room inserts use OR ABORT) overrides one inside a trigger.
                db.execSQL(
                    "CREATE TRIGGER sync_${table}_$event AFTER $event ON $table BEGIN " +
                        "DELETE FROM sync_dirty WHERE tbl = '$rowType' AND id = $ref.$idColumn; " +
                        "INSERT INTO sync_dirty VALUES('$rowType', $ref.$idColumn, $NOW_MS); END"
                )
            }
        }
        // Rows that predate tracking have never been pushed; mark them once so the first sync uploads them.
        db.query("SELECT 1 FROM sync_state WHERE k = 'seeded'").use { if (it.moveToFirst()) return }
        db.execSQL("INSERT OR IGNORE INTO sync_dirty SELECT '$TASKS', id, $NOW_MS FROM tasks")
        db.execSQL("INSERT OR IGNORE INTO sync_dirty SELECT '$CONTEXTS', id, $NOW_MS FROM contexts")
        db.execSQL("INSERT INTO sync_state VALUES('seeded', 1)")
    }
}
