package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.isChecklistItem
import java.util.Calendar

// One "day" of completions. Days follow the rollover hour, so a task finished at 1am (with a 4am
// rollover) counts toward the previous day, matching "Just for today".
data class DayCompletions(val dayStart: Long, val tasks: List<Task>)

// The last `days` days, newest first, including days with nothing completed.
fun completionsByDay(tasks: List<Task>, now: Long, rolloverHour: Int, days: Int): List<DayCompletions> {
    fun dayStartOf(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms
        add(Calendar.HOUR_OF_DAY, -rolloverHour)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val byId = tasks.associateBy { it.id }
    // A completed checklist counts once; its items don't count at all.
    val byDay = tasks
        .filter { it.type.isDoable && !isChecklistItem(it, byId) && it.isComplete && it.completedAt != null }
        .groupBy { dayStartOf(it.completedAt!!) }
    val today = dayStartOf(now)
    return (0 until days).map { back ->
        val day = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -back) }.timeInMillis
        DayCompletions(day, byDay[day].orEmpty().sortedBy { it.completedAt })
    }
}
