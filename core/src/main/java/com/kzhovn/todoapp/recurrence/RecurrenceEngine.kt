package com.kzhovn.todoapp.recurrence

import com.kzhovn.todoapp.quickadd.startOfDay
import com.kzhovn.todoapp.data.RecurrenceType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.newId
import org.dmfs.rfc5545.recur.RecurrenceRule
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object RecurrenceEngine {

    fun nextInstance(task: Task, completedAt: Long): Task? {
        val type = task.recurrenceType ?: return null
        val rule = task.recurrenceRule ?: return null
        val nextStart = when (type) {
            RecurrenceType.AFTER_COMPLETION -> afterCompletion(rule, completedAt) ?: return null
            RecurrenceType.RRULE -> nextRRuleOccurrence(task.startDate ?: completedAt, rule, completedAt)
                ?: return null
        }
        // "After N times": each new instance starts the rule afresh from its own date, so the count
        // left is carried down one per instance, and the last instance spawns nothing.
        val count = if (type == RecurrenceType.RRULE) Regex("COUNT=(\\d+)").find(rule)?.groupValues?.get(1)?.toInt() else null
        if (count != null && count <= 1) return null
        val nextRule = if (count != null) rule.replace("COUNT=$count", "COUNT=${count - 1}") else rule
        // Keep the start-to-due gap constant across recurrences instead of freezing dueDate at its
        // original (now stale) absolute timestamp.
        val startAnchor = task.startDate ?: completedAt
        val nextDueDate = task.dueDate?.plus(nextStart - startAnchor)
        return task.copy(id = newId(), startDate = nextStart, dueDate = nextDueDate, isComplete = false, completedAt = null, recurrenceRule = nextRule)
    }

    // The instance that completing `completed` spawned, if it still exists unedited. Un-completing
    // removes it, so undo (e.g. un-reacting in Discord) doesn't leave a duplicate behind; an
    // edited successor is kept because it now holds the user's work.
    fun untouchedSuccessor(completed: Task, candidates: List<Task>): Task? {
        val expected = nextInstance(completed, completed.completedAt ?: return null) ?: return null
        return candidates.firstOrNull { it.id != completed.id && it.copy(id = expected.id) == expected }
    }

    // Fresh, uncompleted copies of a recurring task's subtasks for its next instance (MLO's
    // "uncomplete subtasks"), with their dates shifted as far as the parent's start moved. Returns
    // (original id, copy) pairs so callers can carry contexts over. Recurring subtasks are left out:
    // they already spawn their own next instances.
    fun successorSubtasks(completed: Task, next: Task, descendants: List<Task>): List<Pair<Long, Task>> {
        val shift = (next.startDate ?: return emptyList()) - (completed.startDate ?: completed.completedAt ?: return emptyList())
        val newIds = mutableMapOf(completed.id to next.id)
        return descendants.sortedBy { it.id }.mapNotNull { sub ->
            val parent = newIds[sub.parentId] ?: return@mapNotNull null
            if (sub.recurrenceType != null) return@mapNotNull null
            val copy = sub.copy(
                id = newId(), parentId = parent, isComplete = false, completedAt = null,
                startDate = sub.startDate?.plus(shift), dueDate = sub.dueDate?.plus(shift)
            )
            newIds[sub.id] = copy.id
            sub.id to copy
        }
    }

    // The Repeat sheet's preview: the next few dates a schedule lands on, starting from `anchor`, or
    // for "after completion", when the next one would be if finished at `now`.
    fun preview(type: RecurrenceType, rule: String, anchor: Long, now: Long, count: Int = 3): List<Long> = when (type) {
        RecurrenceType.AFTER_COMPLETION -> listOfNotNull(afterCompletion(rule, now))
        RecurrenceType.RRULE -> runCatching {
            val iterator = RecurrenceRule(rule).iterator(anchor, TimeZone.getDefault())
            generateSequence { if (iterator.hasNext()) iterator.next().timestamp else null }
                .dropWhile { it < startOfDay(now) }.take(count).toList()
        }.getOrDefault(emptyList())
    }

    // "3" days, "2w" weeks, "1m" months (calendar months: Jan 31 + 1m is Feb's last day).
    private fun afterCompletion(rule: String, completedAt: Long): Long? {
        val n = rule.trimEnd('w', 'm').toIntOrNull() ?: return null
        return when (rule.last()) {
            'w' -> completedAt + TimeUnit.DAYS.toMillis(7L * n)
            'm' -> java.util.Calendar.getInstance().apply { timeInMillis = completedAt; add(java.util.Calendar.MONTH, n) }.timeInMillis
            else -> completedAt + TimeUnit.DAYS.toMillis(n.toLong())
        }
    }

    private fun startOfDay(ms: Long) = java.util.Calendar.getInstance().apply { timeInMillis = ms }.startOfDay()

    private fun nextRRuleOccurrence(dtStart: Long, rrule: String, after: Long): Long? {
        val recurrenceRule = RecurrenceRule(rrule)
        val iterator = recurrenceRule.iterator(dtStart, TimeZone.getDefault())
        while (iterator.hasNext()) {
            val next = iterator.next().timestamp
            if (next > after) return next
        }
        return null
    }
}
