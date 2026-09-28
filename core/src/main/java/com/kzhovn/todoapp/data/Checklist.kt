package com.kzhovn.todoapp.data

// A checklist's children are items (groceries, packing): ticked off inside it, never shown on
// their own in Doing, Active, the widget or Review.
fun isChecklistItem(task: Task, byId: Map<Long, Task>): Boolean = byId[task.parentId]?.type == TaskType.CHECKLIST

// Open items first in list order, then checked ones (they sink to the bottom).
val ChecklistItemOrder: Comparator<Task> = compareBy<Task> { it.isComplete }.then(TaskOrder)

fun checklistItems(checklistId: Long, all: Collection<Task>): List<Task> =
    all.filter { it.parentId == checklistId }.sortedWith(ChecklistItemOrder)

// "milk, eggs, bread" is three items.
fun splitItems(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
