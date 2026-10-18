package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.quickadd.takeContexts

// contextId: a context, or NO_CONTEXT for tasks without one (their own or inherited).
data class SearchFilters(
    val folderId: Long? = null,
    val contextId: Long? = null,
    val starredOnly: Boolean = false,
    val includeCompleted: Boolean = false,
    val dueAfter: Long? = null,
    val dueBefore: Long? = null
) {
    companion object { const val NO_CONTEXT = -1L }
}

// Search typed with quick add's "@home": a context's name comes out of the text as that context (the last
// one, if several).
fun parseSearch(text: String, contexts: Collection<TaskContext>): Pair<String, Long?> =
    takeContexts(text, contexts).let { (rest, ids) -> rest.trim().replace(Regex("\\s+"), " ") to ids.lastOrNull() }

// The line of a task's notes a search matched, shown under its title in the searched list; null when the
// title matched (it says enough) or nothing in the notes did.
fun notesMatch(task: Task, query: String): String? {
    val q = query.trim().takeIf { it.isNotEmpty() } ?: return null
    if (task.title.contains(q, ignoreCase = true)) return null
    val line = task.notes?.lines()?.firstOrNull { it.contains(q, ignoreCase = true) }?.trim() ?: return null
    // A long line is cut to the part around the match.
    val at = line.indexOf(q, ignoreCase = true)
    val start = (at - 40).coerceAtLeast(0).let { s -> line.lastIndexOf(' ', s).takeIf { it in 1 until at }?.plus(1) ?: s }
    val end = (at + q.length + 60).coerceAtMost(line.length).let { e -> line.indexOf(' ', e).takeIf { it > 0 } ?: line.length }
    return (if (start > 0) "…" else "") + line.substring(start, end) + (if (end < line.length) "…" else "")
}

// Search, shared by the app and the server: title (or notes) contains the query (any case, spaces
// around it ignored), plus the filters. A folder filter takes everything under the folder, subfolders included;
// a context filter goes by the contexts a task has or inherits, as Active does.
fun searchMatcher(all: Collection<Task>, contextsByTaskId: Map<Long, Set<Long>>, query: String, filters: SearchFilters): (Task) -> Boolean {
    val byId = all.associateBy { it.id }
    val q = query.trim()
    return { t ->
        !t.type.isContainer && t.type != TaskType.EVENT && (t.title.contains(q, ignoreCase = true) || notesMatch(t, query) != null) &&
            (filters.includeCompleted || !t.isComplete) &&
            filters.folderId.let { it == null || isUnder(t, it, byId) } &&
            (!filters.starredOnly || t.isStarred) &&
            filters.dueAfter.let { after -> after == null || t.dueDate.let { it != null && it >= after } } &&
            filters.dueBefore.let { before -> before == null || t.dueDate.let { it != null && it <= before } } &&
            filters.contextId.let { c ->
                c == null || resolveEffective(t, byId, contextsByTaskId).effectiveContextIds.let { if (c == SearchFilters.NO_CONTEXT) it.isEmpty() else c in it }
            }
    }
}

// The All tree cut down to what a search kept (1A): those tasks, and the folders and tasks above them.
fun pruneTree(nodes: List<OutlinerNode>, keep: Set<Long>): List<OutlinerNode> = nodes.mapNotNull { node ->
    val children = pruneTree(node.children, keep)
    if (node.task.id !in keep && children.isEmpty()) null
    else when (node) {
        is OutlinerNode.FolderNode -> node.copy(children = children)
        is OutlinerNode.TaskNode -> node.copy(children = children)
    }
}
