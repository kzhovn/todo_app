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

    // A context's calendars (Google's ids have no newline) and its "off while" contexts; "" is none.
    @TypeConverter
    fun fromStrings(value: List<String>): String = value.joinToString("\n")
    @TypeConverter
    fun toStrings(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("\n")
    @TypeConverter
    fun fromLongs(value: List<Long>): String = value.joinToString(",")
    @TypeConverter
    fun toLongs(value: String): List<Long> = if (value.isEmpty()) emptyList() else value.split(",").map { it.toLong() }

    @TypeConverter
    fun fromContextType(value: ContextType): String = value.name
    @TypeConverter
    fun toContextType(value: String): ContextType = ContextType.valueOf(value)
}
