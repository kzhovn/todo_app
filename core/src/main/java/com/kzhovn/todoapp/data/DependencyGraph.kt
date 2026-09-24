package com.kzhovn.todoapp.data

// Would adding `candidateId` as a dependency of `editingTaskId` create a cycle? True when
// editingTaskId is reachable from candidateId by following existing dependsOn edges — i.e.
// candidateId already (transitively) depends on editingTaskId, so the new edge would close a loop.
fun wouldCreateDependencyCycle(candidateId: Long, editingTaskId: Long, edges: List<TaskDependency>): Boolean {
    val adjacency = edges.groupBy({ it.taskId }, { it.dependsOnTaskId })
    val visited = mutableSetOf<Long>()
    val stack = ArrayDeque<Long>().apply { add(candidateId) }
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        if (current == editingTaskId) return true
        if (!visited.add(current)) continue
        adjacency[current]?.forEach { stack.add(it) }
    }
    return false
}
