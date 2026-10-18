package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.CalendarCache
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.cancelledEvents
import com.kzhovn.todoapp.data.linkedToEvent
import com.kzhovn.todoapp.data.nowAndNext
import com.kzhovn.todoapp.data.nowNextTail
import com.kzhovn.todoapp.data.snoozeEvents
import com.kzhovn.todoapp.data.timeText
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerTile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Doing's quiet lines from Google Calendar, as the web's: what's on now and next today (tap: its sheet), and each
// cancelled event still to deal with (tap: keep its tasks, link them to another event, or delete them, with Undo).
@Composable
internal fun EventLines(all: Collection<Task>, edges: List<TaskDependency>) {
    val context = LocalContext.current
    val app = context.applicationContext as TodoApp
    val scope = rememberCoroutineScope()
    val version by CalendarCache.changes.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val events = remember(version) { CalendarCache.shownEvents(context) }
    val upcoming = nowAndNext(events, now)
    val cancelled = cancelledEvents(all)
    if (upcoming.isEmpty() && cancelled.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        upcoming.forEach { e ->
            QuietLine(Labels.TODAY_EVENTS, LedgerMuted, onClick = { context.startActivity(EventActivity.intent(context, e.id)) }) {
                CalendarDot(calendarColor(context, e.calendarId))
                Text("${e.title} ${timeText(e.start)}", fontSize = 13.sp, color = LedgerInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(nowNextTail(e, now), fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(end = 10.dp))
            }
        }
        cancelled.forEach { item ->
            var open by remember { mutableStateOf(false) }
            val linked = linkedToEvent(item.id, all, edges).all
            QuietLine(Labels.CANCELLED, LedgerOverdue, onClick = { open = true }) {
                Text(item.title, fontSize = 13.sp, color = LedgerInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                Text(linked.joinToString(", ") { it.title }, fontSize = 12.sp, color = LedgerMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 10.dp))
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(Labels.KEEP_THEM) }, onClick = { open = false; scope.launch { app.repository.keepEventTasks(item.id) } })
                    // Link to another event: the coming ones, soonest first.
                    snoozeEvents(events, now).take(6).forEach { e ->
                        DropdownMenuItem(text = { Text("→ ${e.title} ${timeText(e.start)}") }, onClick = {
                            open = false
                            scope.launch { app.repository.relinkEvent(item.id, e, System.currentTimeMillis()) }
                        })
                    }
                    DropdownMenuItem(text = { Text(Labels.DELETE_THEM, color = LedgerOverdue) }, onClick = { open = false; scope.launch { app.repository.deleteEventTasks(item.id) } })
                }
            }
        }
    }
}

@Composable
private fun QuietLine(label: String, labelColor: androidx.compose.ui.graphics.Color, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LedgerTile).clickable(onClick = onClick).padding(start = 10.dp, top = 8.dp, bottom = 8.dp)
) {
    Text(label.uppercase(), fontSize = 10.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.Bold, color = labelColor, modifier = Modifier.padding(end = 6.dp))
    content()
}
