package com.kzhovn.todoapp.data

import kotlinx.serialization.Serializable

// What completing a task with open subtasks asks, wherever it's ticked (phone, web, top bar, Discord):
// complete them too, or move them out. A checklist asks about its unchecked items instead, which go to
// a new copy of the list rather than out of it. Carried out by planCompleteChoosing.
@Serializable
data class CompleteQuestion(val text: String, val completeAll: String, val moveOut: String, val cancel: String = Labels.CANCEL)

fun completeQuestion(task: Task, open: Int): CompleteQuestion? = when {
    open == 0 || task.isComplete -> null
    task.type == TaskType.CHECKLIST -> CompleteQuestion(Labels.uncheckedItems(open), Labels.COMPLETE_THEM_TOO, Labels.MOVE_TO_NEW_LIST)
    else -> CompleteQuestion(Labels.activeSubtasks(open), Labels.COMPLETE_SUBTASKS_TOO, Labels.MOVE_SUBTASKS_OUT)
}
