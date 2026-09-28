package com.kzhovn.todoapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// A PROJECT is completed as a whole once its subtasks are done; it has no checkbox of its own and
// never shows in Active/Doing (its subtasks do).
enum class TaskType { TASK, FOLDER, PROJECT, CHECKLIST }

// Something you do and tick off: a task or a checklist. Not a folder, nor a project (which completes
// by its steps). What lists, pickers, dependencies and bulk edit deal in.
val TaskType.isDoable: Boolean get() = this == TaskType.TASK || this == TaskType.CHECKLIST
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
    val notes: String? = null
) {
    fun isExpired(now: Long): Boolean = expiresAt != null && expiresAt <= now

    // The one place the maybe/star exclusion is enforced; every write path runs tasks through it.
    fun starRule(): Task = if (isMaybe && isStarred) copy(isStarred = false) else this

    // starRule plus maybeSince bookkeeping; applied on every local write (app and server).
    fun withRules(now: Long): Task = starRule().let {
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
