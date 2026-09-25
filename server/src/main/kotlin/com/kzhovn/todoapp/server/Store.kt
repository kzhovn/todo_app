package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.sync.SyncJson
import com.kzhovn.todoapp.sync.SyncRequest
import com.kzhovn.todoapp.sync.SyncResponse
import com.kzhovn.todoapp.sync.SyncRow
import com.kzhovn.todoapp.sync.diff
import com.kzhovn.todoapp.sync.merge
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

// Schema-agnostic: rows are stored as JSON field bags, so adding a column to Task needs no server
// migration. Every write bumps a global version; clients pull "everything since version N".
// ponytail: one connection behind a lock; plenty for a single user.
class Store(path: String) {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        createStatement().use {
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS rows(
                    tbl TEXT NOT NULL, id INTEGER NOT NULL, fields TEXT NOT NULL, clocks TEXT NOT NULL,
                    version INTEGER NOT NULL, PRIMARY KEY(tbl, id))"""
            )
            it.executeUpdate("CREATE INDEX IF NOT EXISTS rows_version ON rows(version)")
            it.executeUpdate("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        }
    }

    // Called after each row a client pushed is merged (not for bot writes), so the bot can mirror
    // app-side changes back into Discord.
    @Volatile
    var onSyncedChange: ((before: SyncRow?, after: SyncRow) -> Unit)? = null

    // Merged rows get fresh versions, so they come back in the response too — the client needs the
    // merged result, not just what it sent.
    @Synchronized
    fun sync(request: SyncRequest): SyncResponse {
        val changed = mutableListOf<Pair<SyncRow?, SyncRow>>()
        val response = transaction {
            request.rolloverHour?.let { setValue(ROLLOVER_HOUR_KEY, it.toString()) }
            request.changes.forEach { incoming ->
                val before = get(incoming.table, incoming.id)
                val after = merge(before, incoming)
                put(after)
                changed += before to after
            }
            SyncResponse(maxVersion(), since(request.cursor))
        }
        changed.forEach { (before, after) -> onSyncedChange?.invoke(before, after) }
        return response
    }

    // A server-side edit (from the bot): stamps whichever fields changed.
    @Synchronized
    fun write(table: String, id: Long, fields: JsonObject, now: Long) {
        val current = get(table, id)
        val merged = current?.fields?.let { JsonObject(it + fields) } ?: fields
        diff(table, id, current, merged, now)?.let { put(merge(current, it)) }
    }

    @Synchronized
    fun get(table: String, id: Long): SyncRow? =
        query("SELECT * FROM rows WHERE tbl = ? AND id = ?", table, id).firstOrNull()

    @Synchronized
    fun all(table: String): List<SyncRow> = query("SELECT * FROM rows WHERE tbl = ?", table)

    @Synchronized
    fun getValue(key: String): String? =
        conn.prepareStatement("SELECT v FROM kv WHERE k = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    @Synchronized
    fun setValue(key: String, value: String?) {
        conn.prepareStatement(if (value == null) "DELETE FROM kv WHERE k = ?" else "INSERT OR REPLACE INTO kv VALUES(?, ?)").use {
            it.setString(1, key)
            if (value != null) it.setString(2, value)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun valuesWithPrefix(prefix: String): Map<String, String> =
        conn.prepareStatement("SELECT k, v FROM kv WHERE k >= ? AND k < ?").use { st ->
            st.setString(1, prefix)
            st.setString(2, prefix + Char.MAX_VALUE)
            st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
        }

    @Synchronized
    fun <T> transaction(block: () -> T): T {
        if (!conn.autoCommit) return block() // already inside one
        conn.autoCommit = false
        try {
            return block().also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun since(cursor: Long) = query("SELECT * FROM rows WHERE version > ? ORDER BY version", cursor)

    private fun maxVersion(): Long =
        conn.createStatement().use { st -> st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM rows").use { it.next(); it.getLong(1) } }

    private fun put(row: SyncRow) {
        conn.prepareStatement("INSERT OR REPLACE INTO rows VALUES(?, ?, ?, ?, ?)").use {
            it.setString(1, row.table)
            it.setLong(2, row.id)
            it.setString(3, SyncJson.encodeToString(JsonObject.serializer(), row.fields))
            it.setString(4, SyncJson.encodeToString(clockSerializer, row.clocks))
            it.setLong(5, maxVersion() + 1)
            it.executeUpdate()
        }
    }

    private fun query(sql: String, vararg args: Any): List<SyncRow> = conn.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRow()) } }
    }

    private fun ResultSet.toRow() = SyncRow(
        table = getString("tbl"),
        id = getLong("id"),
        fields = SyncJson.decodeFromString(JsonObject.serializer(), getString("fields")),
        clocks = SyncJson.decodeFromString(clockSerializer, getString("clocks"))
    )

    companion object {
        const val ROLLOVER_HOUR_KEY = "rolloverHour"
        private val clockSerializer = MapSerializer(String.serializer(), Long.serializer())
    }
}
