package com.kzhovn.todoapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TaskType { TASK, FOLDER }
enum class RecurrenceType { RRULE, AFTER_COMPLETION }

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
    val icon: String? = null
)
