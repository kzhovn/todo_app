package com.kzhovn.todoapp.sync

import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

const val TASKS = "task"
const val CONTEXTS = "context"
const val DELETED_AT = "deletedAt"

val SyncJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

// A synced row is a bag of fields, each with the wall-clock time it was last written. Join-table
// data travels as fields of its owner (a task's contextIds/dependsOn, a context's timeWindows), so
// only two row types exist and a set is last-write-wins as a whole.
@Serializable
data class SyncRow(
    val table: String,
    val id: Long,
    val fields: JsonObject,
    val clocks: Map<String, Long> = emptyMap()
) {
    val isDeleted: Boolean get() = fields[DELETED_AT].let { it != null && it != JsonNull }
    val deletedAt: Long? get() = fields[DELETED_AT]?.takeIf { it != JsonNull }?.jsonPrimitive?.long
}

@Serializable
data class SyncRequest(val cursor: Long, val changes: List<SyncRow>)

@Serializable
data class SyncResponse(val cursor: Long, val rows: List<SyncRow>)

// Per-field last-write-wins. Ties keep `current`, so on the server, the server wins ties.
fun merge(current: SyncRow?, incoming: SyncRow): SyncRow {
    if (current == null) return incoming
    val fields = current.fields.toMutableMap()
    val clocks = current.clocks.toMutableMap()
    for ((key, value) in incoming.fields) {
        val clock = incoming.clocks[key] ?: continue
        if (clock > (clocks[key] ?: Long.MIN_VALUE)) {
            fields[key] = value
            clocks[key] = clock
        }
    }
    return current.copy(fields = JsonObject(fields), clocks = clocks)
}

// The fields of `next` that differ from `base`, stamped with `now` — or just past base's clock, so
// an edit made after seeing base beats it even when this device's clock runs behind.
// ponytail: cross-device clock skew can still misorder truly concurrent edits; HLCs if it bites.
fun diff(table: String, id: Long, base: SyncRow?, next: JsonObject, now: Long): SyncRow? {
    val changed = next.filter { (key, value) -> base?.fields?.get(key) != value }
    if (changed.isEmpty()) return null
    val clocks = changed.keys.associateWith { maxOf(now, (base?.clocks?.get(it) ?: 0L) + 1) }
    return SyncRow(table, id, JsonObject(changed), clocks)
}

fun taskFields(task: Task, contextIds: Collection<Long>, dependsOn: Collection<Long>): JsonObject =
    JsonObject(
        SyncJson.encodeToJsonElement(task).jsonObject - "id" + mapOf(
            "contextIds" to longArray(contextIds),
            "dependsOn" to longArray(dependsOn),
            DELETED_AT to JsonNull
        )
    )

fun contextFields(context: TaskContext, windows: List<ContextTimeWindow>): JsonObject =
    JsonObject(
        SyncJson.encodeToJsonElement(context).jsonObject - "id" + mapOf(
            "timeWindows" to JsonArray(
                windows.map { SyncJson.encodeToJsonElement(it.copy(id = 0, contextId = 0)) }
                    .sortedBy { it.toString() }
            ),
            DELETED_AT to JsonNull
        )
    )

fun SyncRow.toTask(): Task = SyncJson.decodeFromJsonElement<Task>(fields).copy(id = id)
fun SyncRow.contextIds(): Set<Long> = longs("contextIds")
fun SyncRow.dependsOn(): Set<Long> = longs("dependsOn")

fun SyncRow.toContext(): TaskContext = SyncJson.decodeFromJsonElement<TaskContext>(fields).copy(id = id)
fun SyncRow.timeWindows(): List<ContextTimeWindow> =
    fields["timeWindows"]?.jsonArray.orEmpty()
        .map { SyncJson.decodeFromJsonElement<ContextTimeWindow>(it).copy(id = 0, contextId = id) }

private fun SyncRow.longs(key: String): Set<Long> =
    fields[key]?.jsonArray.orEmpty().map { it.jsonPrimitive.long }.toSet()

// Sorted so an unchanged set always serializes identically and never diffs as a change.
private fun longArray(values: Collection<Long>): JsonElement = JsonArray(values.sorted().map(::JsonPrimitive))
