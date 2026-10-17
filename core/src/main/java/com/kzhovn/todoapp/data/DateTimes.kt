package com.kzhovn.todoapp.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

const val DAY_MS = 24L * 60 * 60 * 1000

// Every date and time is read in the default timezone.
fun zoned(epochMillis: Long): ZonedDateTime = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
fun LocalDate.atTimeMillis(time: LocalTime): Long = atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

// Start/due times are optional. A date without a time is stored as local midnight, so "has a time"
// means "isn't midnight".
// ponytail: a time of exactly 00:00 reads as date-only; add a flag column if that ever matters.
fun hasTime(epochMillis: Long): Boolean = zoned(epochMillis).toLocalTime().toSecondOfDay() != 0

fun atTime(epochMillis: Long, hour: Int, minute: Int): Long = localDay(epochMillis).atTimeMillis(LocalTime.of(hour, minute))

// That day's local midnight.
fun startOfDay(epochMillis: Long): Long = dayStart(localDay(epochMillis))

// When a due date actually passes: its time if it has one, otherwise the end of that day, so
// "due today" isn't overdue from the moment the day starts.
fun deadline(dueDate: Long): Long = if (hasTime(dueDate)) dueDate else zoned(dueDate).plusDays(1).toInstant().toEpochMilli()

// A minute of the day as "09:30" (time-context windows).
fun clockTime(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
