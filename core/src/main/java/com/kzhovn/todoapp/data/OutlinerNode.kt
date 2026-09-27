package com.kzhovn.todoapp.data

// The All tree, shared by the phone and the web.
sealed class OutlinerNode {
    abstract val task: Task
    abstract val children: List<OutlinerNode>

    data class FolderNode(override val task: Task, override val children: List<OutlinerNode>) : OutlinerNode()
    data class TaskNode(override val task: Task, override val children: List<OutlinerNode>) : OutlinerNode()
}

fun buildOutlinerTree(tasks: List<Task>, hideCompleted: Boolean = false): List<OutlinerNode> {
    val byParent: Map<Long?, List<Task>> = tasks.groupBy { it.parentId }
    val seen = mutableSetOf<Long>() // a duplicate id in the input would otherwise recurse forever; place each task at most once
    fun build(parentId: Long?): List<OutlinerNode> =
        byParent[parentId].orEmpty()
            .filter { seen.add(it.id) }
            .sortedWith(TaskOrder)
            .flatMap { task ->
                val children = build(task.id)
                if (hideCompleted && task.type == TaskType.TASK && task.isComplete) {
                    // Splice this task's still-visible children in at the same level instead of
                    // dropping them along with their now-hidden completed parent.
                    children
                } else {
                    val node = if (task.type == TaskType.FOLDER) OutlinerNode.FolderNode(task, children)
                                else OutlinerNode.TaskNode(task, children)
                    listOf(node)
                }
            }
    return build(null)
}
