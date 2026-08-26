package com.kzhovn.todoapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ContextType { TIME, PLACE }

@Entity(tableName = "contexts")
data class TaskContext(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ContextType,
    val wifiSsid: String? = null,
    val isCurrentlySatisfied: Boolean = false
)
