package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task

// The fields subtasks inherit from their parents when they don't set their own (see resolveEffective).
enum class InheritedField(val label: String) { START("start date"), DUE("due date"), CONTEXTS("contexts"), ICON("icon") }

// The inherited fields an edit changes, which may need "update subtasks too?" (see descendantsOverriding).
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
