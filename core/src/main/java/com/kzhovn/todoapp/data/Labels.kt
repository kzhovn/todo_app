package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.recurrence.WEEKDAY_NAMES
import com.kzhovn.todoapp.recurrence.NTH_NAMES
import com.kzhovn.todoapp.recurrence.WEEKDAYS_MASK
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.RecurrenceUnit

// The editor's user-facing names, shared by the phone app and the web app so they always match.
object Labels {
    const val TITLE = "Title"
    const val STAR = "Star"
    const val MAYBE = "Maybe"
    const val PIN = "Pin to notification"

    const val TASK = "Task"
    const val PROJECT = "Project"
    const val FOLDER = "Folder"
    const val CHECKLIST = "Checklist"

    const val START = "Start"
    const val DUE = "Due"
    const val REMIND = "Remind"
    const val REPEAT = "Repeat"
    const val TIMER = "Timer"
    const val TODAY_ONLY = "Today only"
    const val AFTER_COMPLETION = "After completion"

    const val FOLDER_AND_CONTEXTS = "Folder and contexts"
    const val NO_FOLDER = "No folder"
    const val CONTEXT = "Context"
    const val MANAGE_CONTEXTS = "Manage contexts…"

    const val RELATED_TASKS = "Related tasks"
    const val SUBTASK = "Subtask"
    const val PREREQUISITE = "Prerequisite"
    const val DEPENDENT = "Dependent"
    const val ADD_SUBTASK = "+ Subtask"
    const val ADD_PREREQUISITE = "+ Prerequisite"
    const val ADD_DEPENDENT = "+ Dependent task"

    const val SAVE = "Save"
    const val DELETE = "Delete"

    // Lists and screens
    const val DOING = "Doing"
    const val ACTIVE = "Active"
    const val ALL = "All"

    // A row's ⋯ menu: "Snooze" over three tiles
    const val SNOOZE = "Snooze"
    const val SNOOZE_HOUR = "1 hour"
    const val SNOOZE_TOMORROW = "Tomorrow"
    const val SNOOZE_WEEK = "1 week"

    // Completing a task that still has open subtasks
    const val COMPLETE_SUBTASKS_TOO = "Complete subtasks too"
    const val MOVE_SUBTASKS_OUT = "Move subtasks out"
    fun activeSubtasks(count: Int) = "It has $count active subtask${if (count == 1) "" else "s"}."

    // A project whose subtasks are all done
    fun allSubtasksDone(project: String) = "“$project”: all subtasks done"
    const val IS_PROJECT_COMPLETE = "Is the project complete?"
    const val COMPLETE_PROJECT = "Complete project"
    const val ADD_NEXT = "Add next"
    const val LATER = "Later"

    val TYPES = listOf(TaskType.TASK to TASK, TaskType.PROJECT to PROJECT, TaskType.CHECKLIST to CHECKLIST, TaskType.FOLDER to FOLDER)

    // A checklist's items
    const val ITEMS = "Items"
    const val ADD_ITEM = "Add item"
    const val CLEAR_CHECKED = "Clear checked"
    const val UNCHECK_ALL = "Uncheck all"
    const val COMPLETE_LIST = "Complete list"
    const val MOVE_TO_NEW_LIST = "Move them to a new list"
    const val COMPLETE_THEM_TOO = "Complete them too"
    fun uncheckedItems(count: Int) = "$count item${if (count == 1) " isn't" else "s aren't"} checked."
    fun clearChecked(count: Int) = "Delete $count checked item${if (count == 1) "" else "s"}?"

    // Minutes before the due date; null is no reminder.
    val REMINDERS: List<Pair<Int?, String>> = listOf(
        null to "No reminder", 0 to "At due time", 5 to "5 min before", 30 to "30 min before", 60 to "1 hour before", 1440 to "1 day before"
    )

    val TIMER_PRESETS = listOf(15, 30, 45, 60, 90)

    // A folder's "in order" makes its tasks one-at-a-time; a task's does the same for its subtasks.
    fun inOrder(type: TaskType) = if (type == TaskType.FOLDER) "Sequential (complete tasks in order)" else "Complete subtasks in order"

    // Monday first, like both apps' day pickers; bits are Su=0..Sa=6.
    val WEEKDAYS = listOf(1 to "Mo", 2 to "Tu", 3 to "We", 4 to "Th", 5 to "Fr", 6 to "Sa", 0 to "Su")

    // The Repeat pill's text: "Every weekday", "Every 2 weeks · Mo Th", "Every month · first Sat",
    // "3 days after completion", with any end: "· until Dec 31", "· 10 times".
    fun repeat(selection: RecurrenceSelection): String? = when (selection.preset) {
        RecurrencePreset.NONE -> null
        RecurrencePreset.AFTER_COMPLETION_N_DAYS -> "${plural(selection.n, unitName(selection.unit))} after completion"
        RecurrencePreset.CALENDAR -> {
            val unit = unitName(selection.unit)
            val every = if (selection.n == 1) "Every $unit" else "Every ${selection.n} ${unit}s"
            val days = WEEKDAYS.filter { (bit, _) -> selection.weekdaysMask and (1 shl bit) != 0 }.joinToString(" ") { it.second }
            val rule = when {
                selection.unit == RecurrenceUnit.WEEK && selection.n == 1 && selection.weekdaysMask == WEEKDAYS_MASK -> "Every weekday"
                selection.unit == RecurrenceUnit.WEEK && days.isNotEmpty() -> "$every · $days"
                selection.unit == RecurrenceUnit.MONTH && selection.monthlyNth != null ->
                    "$every · ${NTH_NAMES.first { it.first == selection.monthlyNth }.second} ${WEEKDAY_NAMES[selection.monthlyWeekday]}"
                else -> every
            }
            val ends = selection.until?.let { " · until " + java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(it)) }
                ?: selection.count?.let { " · ${plural(it, "time")}" }.orEmpty()
            rule + ends
        }
    }

    private fun unitName(unit: RecurrenceUnit) = when (unit) { RecurrenceUnit.DAY -> "day"; RecurrenceUnit.WEEK -> "week"; RecurrenceUnit.MONTH -> "month" }
    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
}
