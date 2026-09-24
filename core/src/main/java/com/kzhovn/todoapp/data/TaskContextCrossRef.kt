package com.kzhovn.todoapp.data

import androidx.room.Entity

@Entity(tableName = "task_contexts", primaryKeys = ["taskId", "contextId"])
data class TaskContextCrossRef(val taskId: Long, val contextId: Long)
