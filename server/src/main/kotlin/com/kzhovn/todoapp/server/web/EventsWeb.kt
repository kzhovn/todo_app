package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.CalendarEvent
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.cancelledEvents
import com.kzhovn.todoapp.data.dueText
import com.kzhovn.todoapp.data.eventWhen
import com.kzhovn.todoapp.data.eventLinkCandidates
import com.kzhovn.todoapp.data.nearbyFirst
import com.kzhovn.todoapp.data.linkedToEvent
import com.kzhovn.todoapp.data.nowAndNext
import com.kzhovn.todoapp.data.nowNextTail
import com.kzhovn.todoapp.data.snoozeEvents
import com.kzhovn.todoapp.data.timeText
import com.kzhovn.todoapp.server.TaskService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ASIDE
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.summary
import java.net.URLEncoder

// Google Calendar on the web (docs/superpowers/specs/2026-10-18-google-calendar-design.md): an event's sheet in the
// detail panel, Doing's Now/Next and Cancelled lines, and snoozing until an event's over. Event ids carry / and @,
// so they go in the query (?id=).

internal fun eventHref(eventId: String, q: String) = "/event?id=${enc(eventId)}&mode=$q"
private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

internal fun calendarColor(service: TaskService, calendarId: String): String = service.calendar().calendar(calendarId)?.color ?: "var(--accent)"

internal fun FlowContent.calDot(color: String) = span(classes = "cal-dot") { style = "background: $color" }

private fun HTML.eventPage(service: TaskService, list: ListData, event: CalendarEvent) =
    shellPage(service, "Raspberry · ${event.title}", list.path, list, detail = { eventPanel(service, list, event) }) { listColumn(list) }

// When, its calendar, the tasks before and after it, and the editor's add field to link more (new or existing).
private fun ASIDE.eventPanel(service: TaskService, list: ListData, event: CalendarEvent) {
    a(href = list.path, classes = "detail-close") { attributes["aria-label"] = "Close"; +"✕" }
    val q = list.q
    div(classes = "event-sheet") {
        h2 { calDot(calendarColor(service, event.calendarId)); +" ${event.title}" }
        p(classes = "hint") { +eventWhen(event, service.now()) }
        service.calendar().calendar(event.calendarId)?.let { p(classes = "hint") { +it.name } }
        val all = service.tasks()
        val item = all.firstOrNull { it.type == TaskType.EVENT && it.eventId == event.id }
        val links = item?.let { linkedToEvent(it.id, all, service.dependencyEdges()) }
        val dues = service.dueDates()
        fun FlowContent.side(title: String, tasks: List<Task>) {
            if (tasks.isEmpty()) return
            div(classes = "field-label section") { +title }
            tasks.forEach { t ->
                div(classes = "rel-row") {
                    a(href = "/tasks/${t.id}?mode=$q", classes = if (t.isComplete) "done" else null) { openInPanel(); +t.title }
                    dues.at(t)?.let { span(classes = "tail") { +" · ${dueText(it, service.now())}" } }
                }
            }
        }
        links?.let { side(Labels.BEFORE_IT, it.before); side(Labels.AFTER_IT, it.after) }
        addRelatedForm(
            "/event/add?id=${enc(event.id)}&mode=$q", "/event/suggest?id=${enc(event.id)}", "#detail",
            listOf("before" to Labels.BEFORE_IT, "after" to Labels.AFTER_IT), kind = null, focus = false
        )
        event.link?.let { a(href = it, classes = "add-link") { attributes["target"] = "_blank"; attributes["rel"] = "noopener"; +"${Labels.OPEN_IN_GOOGLE} ↗" } }
    }
}

// Doing's quiet lines: what's on now and next today, and cancelled events still to deal with.
internal fun FlowContent.eventLines(service: TaskService, data: ListData) {
    val q = data.q
    val upcoming = nowAndNext(data.shownEvents, data.now)
    if (upcoming.isNotEmpty()) div(classes = "running-section today-events") {
        upcoming.forEach { e ->
            div(classes = "running-row") {
                span(classes = "running-label") { +Labels.TODAY_EVENTS }
                calDot(calendarColor(service, e.calendarId))
                a(href = eventHref(e.id, q), classes = "edit") { openInPanel(); +"${e.title} ${timeText(e.start)}" }
                span(classes = "left") { +nowNextTail(e, data.now) }
            }
        }
    }
    val cancelled = cancelledEvents(data.all)
    if (cancelled.isNotEmpty()) div(classes = "running-section cancelled-events") {
        val edges = service.dependencyEdges()
        cancelled.forEach { item ->
            val linked = linkedToEvent(item.id, data.all, edges).all
            div(classes = "running-row") {
                span(classes = "running-label") { +Labels.CANCELLED }
                span { +item.title }
                span(classes = "left") { +linked.joinToString(", ") { it.title }.ifEmpty { Labels.eventLinks(0) } }
                details(classes = "pp") {
                    summary(classes = "defer") { +"⋯" }
                    div(classes = "pop") {
                        button(classes = "menu-item") { hx("post", "/event-item/${item.id}/keep?mode=$q"); +Labels.KEEP_THEM }
                        // Link to another event: the next upcoming ones, soonest first.
                        snoozeEvents(data.shownEvents, data.now).take(6).forEach { e ->
                            button(classes = "menu-item") { hx("post", "/event-item/${item.id}/relink?event=${enc(e.id)}&mode=$q"); +"→ ${e.title} ${timeText(e.start)}" }
                        }
                        button(classes = "menu-item danger") {
                            hx("post", "/event-item/${item.id}/delete?mode=$q")
                            attributes["hx-confirm"] = "${Labels.DELETE_THEM}: ${linked.joinToString(", ") { it.title }}?"
                            +Labels.DELETE_THEM
                        }
                    }
                }
            }
        }
    }
}

