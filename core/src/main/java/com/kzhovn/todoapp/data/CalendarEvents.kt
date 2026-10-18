package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.repository.TaskChanges
import kotlinx.serialization.Serializable

// Google Calendar (docs/superpowers/specs/2026-10-18-google-calendar-design.md). The server reads every calendar
// the account sees and hands the events on in sync; nothing else talks to Google. An event a task is linked to
// becomes an EVENT task (an "Event item"), so it's a prerequisite or dependent like any task: a task waiting on it
// comes up when it ends, one it waits on is due when it starts.

@Serializable
data class CalendarInfo(val id: String, val name: String, val color: String? = null)

// `id` is the calendar's id and Google's event id, so one meeting on two calendars stays two events. An all-day
// one runs from its first day's start to the next day after its last. `busy`: Google's Busy (not "Free").
@Serializable
data class CalendarEvent(
    val id: String, val calendarId: String, val title: String, val start: Long, val end: Long,
    val allDay: Boolean = false, val busy: Boolean = true, val location: String? = null, val link: String? = null
) {
    fun isOn(now: Long) = now in start until end
}

// What every device knows of the calendars: the events from `from` to `to`, and when they last changed
// (`version`), so the phone is sent them again only then.
@Serializable
data class CalendarData(
    val calendars: List<CalendarInfo> = emptyList(), val events: List<CalendarEvent> = emptyList(),
    val version: Long = 0, val from: Long = 0, val to: Long = 0
) {
    // The calendars shown (Settings → Calendars), and their events.
    fun shown(hidden: Set<String>): List<CalendarEvent> = events.filter { it.calendarId !in hidden }
    fun calendar(id: String): CalendarInfo? = calendars.firstOrNull { it.id == id }
}

// An Event item's end: its start (dueDate) and length.
fun Task.eventEnd(): Long? = if (type == TaskType.EVENT) dueDate?.let { it + (durationMinutes ?: 0) * 60_000L } else null

fun Task.asEvent(event: CalendarEvent): Task =
    copy(type = TaskType.EVENT, title = event.title, dueDate = event.start, durationMinutes = ((event.end - event.start) / 60_000).toInt(), eventId = event.id)

// Links `taskId` to `event`: before it (the event waits on the task, so the task's due when it starts) or after it
// (the task waits on the event). The event's item is made the first time.
fun planLinkEvent(all: List<Task>, taskId: Long, event: CalendarEvent, before: Boolean, now: Long): TaskChanges {
    val existing = all.firstOrNull { it.type == TaskType.EVENT && it.eventId == event.id }
    val item = existing ?: Task(id = newId(), title = event.title).asEvent(event)
    val link = if (before) TaskDependency(item.id, taskId) else TaskDependency(taskId, item.id)
    return TaskChanges(creates = if (existing == null) listOf(item to emptySet()) else emptyList(), links = listOf(link))
}

// The server, after reading the calendars: Event items follow their events. One gone from inside the window read
// was cancelled: it resolves, so what waited on it is let go, and what it waited on keeps the due date it lent.
fun planFollowEvents(all: List<Task>, edges: List<TaskDependency>, data: CalendarData, now: Long): TaskChanges {
    val byEventId = data.events.associateBy { it.id }
    val byId = all.associateBy { it.id }
    val updates = mutableListOf<Task>()
    all.filter { it.type == TaskType.EVENT && !it.eventCancelled && it.eventId != null }.forEach { item ->
        val event = byEventId[item.eventId]
        if (event != null) {
            item.asEvent(event).takeIf { it != item }?.let(updates::add)
        } else if (item.dueDate != null && item.dueDate in data.from until data.to) {
            updates += item.copy(eventCancelled = true, isComplete = true, completedAt = now)
            edges.filter { it.taskId == item.id }.mapNotNull { byId[it.dependsOnTaskId] }
                .filter { !it.isComplete && it.dueDate == null }
                .forEach { updates += it.copy(dueDate = item.dueDate) }
        }
    }
    return TaskChanges(updates = updates)
}

