package com.kzhovn.todoapp.repository

// The fields subtasks inherit from their parents when they don't set their own (see resolveEffective).
enum class InheritedField(val label: String) { START("start date"), DUE("due date"), CONTEXTS("contexts"), ICON("icon") }
