package com.kzhovn.todoapp.sync

import android.content.Context
import androidx.room.withTransaction
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContextCrossRef
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.notifications.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

data class SyncConfig(val url: String, val token: String)

class SyncClient(
    private val context: Context,
    private val db: TodoDatabase,
    private val reminders: ReminderScheduler,
    // Tests swap in the server's Store directly; production goes over HTTPS.
    private val transport: ((SyncConfig, SyncRequest) -> SyncResponse)? = null
) {
    private val mutex = Mutex()
    private val sql get() = db.openHelper.writableDatabase

    // Bumped whenever a pull changes local data, so open screens know to reload.
    private val _pulls = MutableStateFlow(0)
    val pulls: StateFlow<Int> = _pulls

    private data class Key(val table: String, val id: Long)

    private class ApplyResult(val applied: List<Task>, val removed: List<Task>, val changedRows: Int)

    // Push dirty rows, receive the merged result plus everything else new since our cursor.
    // Returns how many rows were applied locally.
    suspend fun sync(config: SyncConfig): Int = mutex.withLock {
        val (request, pushedTs) = db.withTransaction {
            val dirty = readDirty()
            SyncRequest(cursor(), dirty.mapNotNull { (key, ts) -> changeFor(key, ts) }) to dirty
        }
        val response = withContext(Dispatchers.IO) { (transport ?: ::post)(config, request) }
        val result = db.withTransaction { apply(response, pushedTs) }
        result.applied.forEach { if (it.isComplete) reminders.cancel(it) else reminders.schedule(it) }
        result.removed.forEach(reminders::cancel)
        if (result.changedRows > 0) _pulls.value++
        result.changedRows
    }

    fun hasDirty(): Boolean = sql.query("SELECT 1 FROM sync_dirty LIMIT 1").use { it.moveToFirst() }

    private suspend fun changeFor(key: Key, ts: Long): SyncRow? {
        val base = base(key)
        // A missing local row was deleted here; tell the server unless it never knew the row.
        val next = localFields(key)
            ?: base?.takeUnless { it.isDeleted }?.let { JsonObject(it.fields + (DELETED_AT to JsonPrimitive(ts))) }
            ?: return null
        return diff(key.table, key.id, base, next, ts)
    }

    private suspend fun apply(response: SyncResponse, pushedTs: Map<Key, Long>): ApplyResult {
        val applied = mutableListOf<Task>()
        val removed = mutableListOf<Task>()
        var changedRows = 0
        for (row in response.rows) {
            val key = Key(row.table, row.id)
            // Edited again while the request was in flight: leave it dirty. The next push re-sends
            // those edits and gets the merged row back, so nothing from the server is lost.
            val dirtyTs = dirtyTs(key)
            if (dirtyTs != null && dirtyTs != pushedTs[key]) continue
            val local = localFields(key)
            // Usually the echo of our own push: record it as the new base without rewriting the row.
            val unchanged = if (row.isDeleted) local == null else local == row.fields
            if (!unchanged) {
                when (row.table) {
                    TASKS -> applyTask(row)?.let { (task, deleted) -> if (deleted) removed += task else applied += task }
                    CONTEXTS -> applyContext(row)
                    else -> continue // a row type from a newer server; ignore
                }
                changedRows++
            }
            sql.execSQL("INSERT OR REPLACE INTO sync_base VALUES(?, ?, ?)", arrayOf(row.table, row.id, SyncJson.encodeToString(SyncRow.serializer(), row)))
            clearDirty(key) // our own writes above re-dirtied it
        }
        // Pushed rows with nothing to send (e.g. created and deleted offline) never come back.
        val returned = response.rows.map { Key(it.table, it.id) }.toSet()
        pushedTs.filterKeys { it !in returned }.forEach { (key, ts) -> if (dirtyTs(key) == ts) clearDirty(key) }
        sql.execSQL("INSERT OR REPLACE INTO sync_state VALUES('cursor', ?)", arrayOf(response.cursor))
        return ApplyResult(applied, removed, changedRows)
    }

    private suspend fun applyTask(row: SyncRow): Pair<Task, Boolean>? {
        val taskDao = db.taskDao()
        val contextDao = db.taskContextDao()
        if (row.isDeleted) {
            val existing = taskDao.getById(row.id) ?: return null
            taskDao.deleteById(row.id)
            taskDao.deleteDependenciesOf(row.id)
            contextDao.deleteAssignmentsForTask(row.id)
            return existing to true
        }
        val task = row.toTask()
        taskDao.upsert(task)
        taskDao.deleteDependenciesOf(task.id)
        row.dependsOn().forEach { taskDao.insertDependency(TaskDependency(task.id, it)) }
        contextDao.deleteAssignmentsForTask(task.id)
        row.contextIds().forEach { contextDao.assignContext(TaskContextCrossRef(task.id, it)) }
        return task to false
    }

    private suspend fun applyContext(row: SyncRow) {
        val contextDao = db.taskContextDao()
        contextDao.deleteTimeWindowsForContext(row.id)
        if (row.isDeleted) {
            contextDao.deleteContext(row.id)
            return
        }
        contextDao.upsert(row.toContext())
        row.timeWindows().forEach { contextDao.insertTimeWindow(it) }
    }

    private suspend fun localFields(key: Key): JsonObject? = when (key.table) {
        TASKS -> db.taskDao().getById(key.id)?.let {
            taskFields(it, db.taskContextDao().getContextIdsForTask(key.id), db.taskDao().getDependencyIds(key.id))
        }
        CONTEXTS -> db.taskContextDao().getById(key.id)?.let {
            contextFields(it, db.taskContextDao().getTimeWindows(key.id))
        }
        else -> null
    }

    private fun readDirty(): Map<Key, Long> =
        sql.query("SELECT tbl, id, ts FROM sync_dirty").use { c ->
            buildMap { while (c.moveToNext()) put(Key(c.getString(0), c.getLong(1)), c.getLong(2)) }
        }

    private fun dirtyTs(key: Key): Long? =
        sql.query("SELECT ts FROM sync_dirty WHERE tbl = ? AND id = ?", arrayOf(key.table, key.id))
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun clearDirty(key: Key) =
        sql.execSQL("DELETE FROM sync_dirty WHERE tbl = ? AND id = ?", arrayOf(key.table, key.id))

    private fun base(key: Key): SyncRow? =
        sql.query("SELECT row FROM sync_base WHERE tbl = ? AND id = ?", arrayOf(key.table, key.id)).use {
            if (it.moveToFirst()) SyncJson.decodeFromString(SyncRow.serializer(), it.getString(0)) else null
        }

    private fun cursor(): Long =
        sql.query("SELECT v FROM sync_state WHERE k = 'cursor'").use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun post(config: SyncConfig, request: SyncRequest): SyncResponse {
        val conn = URL(config.url.trimEnd('/') + "/sync").openConnection() as HttpURLConnection
        try {
            if (conn is HttpsURLConnection) pinnedSocketFactory?.let { conn.sslSocketFactory = it }
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer ${config.token}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(SyncJson.encodeToString(SyncRequest.serializer(), request).toByteArray()) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { SyncJson.decodeFromString(SyncResponse.serializer(), it.readBytes().decodeToString()) }
        } finally {
            conn.disconnect()
        }
    }

    // The server uses a self-signed certificate bundled as an asset. It's trusted for this
    // connection only, not app-wide. Without the asset, normal public-CA validation applies.
    private val pinnedSocketFactory: SSLSocketFactory? by lazy {
        val pem = runCatching { context.assets.open("server_cert.pem") }.getOrNull() ?: return@lazy null
        val cert = pem.use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("server", cert)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }
}
