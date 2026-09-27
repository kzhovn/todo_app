package com.kzhovn.todoapp.data

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

    const val START = "Start"
    const val DUE = "Due"
    const val REMIND = "Remind"
    const val REPEAT = "Repeat"
    const val TIMER = "Timer"
    const val TODAY_ONLY = "Today only"

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

    val TYPES = listOf(TaskType.TASK to TASK, TaskType.PROJECT to PROJECT, TaskType.FOLDER to FOLDER)

    // Minutes before the due date; null is no reminder.
    val REMINDERS: List<Pair<Int?, String>> = listOf(
        null to "No reminder", 0 to "At due time", 5 to "5 min before", 30 to "30 min before", 60 to "1 hour before", 1440 to "1 day before"
    )

    val TIMER_PRESETS = listOf(15, 30, 45, 60, 90)

    // A folder's "in order" makes its tasks one-at-a-time; a task's does the same for its subtasks.
    fun inOrder(type: TaskType) = if (type == TaskType.FOLDER) "Sequential (complete tasks in order)" else "Complete subtasks in order"

    // Monday first, like both apps' day pickers; bits are Su=0..Sa=6.
    val WEEKDAYS = listOf(1 to "Mo", 2 to "Tu", 3 to "We", 4 to "Th", 5 to "Fr", 6 to "Sa", 0 to "Su")

    // The Repeat pill's text: "Every day", "Every 2 weeks · Mo Th", "3 days after completion".
    fun repeat(selection: RecurrenceSelection): String? = when (selection.preset) {
        RecurrencePreset.NONE -> null
        RecurrencePreset.AFTER_COMPLETION_N_DAYS -> "${selection.n} day${if (selection.n == 1) "" else "s"} after completion"
        RecurrencePreset.CALENDAR -> {
            val unit = when (selection.unit) { RecurrenceUnit.DAY -> "day"; RecurrenceUnit.WEEK -> "week"; RecurrenceUnit.MONTH -> "month" }
            val every = if (selection.n == 1) "Every $unit" else "Every ${selection.n} ${unit}s"
            val days = WEEKDAYS.filter { (bit, _) -> selection.weekdaysMask and (1 shl bit) != 0 }.joinToString(" ") { it.second }
            if (selection.unit == RecurrenceUnit.WEEK && days.isNotEmpty()) "$every · $days" else every
        }
    }
}
