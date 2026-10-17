package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.CalendarEntry
import com.kzhovn.todoapp.data.DateKind
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.calendarEntries
import com.kzhovn.todoapp.data.localDay
import com.kzhovn.todoapp.server.QuickAddChips
import com.kzhovn.todoapp.server.TaskService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.textInput
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate

// The calendar (docs/superpowers/specs/2026-10-16-calendar-mockups.html): FullCalendar's month grid, which
// app.js sets up and feeds from /calendar/events, over the agenda, a list like Doing (ListMode.CALENDAR).
// Picking a day in the grid reloads the agenda with ?day=; the chips pick the kinds of date shown.

fun FlowContent.calendarColumn(data: ListData) {
    val cal = data.calendar!!
    div(classes = "list-head") {
        h2 {
            +Labels.CALENDAR
            data.modeFolder?.let { span(classes = "mode-name") { +it.title } }
        }
    }
    div { kindChips(cal, data.q) }
    script(src = asset(FULLCALENDAR)) {}
    div(classes = "calendar") { id = "calendar"; attributes["data-month"] = cal.month.toString() }
    div { listContents(data) }
}

private fun DIV.kindChips(cal: CalendarView, q: String) {
    id = "kinds"
    classes = setOf("kinds")
    DateKind.entries.forEach { kind ->
        val on = kind in cal.kinds
        button(classes = "pill kind k-${kind.name.lowercase()}" + if (on) " set" else "") {
            hx("post", "/calendar/kind/${kind.name.lowercase()}?mode=$q")
            attributes["aria-pressed"] = on.toString()
            span(classes = "dot") {}
            +Labels.dateKind(kind)
        }
    }
}

// Overdue (when the agenda takes in today), then each day; a picked day ends with a quick add due that day.
internal fun DIV.agendaContents(data: ListData) {
    val cal = data.calendar!!
    val today = localDay(data.now)
    if (data.agenda.isEmpty()) p(classes = "empty") { +Labels.NOTHING_HERE }
    data.agenda.forEachIndexed { i, day ->
        div(classes = "agenda-day" + when (day.day) { null -> " overdue"; today -> " today"; else -> "" }) {
            span { +Labels.agendaHeading(day.day, today) }
            if (i == 0 && cal.day != null) button(classes = "pill all-upcoming") { hx("get", "/list/calendar?month=${cal.month}"); +Labels.ALL_UPCOMING }
        }
        if (day.entries.isEmpty()) p(classes = "empty") { +Labels.NOTHING_THAT_DAY }
        // A repeat still to come isn't a task yet, and a done one stays done: plain rows that open the task.
        day.entries.forEach { e -> if (e.kind == DateKind.REPEAT || e.task.isComplete) quietRow(data, e) else taskRow(data, e.task, depth = 0, tag = Labels.dateTag(e)) }
    }
    cal.day?.let { day ->
        form(classes = "inline day-add") {
            hx("post", "/calendar/add?day=$day&mode=${data.q}")
            textInput(name = "text") { placeholder = Labels.addTo(day); attributes["autocomplete"] = "off" }
        }
    }
}

private fun FlowContent.quietRow(data: ListData, entry: CalendarEntry) = div(classes = "row quiet" + if (entry.task.isComplete) " done" else "") {
    attributes["data-task-id"] = entry.task.id.toString()
    style = "border-left-color: ${data.folderColor(entry.task) ?: "var(--border)"}"
    icon(if (entry.kind == DateKind.REPEAT) Icon.REPEAT else Icon.CHECK, "quiet-mark")
    div(classes = "main") {
        div(classes = "title") {
            a(href = "/tasks/${entry.task.id}?mode=${data.q}", classes = "edit") { openInPanel(); +entry.task.title }
            Labels.dateTag(entry)?.let { span(classes = "tail") { +" · $it" } }
        }
    }
}

fun Route.calendarRoutes(service: TaskService) {
    // The grid's dates, as FullCalendar's events: it asks with ?start=&end= (end not included), dates or times.
    get("/calendar/events") {
        fun day(name: String) = call.request.queryParameters[name]?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val from = day("start") ?: return@get call.respond(HttpStatusCode.BadRequest)
        val to = day("end")?.minusDays(1) ?: from
        val kinds = call.calendarView(ListMode.CALENDAR, service.now())!!.kinds
        // A task once a day, by its first kind, as in the agenda.
        val entries = calendarEntries(service.tasks(), from, to, kinds, service.now(), service.reminderHour(), service.modeFolderId()).distinctBy { it.task.id to it.day }
        val events = buildJsonArray {
            entries.forEach { e ->
                addJsonObject {
                    put("title", (if (e.task.isMaybe) "? " else "") + e.task.title)
                    put("start", e.day.toString())
                    put("allDay", true)
                    put("taskId", e.task.id.toString())
                    putJsonArray("classNames") {
                        add("k-" + if (e.overdue) "overdue" else e.kind.name.lowercase())
                        if (e.task.isMaybe) add("maybe")
                    }
                }
            }
        }
        call.respondText(events.toString(), ContentType.Application.Json)
    }
    // A chip: that kind on or off, for this browser. The chips come back beside the agenda.
    post("/calendar/kind/{kind}") {
        DateKind.entries.firstOrNull { it.name.equals(call.parameters["kind"], ignoreCase = true) }?.let { call.flipKind(it) }
        val cal = call.calendarView(ListMode.CALENDAR, service.now())!!
        call.respondList(service, ListMode.CALENDAR, extra = createHTML().div { attributes["hx-swap-oob"] = "true"; kindChips(cal, call.listQuery()) })
    }
    // A picked day's quick add: quick add's syntax, due that day.
    post("/calendar/add") {
        val day = call.request.queryParameters["day"]?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
        val text = call.receiveParameters()["text"].orEmpty()
        if (day != null && text.isNotBlank()) service.add(service.planQuickAdd(text, QuickAddChips(due = day)), defaultParent = service.defaultFolderId())
        call.respondList(service, ListMode.CALENDAR)
    }
}
