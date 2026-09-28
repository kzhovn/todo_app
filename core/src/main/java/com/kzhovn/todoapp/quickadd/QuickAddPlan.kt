package com.kzhovn.todoapp.quickadd

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.nextRollover
import com.kzhovn.todoapp.data.splitItems

private val prefixRegex = Regex("^\\s*([^:]+?)\\s*:\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)
private val contextRegex = Regex("(?<!\\S)@(\\S+)(?!\\S)")
// `d:` (or rusabot's `daily:`): gone at the next day rollover.
private val TODAY_ONLY_PREFIXES = setOf("d", "daily")
// When a folder, checklist and project share a name, the first of these wins.
private val TARGETS = listOf(TaskType.FOLDER, TaskType.CHECKLIST, TaskType.PROJECT)

// Quick add with the names in it matched, the same on every device: "work: x" into the folder
// (or project) Work, "groceries: milk, eggs" as items into the checklist Groceries, "d: x" today
// only, "@home" a context. A name that matches nothing stays in the title ("Re: invoice").
// The task's parentId is set only by a prefix; callers fill in their default folder.
fun planQuickAdd(input: String, tasks: Collection<Task>, contexts: Collection<TaskContext>, now: Long, rolloverHour: Int): QuickAdd {
    val prefix = prefixRegex.matchEntire(input)
    val name = prefix?.groupValues?.get(1)
    val todayOnly = name?.lowercase() in TODAY_ONLY_PREFIXES
    val target = name?.takeUnless { todayOnly }?.let { n ->
        tasks.filter { it.type in TARGETS && !it.isComplete && it.title.trim().equals(n, ignoreCase = true) }.minByOrNull { TARGETS.indexOf(it.type) }
    }
    val body = if (todayOnly || target != null) prefix!!.groupValues[2] else input
    if (target?.type == TaskType.CHECKLIST) return QuickAdd(task = null, items = splitItems(body), intoChecklist = target.id)

    val byName = contexts.associateBy { it.name.lowercase().replace(" ", "") }
    val contextIds = mutableSetOf<Long>()
    val text = contextRegex.replace(body) { m -> byName[m.groupValues[1].lowercase()]?.let { contextIds += it.id; "" } ?: m.value }
    val parsed = QuickAddParser.read(text, now)
    val task = parsed.task!!.copy(parentId = target?.id, expiresAt = if (todayOnly) nextRollover(now, rolloverHour) else null)
    return parsed.copy(task = task, contextIds = contextIds)
}
