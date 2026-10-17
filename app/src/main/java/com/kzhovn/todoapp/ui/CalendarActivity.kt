package com.kzhovn.todoapp.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kzhovn.todoapp.AppSettings
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.CalendarEntry
import com.kzhovn.todoapp.data.CompleteQuestion
import com.kzhovn.todoapp.data.DateKind
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.agenda
import com.kzhovn.todoapp.data.calendarEntries
import com.kzhovn.todoapp.data.dayStart
import com.kzhovn.todoapp.data.gridRange
import com.kzhovn.todoapp.data.localDay
import com.kzhovn.todoapp.data.nearestFolder
import com.kzhovn.todoapp.data.subtaskParentTitle
import com.kzhovn.todoapp.quickadd.QuickAddActivity
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerCheckBorder
import com.kzhovn.todoapp.ui.theme.LedgerGood
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerStar
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.ui.theme.LedgerUp
import com.kzhovn.todoapp.ui.theme.folderColors
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

// The calendar (docs/superpowers/specs/2026-10-16-calendar-mockups.html): a month grid with a dot per kind of
// date on each day, over the agenda (core's agenda(), as on the web). Tapping a day shows just that day; tapping
// it again, or All upcoming, goes back. The rows act as Doing's do, through the lists' own view model.
class CalendarActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        setContent {
            LedgerTheme {
                val viewModel = remember { TaskListViewModel(app.repository, app.contextRepository, app.listInputChanges(), modeFolderId = { AppSettings.modeFolderId(app) }) }
                // Every task, done ones included (the All tab's load); resuming reloads, as time passing moves "today".
                LifecycleResumeEffect(Unit) { viewModel.load(TaskListMode.ALL); onPauseOrDispose { } }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var completeDecision by remember { mutableStateOf<Pair<Long, CompleteQuestion>?>(null) }
                val open: (Long) -> Unit = { id -> startActivity(Intent(this, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_TASK_ID, id)) }
                AppDrawer(drawerState, calendarSelected = true) {
                    CalendarScreen(
                        viewModel,
                        reminderHour = AppSettings.reminderHour(app),
                        kindsAtStart = AppSettings.calendarKinds(app),
                        onKinds = { AppSettings.setCalendarKinds(app, it) },
                        onMenu = { scope.launch { drawerState.open() } },
                        onOpen = open,
                        onCheck = { id -> viewModel.requestComplete(id) { taskId, question -> completeDecision = taskId to question } },
                        onAdd = { day -> startActivity(Intent(this, QuickAddActivity::class.java).putExtra(QuickAddActivity.EXTRA_DUE, dayStart(day))) }
                    )
                }
                completeDecision?.let { (taskId, question) ->
                    CompleteSubtasksDialog(
                        question,
                        onCompleteAll = { viewModel.completeChoosing(taskId, moveOut = false); completeDecision = null },
                        onMoveOut = { viewModel.completeChoosing(taskId, moveOut = true); completeDecision = null },
                        onDismiss = { completeDecision = null }
                    )
                }
            }
        }
    }
}

