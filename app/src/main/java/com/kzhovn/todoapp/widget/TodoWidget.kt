package com.kzhovn.todoapp.widget

import androidx.compose.ui.graphics.Color
import com.kzhovn.todoapp.data.folderColorsArgb
import com.kzhovn.todoapp.data.isUnder
import com.kzhovn.todoapp.data.subtaskCounts
import androidx.glance.appwidget.updateAll
import androidx.glance.text.TextDecoration
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.kzhovn.todoapp.sync.SyncWorker
import com.kzhovn.todoapp.sync.SyncSettings
import com.kzhovn.todoapp.R
import androidx.glance.ImageProvider
import androidx.glance.Image
import com.kzhovn.todoapp.data.formatDuration
import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.notifications.TaskTimer
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
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.MainActivity
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.quickadd.QuickAddActivity
import com.kzhovn.todoapp.repository.dayOfWeekMask
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.repository.minuteOfDay
import com.kzhovn.todoapp.ui.TaskEditActivity
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted

val taskIdKey = ActionParameters.Key<Long>("task_id")

enum class WidgetMode(val label: String) { DOING("Doing"), ACTIVE("Active"), ALL("All") }

val WIDGET_MODE_KEY = stringPreferencesKey("mode")
val WIDGET_FOLDER_KEY = longPreferencesKey("folder")
// Rows whose subtasks/items are expanded in place, per widget.
val WIDGET_EXPANDED_KEY = stringSetPreferencesKey("expanded")

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
        suspend fun load(): Pair<List<WidgetTaskRow>, String> {
            val now = System.currentTimeMillis()
            repository.purgeExpired(now)
            val allTasks = repository.getAllTasks()
            val allById = allTasks.associateBy { it.id }
            val contextsByTaskId = repository.getAllTaskContexts()
            val tasks = when (mode) {
                WidgetMode.ALL -> TodoWidgetPresenter.allOpen(allTasks, allById)
                else -> {
                    val active = repository.getActiveTasksFrom(allTasks, contextsByTaskId, now, minuteOfDay(now), dayOfWeekMask(now))
                    if (mode == WidgetMode.ACTIVE) active
                    else filterDoing(active, now, allById, contextsByTaskId)
                }
            }.filter { folderId == null || isUnder(it, folderId, allById) }
            val folderName = folderId?.let { allById[it]?.title }
            val effectiveDue = { t: Task -> resolveEffective(t, allById, contextsByTaskId).effectiveDueDate }
            return TodoWidgetPresenter.toRows(tasks, subtaskCounts(allTasks), now, allById, folderColorsArgb(allTasks), effectiveDue, urgentOnTop = mode != WidgetMode.ALL) to
                mode.label.uppercase() + folderName?.let { " · $it" }.orEmpty()
        }
        val initial = load()
        // Reloaded whenever a task, context or dependency changes. A widget's session outlives a single
        // update(), which only recomposes it, so data loaded once would go stale.
        val updates = (context.applicationContext as TodoApp).listInputChanges().map { load() }

        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // The data Uri keeps each widget's PendingIntent distinct, so every widget opens its own settings.
        val configIntent = Intent(context, WidgetConfigActivity::class.java)
            .setData(Uri.parse("todowidget://config/$appWidgetId"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

        // CLEAR_TOP|SINGLE_TOP reuses a running app screen (delivered to onNewIntent) instead of just
        // bringing it forward on whatever tab it was showing.
        val openListIntent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("todowidget://open/$appWidgetId"))
            .putExtra(MainActivity.EXTRA_MODE, mode.name)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        provideContent {
            val (rows, header) = updates.collectAsState(initial).value
            Column(modifier = GlanceModifier.fillMaxSize().background(fixed(LedgerBackground)).padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp)) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Opens the app on the same list; the widget's modes match the app's tabs by name.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = GlanceModifier
                            .size(32.dp)
                            .clickable(actionStartActivity(openListIntent))
                    ) {
                        Text("☰", style = TextStyle(color = fixed(LedgerAccent), fontSize = 20.sp, fontWeight = FontWeight.Bold))
                    }
                    Text(
                        text = header,
                        style = TextStyle(color = fixed(LedgerMuted), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(configIntent))
                    )
                    // Syncs now; the list redraws on its own once the pull lands (see `updates` above).
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = GlanceModifier.size(32.dp).clickable(actionRunCallback<SyncNowAction>())
                    ) {
                        Image(ImageProvider(R.drawable.widget_refresh), contentDescription = "Sync now", modifier = GlanceModifier.size(18.dp))
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = GlanceModifier
                            .size(32.dp)
                            .cornerRadius(16.dp)
                            .background(fixed(LedgerAccentSoft))
                            .clickable(
                                // From a Doing widget, new tasks start starred so they show up right there.
                                actionStartActivity<QuickAddActivity>(
                                    actionParametersOf(
                                        ActionParameters.Key<Long>(QuickAddActivity.EXTRA_FOLDER_ID) to (folderId ?: 0L),
                                        ActionParameters.Key<Boolean>(QuickAddActivity.EXTRA_STARRED) to (mode == WidgetMode.DOING)
                                    )
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
                    val expanded = currentState(WIDGET_EXPANDED_KEY).orEmpty()
                    // An expanded row's children follow it. Their item ids are negated: a subtask can also be a
                    // row of its own in Doing, and ids must be unique in the list.
                    val entries = rows.flatMap { row ->
                        listOf(row.id to row) + if (row.id.toString() in expanded) row.children.map { -it.id to (row to it) } else emptyList()
                    }
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(entries, itemId = { it.first }) { (_, entry) ->
                            when (entry) {
                                is WidgetTaskRow -> WidgetRow(entry, entry.id.toString() in expanded)
                                is Pair<*, *> -> WidgetChildRow(entry.first as WidgetTaskRow, entry.second as WidgetChild)
                            }
                        }
                    }
                }
            }
        }
    }
}

