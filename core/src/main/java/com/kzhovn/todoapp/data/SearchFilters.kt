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
fun searchTasks(all: Collection<Task>, contextsByTaskId: Map<Long, Set<Long>>, query: String, filters: SearchFilters): List<Task> {
    val byId = all.associateBy { it.id }
    val folderId = filters.folderId
    fun inFolder(t: Task) = folderId == null || t.parentId?.let { walkParentChain(it, byId) { id -> true.takeIf { id == folderId } } } == true
    return all.filter { t ->
        t.type != TaskType.FOLDER && t.title.contains(query.trim(), ignoreCase = true) &&
            (filters.includeCompleted || !t.isComplete) &&
            inFolder(t) &&
            (!filters.starredOnly || t.isStarred) &&
            filters.dueAfter.let { after -> after == null || t.dueDate.let { it != null && it >= after } } &&
            filters.dueBefore.let { before -> before == null || t.dueDate.let { it != null && it <= before } } &&
            (filters.contextId == null || filters.contextId in contextsByTaskId[t.id].orEmpty())
    }.sortedBy { it.id }
}
