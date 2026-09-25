package com.kzhovn.todoapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// A PROJECT is completed as a whole once its subtasks are done; it has no checkbox of its own and
// never shows in Active/Doing (its subtasks do).
enum class TaskType { TASK, FOLDER, PROJECT }
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
    // Set for "just for today" tasks: the day rollover after creation. Past it, the task is hidden
    // and then deleted (unlike ordinary completed tasks, which are kept forever).
    val expiresAt: Long? = null,
    // Manual order among siblings (1, 2, 3... after a reorder); null sorts by creation. See TaskOrder.
    val position: Long? = null,
    // When it became a maybe; a maybe older than BACKBURNER_AFTER is shown dimmed. See withRules.
    val maybeSince: Long? = null
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
