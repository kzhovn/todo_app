package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.recurrence.WEEKDAY_NAMES
import com.kzhovn.todoapp.recurrence.NTH_NAMES
import com.kzhovn.todoapp.recurrence.WEEKDAYS_MASK
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.RecurrenceUnit

// The editor's user-facing names, shared by the phone app and the web app so they always match.
object Labels {
    // Review (the phone's and the web's)
    const val REVIEW = "Review"
    const val SEE_ALL_FOLDERS = "See all folders"
    fun justFolder(title: String) = "Just $title"
    const val BACK_TO_TODAY = "Back to today"
    const val TIME_TO_DONE = "Time to done"
    fun timeToDoneCaption(count: Int) = "from start or creation · $count tasks, repeats left out"
    const val MEDIAN_BY_FOLDER = "Median by folder"
    const val TOOK_LONGEST = "Took longest"
    const val WAITING_NOW = "Waiting now"
    const val WAITING_NOW_CAPTION = "Active tasks, plus those only held back by a context, by how long they've waited"
    const val WAITED_LONGEST = "Waited longest"
    const val DUE_DATES = "Due dates"
    const val LATEST = "Latest"
    fun doneIn(window: String) = "Done $window"
    const val BY_MONTH = "By month"
    fun nothingDoneIn(window: String) = "Nothing completed in $window."
    fun late(ms: Long) = "${com.kzhovn.todoapp.repository.shortAge(ms, "under a day")} late"

    // Focus mode
    const val FOCUS_ON = "Focus on"
    const val FOCUS_NEXT_OR_FINISH = "Focus on the next task, or finish?"
    const val IM_DONE = "I'm done"
    const val LEAVE_FOCUS = "Leave focus"

    // Contexts
    const val NEW_CONTEXT = "New context"
    const val PLACE_CONTEXT = "Place (wifi)"
    const val TIME_CONTEXT = "Time window"

    // Repeat
    const val DONT_REPEAT = "Don't repeat"
    const val ON_A_SCHEDULE = "On a schedule"
    const val AFTER_COMPLETION_SUFFIX = "after completion"

    const val HAS_NOTES = "Has notes"
    const val NOTHING_HERE = "Nothing here"
    const val QUICK_ADD = "Quick add"
    const val START_TIMER = "Start timer"
    const val TIMES_UP = "Time's up"
    const val DIGEST_CAPTION = "Doing, posted each morning. Off, the bot still nudges about tasks stuck in Doing."

    // Bulk edit (the phone's and the web's): every choice starts at Keep.
    fun editTasks(count: Int) = "Edit $count task${if (count == 1) "" else "s"}"
    fun applyToTasks(count: Int) = "Apply to $count task${if (count == 1) "" else "s"}"
    const val KEEP = "Keep"
    const val UNSTAR = "Unstar"
    const val MAYBE_CHOICE = "Maybe (?)"
    const val NOT_MAYBE = "Not maybe"
    const val START_DATE = "Start date"
    const val DUE_DATE = "Due date"
    const val CLEAR = "Clear"
    const val TOP_LEVEL = "Top level"
    const val ADD_CONTEXTS = "Add contexts"
    const val REMOVE_CONTEXTS = "Remove contexts"
    const val DEPENDS_ON = "Depends on"
    const val NOTHING_NEW = "Nothing new"

    // Saving a change subtasks override: the editor's question (the phone's dialog, the web's banner).
    const val UPDATE_SUBTASKS_TOO = "Update subtasks too?"
    fun subtasksOverride(count: Int, fields: String) = if (count == 1) "1 subtask has its own $fields. Clear it so it follows this task?"
        else "$count subtasks have their own $fields. Clear them so they follow this task?"
    const val UPDATE_SUBTASKS = "Update subtasks"
    const val ONLY_THIS_TASK = "Only this task"

    const val TITLE = "Title"
    const val STAR = "Star"
    const val MAYBE = "Maybe"
    const val PIN = "Pin"
    const val UNPIN = "Unpin"

    const val TASK = "Task"
    const val PROJECT = "Project"
    const val FOLDER = "Folder"
    const val CHECKLIST = "Checklist"
    const val WAITING = "Waiting"
    // A waiting item's section in the editor, and its actions.
    const val CHECK_IN = "Check in"
    const val RESOLVES_ON = "Resolves on"
    const val STILL_WAITING = "Still waiting"
    const val RESOLVED = "Resolved"
    const val FOLLOW_UP = "Follow up"
    fun followUp(title: String) = "Ask about: $title"

