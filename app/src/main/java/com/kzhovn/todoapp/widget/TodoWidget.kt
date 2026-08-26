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
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.quickadd.QuickAddActivity
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.ui.TaskEditActivity
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerStar

val taskIdKey = ActionParameters.Key<Long>("task_id")

private fun fixed(color: androidx.compose.ui.graphics.Color) = ColorProvider(day = color, night = color)

class TodoWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = (context.applicationContext as TodoApp).repository
        val now = System.currentTimeMillis()
        val activeTasks = repository.getActiveTasks(now, currentMinuteOfDay(), currentDayMask())
        val rows = TodoWidgetPresenter.toRows(filterDoing(activeTasks, now))

        provideContent {
            Column(modifier = GlanceModifier.background(fixed(LedgerBackground)).padding(8.dp)) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DOING",
                        style = TextStyle(
                            color = fixed(LedgerMuted),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif
                        ),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = "+",
                        style = TextStyle(color = fixed(LedgerAccent), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier
                            .size(18.dp)
                            .background(fixed(LedgerAccentSoft))
                            .clickable(actionStartActivity<QuickAddActivity>())
                    )
                }
                LazyColumn {
                    items(rows, itemId = { it.id }) { row ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.padding(vertical = 2.dp)) {
                            Text(
                                text = "✓",
                                style = TextStyle(color = fixed(if (row.isComplete) LedgerAccent else LedgerMuted), fontSize = 11.sp),
                                modifier = GlanceModifier.clickable(
                                    actionRunCallback<ToggleCompleteAction>(actionParametersOf(taskIdKey to row.id))
                                )
                            )
                            Text(
                                text = row.title,
                                style = TextStyle(color = fixed(LedgerInk), fontSize = 11.sp, fontFamily = FontFamily.Serif),
                                modifier = GlanceModifier
                                    .defaultWeight()
                                    .padding(horizontal = 4.dp)
                                    .clickable(
                                        actionStartActivity<TaskEditActivity>(
                                            parameters = actionParametersOf(
                                                ActionParameters.Key<Long>(TaskEditActivity.EXTRA_TASK_ID) to row.id
                                            )
                                        )
                                    )
                            )
                            Text(
                                text = if (row.isStarred) "★" else "☆",
                                style = TextStyle(color = fixed(if (row.isStarred) LedgerStar else LedgerMuted), fontSize = 11.sp),
                                modifier = GlanceModifier.clickable(
                                    actionRunCallback<ToggleStarAction>(actionParametersOf(taskIdKey to row.id))
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun currentMinuteOfDay(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    private fun currentDayMask(): Int {
        val cal = java.util.Calendar.getInstance()
        return 1 shl (cal.get(java.util.Calendar.DAY_OF_WEEK) - 1)
    }
}

class ToggleCompleteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[taskIdKey] ?: return
        val repository = (context.applicationContext as TodoApp).repository
        val task = repository.getTask(taskId)
        val now = System.currentTimeMillis()
        // A Glance widget can't show the in-app 3-way dialog for a task with active subtasks,
        // so completing from the widget always cascades — the "complete subtasks too" choice.
        if (task != null && !task.isComplete) {
            repository.completeWithDescendants(taskId, now)
        } else {
            repository.toggleComplete(taskId, now)
        }
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
