package com.kzhovn.todoapp.data

data class SearchFilters(
    val folderId: Long? = null,
    val contextId: Long? = null,
    val starredOnly: Boolean = false,
    val includeCompleted: Boolean = false,
    val dueAfter: Long? = null,
    val dueBefore: Long? = null
)

// Search, shared by the app and the server: title contains the query (any case, spaces around it
// ignored), plus the filters. A folder filter takes everything under the folder, subfolders included.
// The line of a task's notes a search matched, shown under its title in the results; null when the
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

fun searchTasks(all: Collection<Task>, contextsByTaskId: Map<Long, Set<Long>>, query: String, filters: SearchFilters): List<Task> {
    val byId = all.associateBy { it.id }
    val folderId = filters.folderId
    fun inFolder(t: Task) = folderId == null || isUnder(t, folderId, byId)
    return all.filter { t ->
        t.type != TaskType.FOLDER && (t.title.contains(query.trim(), ignoreCase = true) || notesMatch(t, query) != null) &&
            (filters.includeCompleted || !t.isComplete) &&
            inFolder(t) &&
            (!filters.starredOnly || t.isStarred) &&
            filters.dueAfter.let { after -> after == null || t.dueDate.let { it != null && it >= after } } &&
            filters.dueBefore.let { before -> before == null || t.dueDate.let { it != null && it <= before } } &&
            (filters.contextId == null || filters.contextId in contextsByTaskId[t.id].orEmpty())
    }.sortedBy { it.id }
}
