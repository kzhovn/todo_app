package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.sync.SyncJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

// An editor's save over what's stored now: each field the user changed since `base` (the task as the
// editor loaded it) takes the edit; every other keeps `current`'s, so a change made meanwhile elsewhere
// (completed from the widget, a timer started, a sync) isn't undone. Field by field, so a new Task field
// is covered without touching this; the repeat's type and rule only make sense together, so they go as one.
fun mergeEdit(base: Task, edited: Task, current: Task): Task {
    fun fields(t: Task) = SyncJson.encodeToJsonElement(t).jsonObject
    val b = fields(base)
    val e = fields(edited)
    val merged = SyncJson.decodeFromJsonElement<Task>(JsonObject(fields(current).mapValues { (key, value) -> if (e[key] != b[key]) e.getValue(key) else value }))
    val repeatEdited = edited.recurrenceType != base.recurrenceType || edited.recurrenceRule != base.recurrenceRule
    return if (repeatEdited) merged.copy(recurrenceType = edited.recurrenceType, recurrenceRule = edited.recurrenceRule) else merged
}
