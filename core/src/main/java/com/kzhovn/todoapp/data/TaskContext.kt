package com.kzhovn.todoapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// LABEL: just a name to filter by ("errands", "laptop"), with no place or time; it never holds a task back.
enum class ContextType { TIME, PLACE, LABEL }

@Serializable
@Entity(tableName = "contexts")
data class TaskContext(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ContextType,
    val wifiSsid: String? = null,
    val isCurrentlySatisfied: Boolean = false
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
