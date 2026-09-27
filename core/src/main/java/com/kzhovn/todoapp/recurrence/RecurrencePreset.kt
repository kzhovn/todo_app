package com.kzhovn.todoapp.recurrence

import com.kzhovn.todoapp.data.RecurrenceType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// AFTER_COMPLETION_N_DAYS keeps its name (it's in saved web forms); its unit can now be weeks or months too.
enum class RecurrencePreset { NONE, CALENDAR, AFTER_COMPLETION_N_DAYS }

enum class RecurrenceUnit { DAY, WEEK, MONTH }

data class RecurrenceSelection(
    val preset: RecurrencePreset,
    val n: Int = 1,
    val unit: RecurrenceUnit = RecurrenceUnit.DAY,
    val weekdaysMask: Int = 0,
    // Monthly: on the nth weekday (1..4, or -1 for the last; weekday Su=0..Sa=6). Null: on the
    // start date's day of the month.
    val monthlyNth: Int? = null,
    val monthlyWeekday: Int = 6,
    // Ends (schedules only): after this day (local midnight), or after this many more instances,
    // counting the current one.
    val until: Long? = null,
    val count: Int? = null
)

// Su=0 .. Sa=6, matching the daysMask convention already used by ContextTimeWindow/DayOfWeekToggle.
private val WEEKDAY_CODES = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
const val WEEKDAYS_MASK = 0b0111110 // Mo-Fr
private val UNTIL_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

private fun weekdaysMaskToByDay(mask: Int): String =
    (0..6).filter { mask and (1 shl it) != 0 }.joinToString(",") { WEEKDAY_CODES[it] }

private fun byDayToWeekdaysMask(byDay: String): Int =
    byDay.split(",").fold(0) { acc, code ->
        val idx = WEEKDAY_CODES.indexOf(code)
        if (idx >= 0) acc or (1 shl idx) else acc
    }

// UNTIL is the end of that local day, in UTC, as RFC 5545 wants for a date-time start.
private fun untilToRule(day: Long): String {
    val endOfDay = Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate().atTime(23, 59, 59).atZone(ZoneId.systemDefault())
    return endOfDay.withZoneSameInstant(ZoneOffset.UTC).format(UNTIL_FORMAT)
}

private fun untilFromRule(value: String): Long? = runCatching {
    java.time.LocalDateTime.parse(value, UNTIL_FORMAT).atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault())
        .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

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
        if (unit == RecurrenceUnit.WEEK && weekdaysMask != 0) parts += "BYDAY=${weekdaysMaskToByDay(weekdaysMask)}"
        if (unit == RecurrenceUnit.MONTH && monthlyNth != null) parts += "BYDAY=$monthlyNth${WEEKDAY_CODES[monthlyWeekday]}"
        until?.let { parts += "UNTIL=${untilToRule(it)}" }
        count?.let { parts += "COUNT=$it" }
        RecurrenceType.RRULE to parts.joinToString(";")
    }
    // "3" days (the original, bare form), "2w" weeks, "1m" months.
    RecurrencePreset.AFTER_COMPLETION_N_DAYS -> RecurrenceType.AFTER_COMPLETION to when (unit) {
        RecurrenceUnit.DAY -> "$n"
        RecurrenceUnit.WEEK -> "${n}w"
        RecurrenceUnit.MONTH -> "${n}m"
    }
}

// Unrecognized FREQ values fall back to NONE for display purposes rather than crashing — this is
// a preset picker, not a general RRULE parser (RecurrenceEngine itself handles arbitrary rules).
fun recurrenceSelectionFromTask(recurrenceType: RecurrenceType?, recurrenceRule: String?): RecurrenceSelection {
    if (recurrenceType == null || recurrenceRule == null) return RecurrenceSelection(RecurrencePreset.NONE)
    return when (recurrenceType) {
        RecurrenceType.AFTER_COMPLETION -> {
            val unit = when (recurrenceRule.lastOrNull()) { 'w' -> RecurrenceUnit.WEEK; 'm' -> RecurrenceUnit.MONTH; else -> RecurrenceUnit.DAY }
            RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = recurrenceRule.trimEnd('w', 'm').toIntOrNull() ?: 1, unit = unit)
        }
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
            val byDay = parts["BYDAY"]
            // Monthly "1SA" / "-1FR": the nth weekday.
            val nth = if (unit == RecurrenceUnit.MONTH) byDay?.let { Regex("^(-?\\d)([A-Z]{2})$").find(it) } else null
            RecurrenceSelection(
                RecurrencePreset.CALENDAR,
                n = parts["INTERVAL"]?.toIntOrNull() ?: 1,
                unit = unit,
                weekdaysMask = if (unit == RecurrenceUnit.WEEK) byDay?.let(::byDayToWeekdaysMask) ?: 0 else 0,
                monthlyNth = nth?.groupValues?.get(1)?.toInt(),
                monthlyWeekday = nth?.let { WEEKDAY_CODES.indexOf(it.groupValues[2]).coerceAtLeast(0) } ?: 6,
                until = parts["UNTIL"]?.let(::untilFromRule),
                count = parts["COUNT"]?.toIntOrNull()
            )
        }
    }
}

// The one-tap choices at the top of the Repeat sheet, worded from the task's date ("on Sun",
// "on the 27th"). Ends aren't part of these.
fun recurrencePresets(anchor: Long): List<Pair<String, RecurrenceSelection>> {
    val date = Instant.ofEpochMilli(anchor).atZone(ZoneId.systemDefault()).toLocalDate()
    val weekday = date.dayOfWeek.value % 7 // Su=0
    return listOf(
        "Every day" to RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.DAY),
        "Every weekday" to RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.WEEK, weekdaysMask = WEEKDAYS_MASK),
        "Every week on ${WEEKDAY_NAMES[weekday]}" to RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.WEEK, weekdaysMask = 1 shl weekday),
        "Every month on the ${ordinal(date.dayOfMonth)}" to RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.MONTH)
    )
}

val WEEKDAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
val NTH_NAMES = listOf(1 to "first", 2 to "second", 3 to "third", 4 to "fourth", -1 to "last")

fun ordinal(n: Int): String = "$n" + if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

internal fun LocalDate.toEpochMillis(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
