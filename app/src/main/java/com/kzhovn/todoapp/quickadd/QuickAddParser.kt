package com.kzhovn.todoapp.quickadd

import com.kzhovn.todoapp.data.Task
import java.util.Calendar

object QuickAddParser {

    private val startFlagRegex = Regex("-s\\s+(\\S+)")
    private val dueFlagRegex = Regex("-d\\s+(\\S+)")

    fun parse(input: String): Task {
        val startDate = startFlagRegex.find(input)?.groupValues?.get(1)?.let(::parseDateKeyword)
        val dueDate = dueFlagRegex.find(input)?.groupValues?.get(1)?.let(::parseDateKeyword)
        val title = input
            .replace(startFlagRegex, "")
            .replace(dueFlagRegex, "")
            .trim()
        return Task(title = title, startDate = startDate, dueDate = dueDate)
    }

    private fun parseDateKeyword(value: String): Long? {
        val cal = Calendar.getInstance()
        return when (value.lowercase()) {
            "today" -> cal.startOfDay()
            "tomorrow" -> cal.apply { add(Calendar.DAY_OF_YEAR, 1) }.startOfDay()
            else -> runCatching {
                val (year, month, day) = value.split("-").map { it.toInt() }
                Calendar.getInstance().apply { set(year, month - 1, day) }.startOfDay()
            }.getOrNull()
        }
    }

    private fun Calendar.startOfDay(): Long = apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
