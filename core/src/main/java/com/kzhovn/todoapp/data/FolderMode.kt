package com.kzhovn.todoapp.data

// Folder mode (docs/superpowers/specs/2026-09-29-folder-mode-design.md): every device zoomed into one
// folder and everything under it, until it's left. Null: no mode.

// The mode's folder, if it still exists: deleting the folder ends the mode.
fun modeFolder(modeFolderId: Long?, byId: Map<Long, Task>): Task? = modeFolderId?.let(byId::get)?.takeIf { it.type == TaskType.FOLDER }

fun inMode(task: Task, modeFolderId: Long?, byId: Map<Long, Task>): Boolean =
    modeFolderId == null || task.id == modeFolderId || isUnder(task, modeFolderId, byId)

// Doing's reminder of what the mode hides: Doing's tasks outside it that are due today or overdue.
fun dueOutsideMode(doing: List<Task>, modeFolderId: Long?, byId: Map<Long, Task>, now: Long, effectiveDue: (Task) -> Long?): List<Task> =
    if (modeFolderId == null) emptyList()
    else doing.filter { !inMode(it, modeFolderId, byId) && effectiveDue(it)?.let { due -> dueStatus(due, now) != DueStatus.LATER } == true }
