package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.Task

// Would picking candidateId as editingTaskId's parent create a cycle? Walks up candidateId's
// parentId chain looking for editingTaskId — a hit means editingTaskId would become its own
// descendant (directly, as its own parent, or transitively through any chain length).
fun wouldCreateCycle(candidateId: Long, editingTaskId: Long, allById: Map<Long, Task>): Boolean {
    val seen = mutableSetOf<Long>()
    var current: Long? = candidateId
    while (current != null && seen.add(current)) {
        if (current == editingTaskId) return true
        current = allById[current]?.parentId
    }
    return false
}
