package com.kzhovn.todoapp.data

// Walks a parentId chain starting at startId, visiting each id in turn until `visit` returns
// non-null or a cycle is detected — a revisited id stops the walk instead of looping forever,
// since nothing in the data layer forbids a duplicate parentId chain. Shared by wouldCreateCycle
// (searches for a specific id) and resolveEffective (resolves the first non-null field value).
fun <T> walkParentChain(startId: Long, allById: Map<Long, Task>, visit: (Long) -> T?): T? {
    val seen = mutableSetOf<Long>()
    var current: Long? = startId
    while (current != null && seen.add(current)) {
        visit(current)?.let { return it }
        current = allById[current]?.parentId
    }
    return null
}

// Would picking candidateId as editingTaskId's parent create a cycle? Walks up candidateId's
// parentId chain looking for editingTaskId — a hit means editingTaskId would become its own
// descendant (directly, as its own parent, or transitively through any chain length).
fun wouldCreateCycle(candidateId: Long, editingTaskId: Long, allById: Map<Long, Task>): Boolean =
    walkParentChain(candidateId, allById) { id -> true.takeIf { id == editingTaskId } } ?: false
