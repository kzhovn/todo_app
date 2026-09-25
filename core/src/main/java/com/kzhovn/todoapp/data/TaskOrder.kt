package com.kzhovn.todoapp.data

// Sibling order everywhere (outliner, sequential parents): a manual position if the list was ever
// reordered, else creation order. Positions are small (1..n) and ids huge, so never-reordered
// newcomers land at the end; id breaks ties.
val TaskOrder: Comparator<Task> = compareBy<Task>({ it.position ?: it.id }, { it.id })

// Open projects whose subtasks are all done: time to complete the project or add the next step.
fun stalledProjects(tasks: List<Task>): List<Task> {
    val openChildren = tasks.filter { !it.isComplete && it.type != TaskType.FOLDER }.groupBy { it.parentId }
    return tasks.filter { it.type == TaskType.PROJECT && !it.isComplete && openChildren[it.id].isNullOrEmpty() }
}
