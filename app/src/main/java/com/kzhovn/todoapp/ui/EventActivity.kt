package com.kzhovn.todoapp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.AppSettings
import com.kzhovn.todoapp.CalendarCache
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.CalendarEvent
import com.kzhovn.todoapp.data.EventLinks
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.defaultFolder
import com.kzhovn.todoapp.data.dueText
import com.kzhovn.todoapp.data.eventWhen
import com.kzhovn.todoapp.data.DueDates
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

// An event's sheet (docs/superpowers/specs/2026-10-18-google-calendar-design.md): when and where, its calendar, the
// tasks linked before and after it, a quick add that links what's typed (Before or After), and Open in Google
// Calendar. Opened from the calendar, Doing's Today line and the editor's Related section.
class EventActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        val event = CalendarCache.events(this).firstOrNull { it.id == intent.getStringExtra(EXTRA_EVENT_ID) } ?: return finish()
        setContent {
            LedgerTheme {
                val scope = rememberCoroutineScope()
                var links by remember { mutableStateOf<EventLinks?>(null) }
                var dues by remember { mutableStateOf<DueDates?>(null) }
                var version by remember { mutableIntStateOf(0) }
                LaunchedEffect(version) {
                    links = app.repository.eventLinks(event.id)
                    dues = DueDates(app.repository.getAllTasks(), app.database.taskDao().getAllDependencies())
                }
                val open: (Long) -> Unit = { startActivity(Intent(this, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_TASK_ID, it)) }
                ModalBottomSheet(onDismissRequest = ::finish, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LedgerSearchBackground) {
                    EventSheet(event, links, dues, onOpen = open, onAdd = { text, before ->
                        scope.launch {
                            val now = System.currentTimeMillis()
                            val add = app.repository.planQuickAdd(text, AppSettings.rolloverHour(this@EventActivity), now)
                            val home = defaultFolder(app.repository.getAllTasks(), AppSettings.modeFolderId(this@EventActivity))?.id
                            app.repository.quickAdd(add, home, now)?.let { app.repository.linkEvent(it, event, before, now) }
                            version++
                        }
                    }, onGoogle = { link -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) })
                }
            }
        }
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        fun intent(context: Context, eventId: String): Intent = Intent(context, EventActivity::class.java).putExtra(EXTRA_EVENT_ID, eventId)
    }
}

// A calendar's colour, as Google gives it ("#7986cb"); the accent when it has none.
fun calendarColor(context: Context, calendarId: String): Color =
    CalendarCache.data(context).calendar(calendarId)?.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: LedgerAccent

@Composable
fun CalendarDot(color: Color, size: Int = 8) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

@Composable
private fun EventSheet(event: CalendarEvent, links: EventLinks?, dues: DueDates?, onOpen: (Long) -> Unit, onAdd: (String, Boolean) -> Unit, onGoogle: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val now = System.currentTimeMillis()
    var text by remember { mutableStateOf("") }
    var before by remember { mutableStateOf(true) }
    Column(Modifier.imePadding().padding(horizontal = 18.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CalendarDot(calendarColor(context, event.calendarId), 10)
            Text(event.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = LedgerInk, modifier = Modifier.padding(start = 8.dp))
        }
        Text(eventWhen(event, now), fontSize = 13.sp, color = LedgerMuted, modifier = Modifier.padding(top = 4.dp))
        val where = listOfNotNull(event.location, CalendarCache.data(context).calendar(event.calendarId)?.name).joinToString(" · ")
        if (where.isNotEmpty()) Text(where, fontSize = 13.sp, color = LedgerMuted)
        @Composable
        fun Side(title: String, tasks: List<Task>) {
            if (tasks.isEmpty()) return
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LedgerInk, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
            tasks.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(vertical = 9.dp)) {
                    Text(t.title, fontSize = 15.sp, color = if (t.isComplete) LedgerMuted else LedgerInk, modifier = Modifier.weight(1f))
                    dues?.at(t)?.let { Text(dueText(it, now), fontSize = 12.sp, color = LedgerMuted) }
                }
                HorizontalDivider(color = LedgerBorder)
            }
        }
        links?.let { Side(Labels.BEFORE_IT, it.before); Side(Labels.AFTER_IT, it.after) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
            Row(Modifier.border(1.dp, LedgerBorder, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))) {
                listOf(true to Labels.BEFORE, false to Labels.AFTER).forEach { (side, label) ->
                    Text(
                        label, fontSize = 13.sp, color = if (before == side) LedgerAccentInk else LedgerMuted,
                        modifier = Modifier.background(if (before == side) LedgerAccent else Color.Transparent).clickable { before = side }.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("+ " + Labels.ADD_FOR_EVENT) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { onAdd(text, before); text = "" } }),
                modifier = Modifier.weight(1f)
            )
        }
        event.link?.let { link ->
            Text("${Labels.OPEN_IN_GOOGLE} ↗", fontSize = 14.sp, color = LedgerAccent, modifier = Modifier.padding(top = 14.dp).clickable { onGoogle(link) })
        }
    }
}
