package com.kzhovn.todoapp.data

// A checklist's children are items (groceries, packing), loose or in its sections (one level of SECTION,
// Clothes and Winter in a packing list): ticked off inside it, never shown on their own in Doing, Active,
// the widget or Review.
fun checklistOf(task: Task, byId: Map<Long, Task>): Task? = byId[task.parentId]?.let { parent ->
    when (parent.type) {
        TaskType.CHECKLIST -> parent
        TaskType.SECTION -> byId[parent.parentId]?.takeIf { it.type == TaskType.CHECKLIST }
        else -> null
    }
}

fun isChecklistItem(task: Task, byId: Map<Long, Task>): Boolean = task.type != TaskType.SECTION && checklistOf(task, byId) != null

// Open items first in list order, then checked ones (they sink to the bottom).
val ChecklistItemOrder: Comparator<Task> = compareBy<Task> { it.isComplete }.then(TaskOrder)

// Its loose items (section null), then each section with its own, open ones first in each.
fun checklistGroups(checklistId: Long, all: Collection<Task>): List<Pair<Task?, List<Task>>> {
    val children = all.filter { it.parentId == checklistId }
    val sections = children.filter { it.type == TaskType.SECTION }.sortedWith(TaskOrder)
    return listOf(null to children.filter { it.type != TaskType.SECTION }.sortedWith(ChecklistItemOrder)) +
        sections.map { s -> s to all.filter { it.parentId == s.id }.sortedWith(ChecklistItemOrder) }
}

// Every item, its sections' too, in that order.
fun checklistItems(checklistId: Long, all: Collection<Task>): List<Task> = checklistGroups(checklistId, all).flatMap { it.second }

// The items a checklist or one of its sections holds: what Uncheck all and Clear checked work on.
fun itemsIn(containerId: Long, all: Collection<Task>): List<Task> =
    if (all.firstOrNull { it.id == containerId }?.type == TaskType.SECTION) all.filter { it.parentId == containerId } else checklistItems(containerId, all)

// "milk, eggs, bread" is three items.
fun splitItems(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