// Each kind's colour, as the web's chips and grid have them; a passed due date takes the overdue colour.
private fun kindColor(kind: DateKind, overdue: Boolean = false): Color = when (kind) {
    DateKind.DUE -> if (overdue) LedgerOverdue else LedgerAccent
    DateKind.START -> LedgerGood
    DateKind.REPEAT -> LedgerMuted
    DateKind.WAITING -> LedgerUp
    DateKind.REMINDER -> LedgerStar
    DateKind.DONE -> LedgerCheckBorder
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarScreen(
    viewModel: TaskListViewModel,
    reminderHour: Int,
    kindsAtStart: Set<DateKind>,
    onKinds: (Set<DateKind>) -> Unit,
    onMenu: () -> Unit,
    onOpen: (Long) -> Unit,
    onCheck: (Long) -> Unit,
    onAdd: (LocalDate) -> Unit
) {
    val tasks by viewModel.tasks.collectAsState()
    val allById by viewModel.allById.collectAsState()
    val dues by viewModel.dues.collectAsState()
    val subtaskCounts by viewModel.subtaskCounts.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val colors = remember(allById) { folderColors(allById.values) }
    val scope = rememberCoroutineScope()
    var kinds by remember { mutableStateOf(kindsAtStart) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val now = System.currentTimeMillis()
    val today = localDay(now)
    val state = rememberCalendarState(
        startMonth = YearMonth.from(today).minusMonths(120), endMonth = YearMonth.from(today).plusMonths(120),
        firstVisibleMonth = YearMonth.from(today), firstDayOfWeek = DayOfWeek.MONDAY
    )
    val month = state.firstVisibleMonth.yearMonth
    // Another month: its own agenda, from its 1st.
    LaunchedEffect(month) { if (picked?.let(YearMonth::from) != month) picked = null }
    // The dots for the month shown and the ones either side, so a swipe doesn't land on a bare grid.
    val dots = remember(tasks, kinds, month, mode) {
        calendarEntries(tasks, gridRange(month.minusMonths(1)).first, gridRange(month.plusMonths(1)).second, kinds, now, reminderHour, mode?.id)
            .groupBy({ it.day }, { kindColor(it.kind, it.overdue) to (it.kind == DateKind.REPEAT) }).mapValues { it.value.distinct() }
    }
    val days = remember(tasks, kinds, month, picked, mode) { agenda(tasks, month, picked, kinds, now, reminderHour, mode?.id) }

    LazyColumn(Modifier.fillMaxSize().background(LedgerBackground)) {
        item(key = "top") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp, top = 4.dp)) {
                IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                Text(Labels.CALENDAR, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerInk)
                mode?.let { Text(it.title, fontSize = 13.sp, color = LedgerMuted, modifier = Modifier.padding(start = 8.dp)) }
                Spacer(Modifier.weight(1f))
                StepButton("‹") { scope.launch { state.animateScrollToMonth(month.minusMonths(1)) } }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM", Locale.US)), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = LedgerInk, modifier = Modifier.padding(horizontal = 4.dp))
                StepButton("›") { scope.launch { state.animateScrollToMonth(month.plusMonths(1)) } }
                Text(
                    Labels.TODAY, fontSize = 13.sp, color = LedgerAccent,
                    modifier = Modifier.padding(start = 6.dp).border(1.dp, LedgerBorder, RoundedCornerShape(6.dp))
                        .clickable { picked = null; scope.launch { state.animateScrollToMonth(YearMonth.from(today)) } }.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                DateKind.entries.forEach { kind ->
                    KindChip(kind, on = kind in kinds) { kinds = if (kind in kinds) kinds - kind else kinds + kind; onKinds(kinds) }
                }
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                DayOfWeek.entries.forEach { d -> Text(d.name.take(1) + d.name.drop(1).take(2).lowercase(), fontSize = 11.sp, color = LedgerMuted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
            HorizontalCalendar(state = state, modifier = Modifier.padding(horizontal = 10.dp), dayContent = { day ->
                DayCell(day, dots[day.date].orEmpty(), isToday = day.date == today, isPicked = day.date == picked) {
                    picked = if (picked == day.date) null else day.date
                }
            })
            HorizontalDivider(color = LedgerBorder, modifier = Modifier.padding(top = 6.dp))
        }
        days.forEachIndexed { i, day ->
            item(key = "head-${day.day}") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 4.dp)) {
                    Text(
                        Labels.agendaHeading(day.day, today), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = when (day.day) { null -> LedgerOverdue; today -> LedgerInk; else -> LedgerMuted }, modifier = Modifier.weight(1f)
                    )
                    if (i == 0 && picked != null) Text(Labels.ALL_UPCOMING, fontSize = 13.sp, color = LedgerAccent, modifier = Modifier.clickable { picked = null }.padding(4.dp))
                }
            }
            if (day.entries.isEmpty()) item(key = "empty-${day.day}") {
                Text(Labels.NOTHING_THAT_DAY, fontSize = 14.sp, color = LedgerMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            items(day.entries, key = { "${day.day}-${it.task.id}" }) { e ->
                val barColor = nearestFolder(e.task, allById)?.let { colors[it.id] } ?: LedgerBorder
                // A repeat still to come isn't a task yet, and a done one stays done: plain rows that open the task.
                if (e.kind == DateKind.REPEAT || e.task.isComplete) QuietRow(e, barColor) { onOpen(e.task.id) }
                else Box(Modifier.alpha(if (e.task.isMaybe) 0.5f else 1f)) {
                    TaskRow(
                        e.task, dues.of(e.task), subtaskCounts[e.task.id], barColor, false, subtaskParentTitle(e.task, allById),
                        onCheck, viewModel::toggleStar, onOpen, viewModel::snooze, tag = Labels.dateTag(e)
                    )
                }
                HorizontalDivider(color = LedgerBorder)
            }
        }
        picked?.let { day ->
            item(key = "add") {
                Text(
                    "+ " + Labels.addTo(day), fontSize = 14.sp, color = LedgerMuted,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).border(1.dp, LedgerBorder, RoundedCornerShape(8.dp)).clickable { onAdd(day) }.padding(10.dp)
                )
            }
        }
        item(key = "end") { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) =
    Text(text, fontSize = 20.sp, color = LedgerMuted, modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 8.dp))

@Composable
private fun KindChip(kind: DateKind, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(shape).background(if (on) LedgerAccentSoft else Color.Transparent)
            .border(1.dp, if (on) LedgerAccent else LedgerBorder, shape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Dot(kindColor(kind), hollow = kind == DateKind.REPEAT)
        Spacer(Modifier.width(6.dp))
        Text(Labels.dateKind(kind), fontSize = 13.sp, color = if (on) LedgerAccent else LedgerMuted, fontWeight = if (on) FontWeight.SemiBold else null)
    }
}

@Composable
private fun Dot(color: Color, hollow: Boolean = false) = Box(
    Modifier.size(6.dp).clip(CircleShape).then(if (hollow) Modifier.border(1.5.dp, color, CircleShape) else Modifier.background(color))
)

// A day: its number (today's in a filled circle), and a dot per kind of date on it (a repeat's hollow). Days of the months either
// side are fainter, and still tappable.
@Composable
private fun DayCell(day: CalendarDay, dots: List<Pair<Color, Boolean>>, isToday: Boolean, isPicked: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.aspectRatio(1.3f).padding(1.dp).clip(RoundedCornerShape(6.dp))
            .background(if (isPicked) LedgerAccentSoft else Color.Transparent).clickable(onClick = onClick).padding(top = 4.dp)
            .alpha(if (day.position == DayPosition.MonthDate) 1f else 0.45f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(24.dp).clip(CircleShape).background(if (isToday) LedgerAccent else Color.Transparent)
        ) {
            Text(day.date.dayOfMonth.toString(), fontSize = 13.sp, color = if (isToday) LedgerAccentInk else LedgerInk, fontWeight = if (isToday) FontWeight.SemiBold else null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 3.dp)) { dots.take(4).forEach { (color, hollow) -> Dot(color, hollow) } }
    }
}

@Composable
private fun QuietRow(entry: CalendarEntry, barColor: Color, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clickable(onClick = onClick)) {
        Box(Modifier.width(4.dp).height(44.dp).background(barColor))
        Icon(if (entry.kind == DateKind.REPEAT) Icons.Filled.Repeat else Icons.Filled.Check, contentDescription = null, tint = LedgerMuted, modifier = Modifier.padding(horizontal = 12.dp).size(18.dp))
        Text(
            entry.task.title, fontSize = 16.sp, color = LedgerMuted, modifier = Modifier.weight(1f),
            textDecoration = if (entry.task.isComplete) TextDecoration.LineThrough else null
        )
        Labels.dateTag(entry)?.let { Text(it, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(end = 14.dp)) }
    }
}
