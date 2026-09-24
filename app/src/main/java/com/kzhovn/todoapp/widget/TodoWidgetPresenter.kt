package com.kzhovn.todoapp.widget

import com.kzhovn.todoapp.data.Task

data class WidgetTaskRow(val id: Long, val title: String, val isComplete: Boolean, val isStarred: Boolean, val isMaybe: Boolean = false)

object TodoWidgetPresenter {
    fun toRows(tasks: List<Task>): List<WidgetTaskRow> =
        tasks.map { WidgetTaskRow(it.id, it.title, it.isComplete, it.isStarred, it.isMaybe) }
}
