package com.kzhovn.todoapp.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromTaskType(value: TaskType): String = value.name
    @TypeConverter
    fun toTaskType(value: String): TaskType = TaskType.valueOf(value)

    @TypeConverter
    fun fromRecurrenceType(value: RecurrenceType?): String? = value?.name
    @TypeConverter
    fun toRecurrenceType(value: String?): RecurrenceType? = value?.let { RecurrenceType.valueOf(it) }

    @TypeConverter
    fun fromContextType(value: ContextType): String = value.name
    @TypeConverter
    fun toContextType(value: String): ContextType = ContextType.valueOf(value)
}