// The check/star glyphs are large (they're what you tap) but their boxes hug them, so the
// buttons stay easy to hit without adding whitespace around the title.
@androidx.compose.runtime.Composable
private fun WidgetRow(row: WidgetTaskRow, expanded: Boolean) {
    val toggleExpanded = actionRunCallback<ToggleExpandedAction>(actionParametersOf(taskIdKey to row.id))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
        // The folder colour bar, like the app's rows; row height is fixed by the 30dp tap boxes.
        Box(GlanceModifier.width(4.dp).height(30.dp).background(fixed(row.barColor?.let(::Color) ?: LedgerBorder))) {}
        // A checklist: "5/8" in the checkbox's place, tapped to show its items in place.
        if (row.isChecklist) Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.size(width = 36.dp, height = 30.dp).clickable(toggleExpanded)) {
            val (done, total) = row.subtasks ?: (0 to 0)
            Text("$done/$total", style = TextStyle(color = fixed(LedgerAccent), fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        } else Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier.size(width = 31.dp, height = 30.dp)
                .clickable(actionRunCallback<ToggleCompleteAction>(actionParametersOf(taskIdKey to row.id)))
        ) {
            // Due today: an orange ring with a pale orange fill; overdue: rust. Drawn as vectors, since a
            // text glyph can only take one colour.
            if (row.isComplete) {
                Text("✓", style = TextStyle(color = fixed(LedgerAccent), fontSize = 24.sp))
            } else {
                val ring = when (row.due) {
                    DueStatus.OVERDUE -> R.drawable.widget_check_overdue
                    DueStatus.TODAY -> R.drawable.widget_check_today
                    else -> R.drawable.widget_check
                }
                Image(ImageProvider(ring), contentDescription = "Complete", modifier = GlanceModifier.size(21.dp))
            }
        }
        // Glance text can't mix colours, so a subtask's dimmer "Parent: " is its own Text, capped so a
        // long parent name can't crowd out the subtask's own title.
        // Tapping the parent opens the parent, as tapping the title opens the subtask.
        row.parentTitle?.let { parent ->
            Text(
                text = (if (parent.length > 22) parent.take(21) + "…" else parent) + ":",
                style = TextStyle(color = fixed(LedgerMuted), fontSize = 14.sp),
                maxLines = 1,
                modifier = GlanceModifier.padding(start = 2.dp).clickable(
                    actionStartActivity<TaskEditActivity>(
                        parameters = actionParametersOf(ActionParameters.Key<Long>(TaskEditActivity.EXTRA_TASK_ID) to (row.parentId ?: 0L))
                    )
                )
            )
        }
        Text(
            text = row.title,
            // Glance has no alpha, so a backburner row is dimmed with the muted colour instead.
            style = TextStyle(color = fixed(if (row.isBackburner) LedgerMuted else LedgerInk), fontSize = 14.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier
                .defaultWeight()
                // The ○ glyph sits a little below its box's centre; this nudges the title (centred in
                // the row) down 1dp to line up with it.
                .padding(start = 2.dp, end = 2.dp, top = 2.dp)
                .clickable(
                    actionStartActivity<TaskEditActivity>(
                        parameters = actionParametersOf(ActionParameters.Key<Long>(TaskEditActivity.EXTRA_TASK_ID) to row.id)
                    )
                )
        )
        // "▶ 1h", like the app's timer pill.
        row.durationMinutes?.let { minutes ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = GlanceModifier.height(30.dp).padding(horizontal = 4.dp)
                    .clickable(actionRunCallback<StartTimerAction>(actionParametersOf(taskIdKey to row.id)))
            ) {
                Text("▶ ${formatDuration(minutes)}", style = TextStyle(color = fixed(LedgerAccent), fontSize = 12.sp, fontWeight = FontWeight.Medium))
            }
        }
        // A task's subtask count doubles as the toggle that shows them in place, like a checklist's.
        if (!row.isChecklist) row.subtasks?.let { (done, total) ->
            Text(
                "$done/$total " + if (expanded) "▴" else "▾",
                style = TextStyle(color = fixed(LedgerMuted), fontSize = 11.sp), maxLines = 1,
                modifier = GlanceModifier.height(30.dp).padding(horizontal = 3.dp, vertical = 8.dp).clickable(toggleExpanded)
            )
        }
        if (row.isMaybe) {
            Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.size(width = 34.dp, height = 30.dp)) {
                Text("?", style = TextStyle(color = fixed(LedgerMuted), fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = GlanceModifier.size(width = 31.dp, height = 30.dp)
                    .clickable(actionRunCallback<ToggleStarAction>(actionParametersOf(taskIdKey to row.id)))
            ) {
                Image(
                    ImageProvider(if (row.isStarred) R.drawable.widget_star_on else R.drawable.widget_star),
                    contentDescription = if (row.isStarred) "Starred" else "Star",
                    modifier = GlanceModifier.size(23.dp)
                )
            }
        }
    }
}