    const val START = "Start"
    const val DUE = "Due"
    const val REMIND = "Remind"
    const val REPEAT = "Repeat"
    const val TIMER = "Timer"
    const val TODAY_ONLY = "Today only"
    const val AFTER_COMPLETION = "After completion"

    // The Repeat builder's preview: "Next: Sat Oct 3 · Sat Nov 7", or for after completion, when the
    // next one would be if finished today.
    fun repeatPreviewLabel(afterCompletion: Boolean) = if (afterCompletion) "If done today" else "Next"
    fun repeatPreview(dates: List<Long>): String =
        dates.joinToString(" · ") { java.text.SimpleDateFormat("EEE MMM d", java.util.Locale.US).format(java.util.Date(it)) }.ifEmpty { "No more" }

    const val TIMING = "Timing"
    const val PROPERTIES = "Properties"
    const val NO_FOLDER = "No folder"
    const val CONTEXT = "Context"
    const val MANAGE_CONTEXTS = "Manage contexts…"

    const val RELATED = "Related"
    const val SUBTASK = "Subtask"
    const val PREREQUISITE = "Prerequisite"
    const val DEPENDENT = "Dependent"
    // A subtask's ✕ menu.
    fun moveOutTo(folder: String?) = if (folder == null) "Move out (no folder)" else "Move out to $folder"
    const val NOT_PREREQUISITE = "Not a prerequisite"
    const val NOT_DEPENDENT = "Not a dependent"
    const val MOVE_OUT_AND_UNLINK = "Both"
    const val DELETE_TASK = "Delete task…"
    const val ADD_SUBTASK = "+ Subtask"
    const val ADD_PREREQUISITE = "+ Prerequisite"
    const val ADD_DEPENDENT = "+ Dependent task"

    // Search filters
    const val STARRED = "Starred"
    const val COMPLETED = "Completed"
    const val DUE_AFTER = "Due after"
    const val DUE_BEFORE = "Due before"

    // Quick add's cheat sheet (the phone's drawer, the web's sidebar)
    val QUICK_ADD_SYNTAX = listOf(
        "-d fri · due 3pm" to "due date (and time)",
        "-s tomorrow · start mon 9am" to "start date",
        "today, mon, next fri, +3d, in 2 weeks, +2h, oct 12, next week, weekend, 2026-10-01" to "dates",
        "5pm, 9:30am, 14:00" to "times",
        "work: …" to "into a folder or project (else the mode's folder, or Personal)",
        "groceries: milk, eggs" to "items into a checklist",
        "packing [passport, charger]" to "a new checklist",
        "d: …" to "today only",
        "@home" to "a context",
        "every day, every mon, thu, every 2 weeks, every 1st sat" to "repeat on a schedule",
        "every 4 days after done" to "repeat after completion",
        "1 hour of …, ~30m" to "timed task",
        "ends with * · ? · !" to "starred · maybe · high priority",
        "wait …" to "a waiting item (blocks, but isn't yours to do)",
        "-p · -f" to "pin it · focus on it",
        "remind start · remind 30m before" to "a reminder when it starts · before it's due",
        "remind fri 5pm · remind 30m" to "a reminder at a time · in 30 minutes",
        "call bank // ask about fees" to "everything after // is the note",
    )

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
    const val SKIP = "Skip this time"
    const val PRIORITY = "Priority"
    // The priority picker, high to low: (high, maybe) flags and the label.
    val PRIORITIES = listOf(Triple(true, false, "High !"), Triple(false, false, "Normal"), Triple(false, true, "Maybe ?"))
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

    val TYPES = listOf(TaskType.TASK to TASK, TaskType.PROJECT to PROJECT, TaskType.CHECKLIST to CHECKLIST, TaskType.FOLDER to FOLDER, TaskType.WAITING to WAITING)

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

    // Subtask options: a folder's or task's children done one at a time; a task that stays active
    // while its subtasks are open.
    const val SEQUENTIAL = "Sequential"
    const val ACTIVE_WITH_SUBTASKS = "Active with subtasks"

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

    fun unitName(unit: RecurrenceUnit) = when (unit) { RecurrenceUnit.DAY -> "day"; RecurrenceUnit.WEEK -> "week"; RecurrenceUnit.MONTH -> "month" }
    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
}