// Cancelled events still to be dealt with (Doing's Cancelled line), and the tasks linked to one.
fun cancelledEvents(all: Collection<Task>): List<Task> = all.filter { it.type == TaskType.EVENT && it.eventCancelled }.sortedBy { it.dueDate }

fun linkedToEvent(itemId: Long, all: Collection<Task>, edges: List<TaskDependency>): EventLinks {
    val byId = all.associateBy { it.id }
    return EventLinks(
        before = edges.filter { it.taskId == itemId }.mapNotNull { byId[it.dependsOnTaskId] }.sortedWith(TaskOrder),
        after = edges.filter { it.dependsOnTaskId == itemId }.mapNotNull { byId[it.taskId] }.sortedWith(TaskOrder)
    )
}

data class EventLinks(val before: List<Task>, val after: List<Task>) {
    val all: List<Task> get() = before + after
}

// "Link to another event…" on a cancelled one: the same links, to `event`; the old item goes.
fun planRelinkEvent(all: List<Task>, edges: List<TaskDependency>, itemId: Long, event: CalendarEvent, now: Long): TaskChanges {
    val links = linkedToEvent(itemId, all, edges)
    var plan = TaskChanges(deletes = setOf(itemId))
    var current = all.filter { it.id != itemId }
    (links.before.map { it to true } + links.after.map { it to false }).forEach { (task, before) ->
        val step = planLinkEvent(current, task.id, event, before, now)
        current = step.applyTo(current)
        plan += step
    }
    return plan
}

// The events an Events context goes by: on its calendars, busy ones only unless set otherwise, and with one of its
// words in the title when it has any ("oncall for, pager": either).
fun matchingEvents(context: TaskContext, events: List<CalendarEvent>): List<CalendarEvent> {
    val words = context.titleWords.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return events.filter { e ->
        e.calendarId in context.calendarIds && (!context.busyOnly || e.busy) && (words.isEmpty() || words.any { e.title.contains(it, ignoreCase = true) })
    }
}

// The events to snooze until after ("After…"): on now or still to come, today and tomorrow, soonest first.
fun snoozeEvents(events: List<CalendarEvent>, now: Long): List<CalendarEvent> {
    val until = dayStart(localDay(now).plusDays(2))
    return events.filter { !it.allDay && it.end > now && it.start < until }.sortedBy { it.start }
}

// Doing's Now/Next line: what's on now, else what's next today (at most two).
fun nowAndNext(events: List<CalendarEvent>, now: Long): List<CalendarEvent> {
    val tomorrow = dayStart(localDay(now).plusDays(1))
    return events.filter { !it.allDay && it.end > now && it.start < tomorrow }.sortedBy { it.start }.take(2)
}

// "14:00–15:00" (each in the device's own clock format), or "All day".
fun eventTimes(e: CalendarEvent): String = if (e.allDay) Labels.ALL_DAY else "${timeText(e.start)}–${timeText(e.end)}"

// "Today · Oct 20, 14:00–15:00 · in 40m": when an event is, for its sheet.
fun eventWhen(e: CalendarEvent, now: Long): String {
    val day = Labels.agendaHeading(localDay(e.start), localDay(now)) + ", " + eventTimes(e)
    return when {
        e.isOn(now) -> "$day · ${Labels.NOW_ON}"
        e.start > now && localDay(e.start) == localDay(now) -> "$day · ${Labels.inMinutes(((e.start - now) / 60_000).toInt())}"
        else -> day
    }
}

// The Now/Next line's tail: "now, until 15:00", "in 40m".
fun nowNextTail(e: CalendarEvent, now: Long): String =
    if (e.isOn(now)) "${Labels.NOW_ON}, until ${timeText(e.end)}" else Labels.inMinutes(((e.start - now) / 60_000).toInt())
