package com.kzhovn.todoapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// A PROJECT is completed as a whole once its subtasks are done; it has no checkbox of its own and
// never shows in Active/Doing (its subtasks do). A WAITING item is something you're blocked on but
// don't do yourself (someone getting back to you, a date): see Waiting.kt.
enum class TaskType { TASK, FOLDER, PROJECT, CHECKLIST, WAITING }

// Something you do and tick off: a task or a checklist. Not a folder, nor a project (which completes
// by its steps). What lists, pickers and bulk edit deal in.
val TaskType.isDoable: Boolean get() = this == TaskType.TASK || this == TaskType.CHECKLIST

// What can be a prerequisite or wait on one: anything that gets done, projects included (a project is
// done when it's completed, and one waiting on something holds back its steps). Not a folder.
val TaskType.isLinkable: Boolean get() = this != TaskType.FOLDER

enum class RecurrenceType { RRULE, AFTER_COMPLETION }

const val BACKBURNER_AFTER = 30L * 24 * 60 * 60 * 1000

@Serializable
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TaskType = TaskType.TASK,
    val title: String,
    val parentId: Long? = null,
    val sequential: Boolean = false,
    val startDate: Long? = null,
    val dueDate: Long? = null,
    val isStarred: Boolean = false,
    val isComplete: Boolean = false,
    val completedAt: Long? = null,
    val recurrenceType: RecurrenceType? = null,
    val recurrenceRule: String? = null,
    val icon: String? = null,
    val reminderOffsetMinutes: Int? = null,
    // "?" in the UI: a someday-maybe, hidden from Active/Doing. Never starred (see starRule).
    @ColumnInfo(defaultValue = "0") val isMaybe: Boolean = false,
    // Set for "Today only" tasks: the day rollover after creation. Past it, the task is hidden
    // and then deleted (unlike ordinary completed tasks, which are kept forever).
    val expiresAt: Long? = null,
    // Manual order among siblings (1, 2, 3... after a reorder); null sorts by creation. See TaskOrder.
    val position: Long? = null,
    // When it became a maybe; a maybe older than BACKBURNER_AFTER is shown dimmed. See withRules.
    val maybeSince: Long? = null,
    // Folders only: the colour slot it took (a base colour at top level, else a variant within its
    // family). Stored so a folder keeps its colour when others come and go. See folderColorsArgb.
    val colorIndex: Int? = null,
    // A timed task ("1 hour of ticket work"): how long to spend, which its play button counts down.
    val durationMinutes: Int? = null,
    // Normally a task waits on its open subtasks (see computeActiveTasks); this keeps it active anyway.
    @ColumnInfo(defaultValue = "0") val activeWithSubtasks: Boolean = false,
    // When it was pinned ("what I'm doing now"), shared by every device. See pinnedTask and CurrentTask.
    val pinnedAt: Long? = null,
    // The pinned task's timer: running until timerEndsAt, or paused with timerRemaining (ms) left.
    val timerEndsAt: Long? = null,
    val timerRemaining: Long? = null,
    // A focus session on it, on every device (see CurrentTask.focusSession).
    val focusedAt: Long? = null,
    // Free text: what to ask, the number to call, a link. Plain text; blank means none.
    val notes: String? = null,
    // High priority ("!"): sorts to the top of Doing and Active, below only what's due today or overdue.
    // The other end of the scale is isMaybe; a task is one, the other, or neither (normal).
    @ColumnInfo(defaultValue = "0") val isHighPriority: Boolean = false,
    // Reminders beyond reminderOffsetMinutes (before due): one when it starts, and one at any time.
    // See reminderTimes.
    @ColumnInfo(defaultValue = "0") val remindAtStart: Boolean = false,
    val remindAt: Long? = null,
    // Waiting items: days between check-ins (null: DEFAULT_CHECK_IN_DAYS). The next check-in is startDate.
    val checkInDays: Int? = null
) {
    fun isExpired(now: Long): Boolean = expiresAt != null && expiresAt <= now

    // Snoozed or not started yet: the All tree shows it slightly dimmed. (A waiting item's start is its
    // next check-in, not a start.)
    fun startsLater(now: Long): Boolean = !isComplete && type != TaskType.WAITING && startDate != null && startDate > now

    // The one place the maybe exclusions are enforced (a maybe is never starred, nor high priority);
    // every write path runs tasks through it.
    fun starRule(): Task = if (isMaybe && (isStarred || isHighPriority)) copy(isStarred = false, isHighPriority = false) else this

    // What a type can't carry. A folder can't be done, so it has no due date, repeat, reminder or timer
    // (a due date would keep scheduling a reminder); a waiting item never shows in Doing or Active, so it
    // has no star or priority.
    fun typeRule(): Task = when (type) {
        TaskType.FOLDER -> copy(dueDate = null, recurrenceType = null, recurrenceRule = null, reminderOffsetMinutes = null, durationMinutes = null)
        TaskType.WAITING -> copy(isStarred = false, isHighPriority = false, isMaybe = false)
        else -> this
    }

    // typeRule, starRule and maybeSince bookkeeping; applied on every local write (app and server).
    fun withRules(now: Long): Task = typeRule().starRule().let {
        when {
            it.isMaybe && it.maybeSince == null -> it.copy(maybeSince = now)
            !it.isMaybe && it.maybeSince != null -> it.copy(maybeSince = null)
            else -> it
        }
    }

    // A maybe left alone for a month drifts to the back burner: still listed, but dimmed.
    fun isBackburner(now: Long): Boolean = isMaybe && maybeSince != null && now - maybeSince >= BACKBURNER_AFTER
}

// The pinned task: the open task pinned most recently. A time rather than a flag, so two devices
// pinning while offline can't both end up pinned; the newer pin wins.
fun pinnedTask(tasks: Collection<Task>): Task? = tasks.filter { it.pinnedAt != null && !it.isComplete }.maxByOrNull { it.pinnedAt!! }