// An expanded row's subtask or item: indented under it, ticked in place; checked ones faded and struck through.
@androidx.compose.runtime.Composable
private fun WidgetChildRow(parent: WidgetTaskRow, child: WidgetChild) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
        Box(GlanceModifier.width(4.dp).height(26.dp).background(fixed(parent.barColor?.let(::Color) ?: LedgerBorder))) {}
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier.padding(start = 14.dp).size(width = 28.dp, height = 26.dp)
                .clickable(actionRunCallback<ToggleItemAction>(actionParametersOf(taskIdKey to child.id)))
        ) {
            if (child.isComplete) Text("✓", style = TextStyle(color = fixed(LedgerMuted), fontSize = 16.sp))
            else Image(ImageProvider(R.drawable.widget_check), contentDescription = "Check", modifier = GlanceModifier.size(16.dp))
        }
        Text(
            child.title,
            style = TextStyle(
                color = fixed(if (child.isComplete) LedgerMuted else LedgerInk), fontSize = 13.sp,
                textDecoration = if (child.isComplete) TextDecoration.LineThrough else TextDecoration.None
            ),
            maxLines = 1, modifier = GlanceModifier.defaultWeight().padding(start = 2.dp, end = 6.dp)
        )
    }
}

class ToggleExpandedAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[taskIdKey]?.toString() ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            val open = prefs[WIDGET_EXPANDED_KEY].orEmpty()
            prefs[WIDGET_EXPANDED_KEY] = if (id in open) open - id else open + id
        }
        TodoWidget().update(context, glanceId)
    }
}

// Ticks just this subtask/item (no cascade, and checking a checklist's last item completes nothing).
class ToggleItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        (context.applicationContext as TodoApp).repository.toggleComplete(parameters[taskIdKey] ?: return, System.currentTimeMillis())
    }
}

class SyncNowAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (SyncSettings.config(context) != null) SyncWorker.requestSoon(context, delaySeconds = 0)
        // Redraw every widget from fresh data now, too; whatever the sync brings in redraws them again.
        TodoWidget().updateAll(context)
    }
}

class StartTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val task = (context.applicationContext as TodoApp).repository.getTask(parameters[taskIdKey] ?: return) ?: return
        task.durationMinutes?.let { TaskTimer.start(context, task, it) }
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
    }
}

class ToggleStarAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[taskIdKey] ?: return
        val repository = (context.applicationContext as TodoApp).repository
        repository.toggleStar(taskId)
    }
}
