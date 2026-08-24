package com.kzhovn.todoapp.data

import androidx.room.Entity

@Entity(tableName = "task_dependencies", primaryKeys = ["taskId", "dependsOnTaskId"])
data class TaskDependency(val taskId: Long, val dependsOnTaskId: Long)
