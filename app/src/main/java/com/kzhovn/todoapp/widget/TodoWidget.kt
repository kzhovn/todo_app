package com.kzhovn.todoapp.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.data.walkParentChain
import com.kzhovn.todoapp.quickadd.QuickAddActivity
import com.kzhovn.todoapp.repository.dayOfWeekMask
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.repository.minuteOfDay
import com.kzhovn.todoapp.ui.TaskEditActivity
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerStar

val taskIdKey = ActionParameters.Key<Long>("task_id")

enum class WidgetMode(val label: String) { DOING("Doing"), ACTIVE("Active"), ALL("All") }

val WIDGET_MODE_KEY = stringPreferencesKey("mode")
val WIDGET_FOLDER_KEY = longPreferencesKey("folder")

fun Preferences.widgetMode(): WidgetMode =
    this[WIDGET_MODE_KEY]?.let { runCatching { WidgetMode.valueOf(it) }.getOrNull() } ?: WidgetMode.DOING

private fun fixed(color: androidx.compose.ui.graphics.Color) = ColorProvider(day = color, night = color)

class TodoWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val mode = prefs.widgetMode()
        val folderId = prefs[WIDGET_FOLDER_KEY]

        val repository = (context.applicationContext as TodoApp).repository
        val now = System.currentTimeMillis()
        val allTasks = repository.getAllTasks()
        val allById = allTasks.associateBy { it.id }
        val contextsByTaskId = repository.getAllTaskContexts()
        val tasks = when (mode) {
            WidgetMode.ALL -> allTasks.filter { it.type == TaskType.TASK && !it.isComplete }
            else -> {
                val active = repository.getActiveTasksFrom(allTasks, contextsByTaskId, now, minuteOfDay(now), dayOfWeekMask(now))
                if (mode == WidgetMode.ACTIVE) active
                else filterDoing(active, now) { resolveEffective(it, allById, contextsByTaskId).effectiveDueDate }
            }
        }.filter { folderId == null || isUnder(it, folderId, allById) }
        val rows = TodoWidgetPresenter.toRows(tasks)
        val folderName = folderId?.let { allById[it]?.title }
        val header = mode.label.uppercase() + folderName?.let { " · $it" }.orEmpty()

        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // The data Uri keeps each widget's PendingIntent distinct, so every widget opens its own settings.
        val configIntent = Intent(context, WidgetConfigActivity::class.java)
            .setData(Uri.parse("todowidget://config/$appWidgetId"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

        provideContent {
            Column(modifier = GlanceModifier.fillMaxSize().background(fixed(LedgerBackground)).padding(12.dp)) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = header,
                        style = TextStyle(color = fixed(LedgerMuted), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(configIntent))
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = GlanceModifier
                            .size(40.dp)
                            .cornerRadius(20.dp)
                            .background(fixed(LedgerAccentSoft))
                            .clickable(
                                actionStartActivity<QuickAddActivity>(
                                    actionParametersOf(ActionParameters.Key<Long>(QuickAddActivity.EXTRA_FOLDER_ID) to (folderId ?: 0L))
                                )
                            )
                    ) {
                        Text("+", style = TextStyle(color = fixed(LedgerAccent), fontSize = 24.sp, fontWeight = FontWeight.Bold))
                    }
                }
                if (rows.isEmpty()) {
                    Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.fillMaxSize()) {
                        Text("Nothing here", style = TextStyle(color = fixed(LedgerMuted), fontSize = 15.sp))
                    }
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(rows, itemId = { it.id }) { row -> WidgetRow(row) }
                    }
                }
            }
        }
    }

    private fun isUnder(task: Task, folderId: Long, allById: Map<Long, Task>): Boolean =
        task.parentId?.let { parent -> walkParentChain(parent, allById) { id -> true.takeIf { id == folderId } } } ?: false
}

// 44dp boxes around the check and star glyphs give thumb-sized tap targets.
@androidx.compose.runtime.Composable
private fun WidgetRow(row: WidgetTaskRow) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier.size(44.dp)
                .clickable(actionRunCallback<ToggleCompleteAction>(actionParametersOf(taskIdKey to row.id)))
        ) {
            Text(
                text = if (row.isComplete) "✓" else "○",
                style = TextStyle(color = fixed(if (row.isComplete) LedgerAccent else LedgerMuted), fontSize = 24.sp)
            )
        }
        Text(
            text = row.title,
            style = TextStyle(color = fixed(LedgerInk), fontSize = 16.sp),
            maxLines = 2,
            modifier = GlanceModifier
                .defaultWeight()
                .padding(horizontal = 4.dp)
                .clickable(
                    actionStartActivity<TaskEditActivity>(
                        parameters = actionParametersOf(ActionParameters.Key<Long>(TaskEditActivity.EXTRA_TASK_ID) to row.id)
                    )
                )
        )
        if (row.isMaybe) {
            Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.size(44.dp)) {
                Text("?", style = TextStyle(color = fixed(LedgerMuted), fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = GlanceModifier.size(44.dp)
                    .clickable(actionRunCallback<ToggleStarAction>(actionParametersOf(taskIdKey to row.id)))
            ) {
                Text(
                    text = if (row.isStarred) "★" else "☆",
                    style = TextStyle(color = fixed(if (row.isStarred) LedgerStar else LedgerMuted), fontSize = 24.sp)
                )
            }
        }
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
