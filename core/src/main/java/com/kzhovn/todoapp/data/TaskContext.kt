package com.kzhovn.todoapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// LABEL: just a name to filter by ("errands", "laptop"), with no place or time; it holds a task back only when
// switched Off by hand.
enum class ContextType { TIME, PLACE, LABEL }

@Serializable
@Entity(tableName = "contexts")
data class TaskContext(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ContextType,
    val wifiSsid: String? = null,
    val isCurrentlySatisfied: Boolean = false,
    // Set by hand (on holiday, home without wifi): overrides the wifi and the clock until set back to null.
    val forced: Boolean? = null
)

// What a context still needs before it can be saved (null: nothing), the same on every device.
fun contextProblem(context: TaskContext, windows: List<ContextTimeWindow>): String? = when {
    context.name.isBlank() -> "A name is required."
    context.type == ContextType.PLACE && context.wifiSsid.isNullOrBlank() -> "A place needs its wifi network's name."
    context.type == ContextType.TIME && windows.isEmpty() -> "A time context needs at least one window."
    else -> null
}

// "Wifi: Home", "09:00–17:00 Mon Tue" or "Filter only", as the contexts lists show it.
fun describeContext(context: TaskContext, windows: List<ContextTimeWindow>): String = when (context.type) {
    ContextType.PLACE -> "Wifi: ${context.wifiSsid}"
    ContextType.LABEL -> Labels.LABEL_CONTEXT
    ContextType.TIME -> windows.joinToString(", ") { w ->
        val days = if (w.daysMask == ContextTimeWindow.ALL_DAYS) "every day"
        else Labels.WEEKDAYS.filter { (bit, _) -> w.daysMask and (1 shl bit) != 0 }.joinToString(" ") { it.second }
        "${clockTime(w.windowStartMinute)}–${clockTime(w.windowEndMinute)} $days"
    }
}

fun deleteContextQuestion(name: String) = "\"$name\" will be removed from every task using it. This can't be undone."

// The Auto/On/Off a context offers. A filter-only one has nothing to decide for it, so On or Off (On is Auto).
fun forcedChoices(context: TaskContext): List<Pair<Boolean?, String>> =
    if (context.type == ContextType.LABEL) Labels.CONTEXT_FORCED.filter { it.first != true }.map { (v, label) -> v to if (v == null) "On" else label }
    else Labels.CONTEXT_FORCED

// Which of forcedChoices is picked: a filter-only one set On by hand is the same as Auto.
fun forcedShown(context: TaskContext): Boolean? = context.forced.takeUnless { context.type == ContextType.LABEL && it == true }

// Open tasks by context, their own or inherited: the context menus' counts.
fun openByContext(all: Collection<Task>, contextsByTaskId: Map<Long, Set<Long>>): Map<Long, Int> {
    val byId = all.associateBy { it.id }
    return all.filter { it.type.isDoable && !it.isComplete && !isChecklistItem(it, byId) }
        .flatMap { resolveEffective(it, byId, contextsByTaskId).effectiveContextIds }
        .groupingBy { it }.eachCount()
}
