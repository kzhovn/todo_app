package com.kzhovn.todoapp.data

// A task's ancestors, outermost first: the editor's breadcrumb. Capped, so a corrupt loop can't hang it.
fun ancestors(task: Task, byId: Map<Long, Task>): List<Task> =
    generateSequence(byId[task.parentId]) { byId[it.parentId] }.take(64).toList().reversed()

// Where "move out" takes a subtask of `parent`: straight to the folder `parent` sits in, however deep
// (null: no folder).
fun moveOutFolderId(parent: Task, byId: Map<Long, Task>): Long? = ancestors(parent, byId).lastOrNull { it.type == TaskType.FOLDER }?.id

// A parent's subtasks done, of all (the Parent row's progress).
fun subtaskProgress(parentId: Long, all: List<Task>): Pair<Int, Int> =
    all.filter { it.parentId == parentId && it.type != TaskType.FOLDER }.let { subs -> subs.count { it.isComplete } to subs.size }
