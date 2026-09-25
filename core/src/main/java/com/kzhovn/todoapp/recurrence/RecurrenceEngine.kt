package com.kzhovn.todoapp.recurrence

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
            RecurrenceType.AFTER_COMPLETION -> completedAt + TimeUnit.DAYS.toMillis(rule.toLong())
            RecurrenceType.RRULE -> nextRRuleOccurrence(task.startDate ?: completedAt, rule, completedAt)
                ?: return null
        }
        // Keep the start-to-due gap constant across recurrences instead of freezing dueDate at its
        // original (now stale) absolute timestamp.
        val startAnchor = task.startDate ?: completedAt
        val nextDueDate = task.dueDate?.plus(nextStart - startAnchor)
        return task.copy(id = newId(), startDate = nextStart, dueDate = nextDueDate, isComplete = false, completedAt = null)
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
