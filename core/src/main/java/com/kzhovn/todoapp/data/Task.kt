package com.kzhovn.todoapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class TaskType { TASK, FOLDER }
enum class RecurrenceType { RRULE, AFTER_COMPLETION }

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
    val expiresAt: Long? = null
) {
    fun isExpired(now: Long): Boolean = expiresAt != null && expiresAt <= now

    // The one place the maybe/star exclusion is enforced; every write path runs tasks through it.
    fun starRule(): Task = if (isMaybe && isStarred) copy(isStarred = false) else this
}
