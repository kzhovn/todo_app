package com.kzhovn.todoapp.data

// The All tree, shared by the phone and the web.
sealed class OutlinerNode {
    abstract val task: Task
    abstract val children: List<OutlinerNode>

    data class FolderNode(override val task: Task, override val children: List<OutlinerNode>) : OutlinerNode()
    data class TaskNode(override val task: Task, override val children: List<OutlinerNode>) : OutlinerNode()
}

// The tree inside one folder (folder mode, a folder's view); null: the whole tree.
fun buildOutlinerTree(tasks: List<Task>, hideCompleted: Boolean, rootId: Long?): List<OutlinerNode> {
    val whole = buildOutlinerTree(tasks, hideCompleted)
    fun find(nodes: List<OutlinerNode>): OutlinerNode? = nodes.firstNotNullOfOrNull { if (it.task.id == rootId) it else find(it.children) }
    return if (rootId == null) whole else find(whole)?.children.orEmpty()
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
                if (hideCompleted && task.type == TaskType.CHECKLIST && task.isComplete) {
                    emptyList() // its items and sections only live inside it
                } else if (hideCompleted && (task.type == TaskType.TASK || task.type == TaskType.PROJECT) && task.isComplete) {
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
