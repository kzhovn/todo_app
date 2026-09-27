package com.kzhovn.todoapp.data

// In the flat lists a subtask is shown as "Parent: subtask" (and a checklist item, in search, as
// "Groceries: milk"). Being inside a folder isn't being a subtask.
fun subtaskParentTitle(task: Task, byId: Map<Long, Task>): String? =
    byId[task.parentId]?.takeIf { it.type != TaskType.FOLDER }?.title
