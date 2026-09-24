package com.kzhovn.todoapp.recurrence

import com.kzhovn.todoapp.data.RecurrenceType

enum class RecurrencePreset { NONE, CALENDAR, AFTER_COMPLETION_N_DAYS }

enum class RecurrenceUnit { DAY, WEEK, MONTH }

data class RecurrenceSelection(
    val preset: RecurrencePreset,
    val n: Int = 1,
    val unit: RecurrenceUnit = RecurrenceUnit.DAY,
    val weekdaysMask: Int = 0
)

// Su=0 .. Sa=6, matching the daysMask convention already used by ContextTimeWindow/DayOfWeekToggle.
private val WEEKDAY_CODES = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")

private fun weekdaysMaskToByDay(mask: Int): String =
    (0..6).filter { mask and (1 shl it) != 0 }.joinToString(",") { WEEKDAY_CODES[it] }

private fun byDayToWeekdaysMask(byDay: String): Int =
    byDay.split(",").fold(0) { acc, code ->
        val idx = WEEKDAY_CODES.indexOf(code)
        if (idx >= 0) acc or (1 shl idx) else acc
    }

fun RecurrenceSelection.toTaskFields(): Pair<RecurrenceType?, String?> = when (preset) {
    RecurrencePreset.NONE -> null to null
    RecurrencePreset.CALENDAR -> {
        val freq = when (unit) {
            RecurrenceUnit.DAY -> "DAILY"
            RecurrenceUnit.WEEK -> "WEEKLY"
            RecurrenceUnit.MONTH -> "MONTHLY"
        }
        val parts = mutableListOf("FREQ=$freq")
        if (n > 1) parts += "INTERVAL=$n"
        if (unit == RecurrenceUnit.WEEK && weekdaysMask != 0) {
            parts += "BYDAY=${weekdaysMaskToByDay(weekdaysMask)}"
        }
        RecurrenceType.RRULE to parts.joinToString(";")
    }
    RecurrencePreset.AFTER_COMPLETION_N_DAYS -> RecurrenceType.AFTER_COMPLETION to n.toString()
}

// Unrecognized FREQ values fall back to NONE for display purposes rather than crashing — this is
// a preset picker, not a general RRULE parser (RecurrenceEngine itself handles arbitrary rules).
fun recurrenceSelectionFromTask(recurrenceType: RecurrenceType?, recurrenceRule: String?): RecurrenceSelection {
    if (recurrenceType == null || recurrenceRule == null) return RecurrenceSelection(RecurrencePreset.NONE)
    return when (recurrenceType) {
        RecurrenceType.AFTER_COMPLETION ->
            RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = recurrenceRule.toIntOrNull() ?: 1)
        RecurrenceType.RRULE -> {
            val parts = recurrenceRule.split(";").mapNotNull {
                val eq = it.indexOf('=')
                if (eq < 0) null else it.substring(0, eq) to it.substring(eq + 1)
            }.toMap()
            val unit = when (parts["FREQ"]) {
                "DAILY" -> RecurrenceUnit.DAY
                "WEEKLY" -> RecurrenceUnit.WEEK
                "MONTHLY" -> RecurrenceUnit.MONTH
                else -> return RecurrenceSelection(RecurrencePreset.NONE)
            }
            val n = parts["INTERVAL"]?.toIntOrNull() ?: 1
            val weekdaysMask = parts["BYDAY"]?.let(::byDayToWeekdaysMask) ?: 0
            RecurrenceSelection(RecurrencePreset.CALENDAR, n = n, unit = unit, weekdaysMask = weekdaysMask)
        }
    }
}
