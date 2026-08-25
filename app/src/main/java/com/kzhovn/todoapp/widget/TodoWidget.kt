package com.kzhovn.todoapp.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.text.Text
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.quickadd.QuickAddActivity
import com.kzhovn.todoapp.ui.TaskEditActivity

val taskIdKey = ActionParameters.Key<Long>("task_id")

class TodoWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = (context.applicationContext as TodoApp).repository
        val now = System.currentTimeMillis()
        val rows = TodoWidgetPresenter.toRows(repository.getActiveTasks(now, currentMinuteOfDay()))

        provideContent {
            // Column caps at 10 direct children and silently drops the rest — LazyColumn for the
            // task rows keeps "+ Add" (a sibling, not a LazyColumn child) always visible.
            Column {
                LazyColumn {
                    items(rows, itemId = { it.id }) { row ->
                        Row {
                            Text(
                                text = "✓",
                                modifier = GlanceModifier.clickable(
                                    actionRunCallback<ToggleCompleteAction>(
                                        actionParametersOf(taskIdKey to row.id)
                                    )
                                )
                            )
                            Text(
                                text = row.title,
                                modifier = GlanceModifier.clickable(
                                    actionStartActivity<TaskEditActivity>(
                                        parameters = actionParametersOf(
                                            ActionParameters.Key<Long>(TaskEditActivity.EXTRA_TASK_ID) to row.id
                                        )
                                    )
                                )
                            )
                            Text(
                                text = if (row.isStarred) "★" else "☆",
                                modifier = GlanceModifier.clickable(
                                    actionRunCallback<ToggleStarAction>(
                                        actionParametersOf(taskIdKey to row.id)
                                    )
                                )
                            )
                        }
                    }
                }
                Text(
                    text = "+ Add",
                    modifier = GlanceModifier.clickable(actionStartActivity<QuickAddActivity>())
                )
            }
        }
    }

    private fun currentMinuteOfDay(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }
}

class ToggleCompleteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[taskIdKey] ?: return
        val repository = (context.applicationContext as TodoApp).repository
        repository.markComplete(taskId, System.currentTimeMillis())
        TodoWidget().update(context, glanceId)
    }
}

class ToggleStarAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[taskIdKey] ?: return
        val repository = (context.applicationContext as TodoApp).repository
        repository.toggleStar(taskId)
        TodoWidget().update(context, glanceId)
    }
}
