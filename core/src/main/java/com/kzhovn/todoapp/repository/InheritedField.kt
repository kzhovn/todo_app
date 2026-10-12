package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task

// The fields subtasks inherit from their parents when they don't set their own (see resolveEffective).
enum class InheritedField(val label: String) { START("start date"), DUE("due date"), CONTEXTS("contexts"), ICON("icon") }

// The inherited fields an edit changes, which may need "update subtasks too?" (see overridingSubtasks).
fun changedInheritedFields(before: Task, after: Task, contextsBefore: Set<Long>, contextsAfter: Set<Long>): Set<InheritedField> = buildSet {
    if (after.startDate != before.startDate) add(InheritedField.START)
    if (after.dueDate != before.dueDate) add(InheritedField.DUE)
    if (after.icon != before.icon) add(InheritedField.ICON)
    if (contextsAfter != contextsBefore) add(InheritedField.CONTEXTS)
}

// Whether a subtask sets its own value for the field, and so wouldn't follow its parent's change.
fun overridesInherited(task: Task, contextIds: Set<Long>, field: InheritedField): Boolean = when (field) {
    InheritedField.START -> task.startDate != null
    InheritedField.DUE -> task.dueDate != null
    InheritedField.ICON -> task.icon != null
    InheritedField.CONTEXTS -> contextIds.isNotEmpty()
}

// Of a task's descendants, the ones that set their own value for a changed field, and the changed fields
// some of them do set: what "Update subtasks too?" asks about. Nothing to ask: both empty.
fun overridingSubtasks(descendants: List<Task>, contexts: Map<Long, Set<Long>>, changed: Set<InheritedField>): Pair<List<Task>, Set<InheritedField>> {
    fun overrides(t: Task, f: InheritedField) = overridesInherited(t, contexts[t.id].orEmpty(), f)
    val tasks = descendants.filter { d -> changed.any { overrides(d, it) } }
    return tasks to changed.filterTo(mutableSetOf()) { f -> tasks.any { overrides(it, f) } }
}

// The task with those fields cleared, so it inherits them again (contexts are cleared by the caller).
fun Task.clearInherited(fields: Set<InheritedField>): Task = copy(
    startDate = startDate.takeUnless { InheritedField.START in fields },
    dueDate = dueDate.takeUnless { InheritedField.DUE in fields },
    icon = icon.takeUnless { InheritedField.ICON in fields }
)
