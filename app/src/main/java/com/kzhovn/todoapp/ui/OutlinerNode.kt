package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType

sealed class OutlinerNode {
    abstract val task: Task
    abstract val children: List<OutlinerNode>

    data class FolderNode(override val task: Task, override val children: List<OutlinerNode>) : OutlinerNode()
    data class TaskNode(override val task: Task, override val children: List<OutlinerNode>) : OutlinerNode()
}

fun buildOutlinerTree(tasks: List<Task>): List<OutlinerNode> {
    val byParent: Map<Long?, List<Task>> = tasks.groupBy { it.parentId }
    fun build(parentId: Long?): List<OutlinerNode> =
        byParent[parentId].orEmpty()
            .sortedBy { it.id }
            .map { task ->
                val children = build(task.id)
                if (task.type == TaskType.FOLDER) OutlinerNode.FolderNode(task, children)
                else OutlinerNode.TaskNode(task, children)
            }
    return build(null)
}

fun subtaskCounts(tasks: List<Task>): Map<Long, Pair<Int, Int>> {
    val byParent = tasks.groupBy { it.parentId }
    return tasks
        .filter { byParent.containsKey(it.id) }
        .associate { parent ->
            val children = byParent[parent.id].orEmpty()
            parent.id to (children.count { it.isComplete } to children.size)
        }
}
