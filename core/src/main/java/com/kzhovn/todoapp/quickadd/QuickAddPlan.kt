package com.kzhovn.todoapp.quickadd

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.repository.templateRoots
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
// The task's parentId is set only by a prefix; callers fill in their default folder. "use packing" makes a list
// from the template Packing (`tasks` must hold the templates for that); after a colon, the rest is its title's
// end and dates, as quick add reads a task: "use packing: Lisbon -d fri" is "Packing: Lisbon", due Friday.
fun planQuickAdd(input: String, tasks: Collection<Task>, contexts: Collection<TaskContext>, now: Long, rolloverHour: Int): QuickAdd {
    fromTemplate(input.trim(), tasks, now)?.let { return it }
    val prefix = prefixRegex.matchEntire(input)
    val name = prefix?.groupValues?.get(1)
    val todayOnly = name?.lowercase() in TODAY_ONLY_PREFIXES
    val target = name?.takeUnless { todayOnly }?.let { n ->
        tasks.filter { it.type in TARGETS && !it.isComplete && !it.isTemplate && it.title.trim().equals(n, ignoreCase = true) }.minByOrNull { TARGETS.indexOf(it.type) }
    }
    val body = if (todayOnly || target != null) prefix!!.groupValues[2] else input
    if (target?.type == TaskType.CHECKLIST) return QuickAdd(task = null, items = splitItems(body), intoChecklist = target.id)

    // Contexts only come from the title's part, not the note's.
    val (titlePart, notes) = QuickAddParser.splitNotes(body)
    val byName = contexts.associateBy { it.name.lowercase().replace(" ", "") }
    val contextIds = mutableSetOf<Long>()
    val text = contextRegex.replace(titlePart) { m -> byName[m.groupValues[1].lowercase()]?.let { contextIds += it.id; "" } ?: m.value }
    val parsed = QuickAddParser.read(text, now)
    val task = parsed.task!!.copy(parentId = target?.id, expiresAt = if (todayOnly) nextRollover(now, rolloverHour) else null, notes = notes)
    return parsed.copy(task = task, contextIds = contextIds)
}

private fun fromTemplate(input: String, tasks: Collection<Task>, now: Long): QuickAdd? {
    if (!input.startsWith("use ", ignoreCase = true)) return null
    val rest = input.substring(4).trim()
    // The longest template title it starts with, followed by its end or a colon.
    val template = templateRoots(tasks).sortedByDescending { it.title.length }.firstOrNull { t ->
        rest.startsWith(t.title.trim(), ignoreCase = true) && rest.substring(t.title.trim().length).let { it.isBlank() || it.trimStart().startsWith(":") }
    } ?: return null
    val after = rest.substring(template.title.trim().length).trimStart().removePrefix(":").trim()
    val parsed = after.takeIf { it.isNotEmpty() }?.let { QuickAddParser.read(it, now) }
    val title = parsed?.task?.title?.takeIf { it.isNotBlank() }?.let { "${template.title}: $it" } ?: template.title
    return QuickAdd(task = Task(title = title, startDate = parsed?.task?.startDate, dueDate = parsed?.task?.dueDate), fromTemplate = template.id, pin = parsed?.pin == true, focus = parsed?.focus == true)
}

// What quick add's Folder chip offers: folders, and open checklists (whose items the text then is).
fun quickAddTargets(tasks: Collection<Task>): List<Task> = tasks.filter { it.type == TaskType.FOLDER || (it.type == TaskType.CHECKLIST && !it.isComplete) }

// A checklist on the Folder chip: the text is its new items.
fun chipItems(text: String, chip: Task?): QuickAdd? =
    chip?.takeIf { it.type == TaskType.CHECKLIST }?.let { QuickAdd(task = null, items = splitItems(text), intoChecklist = it.id) }

// Quick add's chips (the phone's and the top bar's) over what the text says: they win, except that a folder
// named in the text beats the Folder chip.
fun QuickAdd.withChips(star: Boolean, start: Long?, due: Long?, todayOnly: Boolean, folder: Task?, now: Long, rolloverHour: Int): QuickAdd {
    val task = task ?: return this
    return copy(task = task.copy(
        isStarred = task.isStarred || star,
        startDate = start ?: task.startDate,
        dueDate = due ?: task.dueDate,
        parentId = task.parentId ?: folder?.takeIf { it.type == TaskType.FOLDER }?.id,
        expiresAt = if (todayOnly) nextRollover(now, rolloverHour) else task.expiresAt
    ))
}
