package com.kzhovn.todoapp.data

// In the flat Doing/Active lists a subtask is shown as "Parent: subtask". Only a task or project
// parent counts; being inside a folder isn't being a subtask.
fun subtaskParentTitle(task: Task, byId: Map<Long, Task>): String? =
    byId[task.parentId]?.takeIf { it.type == TaskType.TASK || it.type == TaskType.PROJECT }?.title