fun Route.eventRoutes(service: TaskService) {
    get("/event") {
        val event = call.request.queryParameters["id"]?.let(service::event) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondHtml { eventPage(service, call.listData(service, call.mode()), event) }
    }
    // The sheet's add: a picked task, or a new one in quick add's syntax, linked before or after the event.
    post("/event/add") {
        val event = call.request.queryParameters["id"]?.let(service::event) ?: return@post call.respond(HttpStatusCode.NotFound)
        val p = call.receiveParameters()
        val taskId = p["existing"]?.toLongOrNull() ?: p["text"]?.takeIf { it.isNotBlank() }?.let { service.addTyped(it, folder = service.defaultFolderId())?.id }
        taskId?.let { service.linkEvent(it, event.id, before = p["kind"] != "after") }
        call.respondHtml { eventPage(service, call.listData(service, call.mode()), event) }
    }
    // Its suggestions, as the editor's: the nearest few when empty, else what matches.
    get("/event/suggest") {
        val event = call.request.queryParameters["id"]?.let(service::event) ?: return@get call.respond(HttpStatusCode.NotFound)
        val q = call.request.queryParameters["text"].orEmpty().trim()
        val all = service.tasks()
        val (item, candidates) = eventLinkCandidates(event, call.request.queryParameters["kind"] != "after", all, service.dependencyEdges())
        val sections = if (q.isEmpty()) nearbyFirst(item, candidates, all, service.now()).filter { it.first != Labels.EVERYTHING_ELSE }.map { (label, ts) -> label to ts.take(5) }
            else listOf(null to candidates.filter { it.title.contains(q, ignoreCase = true) }.take(8))
        call.respondText(createHTML().div(classes = "suggest") { suggestTasks(q, "task", sections, all.associateBy { it.id }) }, ContentType.Text.Html)
    }
    // A row's "After…" tile: the events to snooze until after, fetched when it's opened.
    get("/tasks/{id}/snooze-events") {
        val id = call.taskId() ?: return@get
        val q = call.request.queryParameters["mode"] ?: ListMode.DOING.name
        call.respondText(createHTML().div(classes = "snooze-events") {
            val events = snoozeEvents(service.shownEvents(), service.now())
            if (events.isEmpty()) p(classes = "hint") { +Labels.NO_CALENDAR.takeIf { service.calendar().calendars.isEmpty() }.orEmpty() }
            events.forEach { e ->
                button(classes = "menu-row") {
                    hx("post", "/tasks/$id/snooze?event=${enc(e.id)}&mode=$q")
                    calDot(calendarColor(service, e.calendarId))
                    +" ${e.title}"
                    span(classes = "tail") { +" until ${timeText(e.end)}" }
                }
            }
        }, ContentType.Text.Html)
    }
    post("/event-item/{id}/keep") { call.taskId()?.let(service::keepEventTasks); call.respondList(service, call.mode()) }
    // Deleted with the event's item, under one stamp, so Undo brings them all back.
    post("/event-item/{id}/delete") {
        val q = call.listQuery()
        val at = call.taskId()?.let(service::deleteEventTasks)
        call.respondList(service, call.mode(), extra = at?.let {
            createHTML().div {
                id = "toast"; attributes["hx-swap-oob"] = "true"; attributes["class"] = "show"
                span { +Labels.DELETE_THEM }
                button { hx("post", "/event-item/restore?at=$it&mode=$q"); +Labels.UNDO }
            }
        })
    }
    post("/event-item/restore") {
        call.request.queryParameters["at"]?.toLongOrNull()?.let(service::restoreDeletedAt)
        call.respondList(service, call.mode(), extra = createHTML().div { id = "toast"; attributes["hx-swap-oob"] = "true" })
    }
    post("/event-item/{id}/relink") {
        val id = call.taskId()
        val event = call.request.queryParameters["event"]
        if (id != null && event != null) service.relinkEvent(id, event)
        call.respondList(service, call.mode())
    }
}
