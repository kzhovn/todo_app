package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.data.dueText
import com.kzhovn.todoapp.data.formatDuration
import com.kzhovn.todoapp.data.subtaskParentTitle
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.repository.nextFocusTasks
import com.kzhovn.todoapp.server.TaskService
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.textInput

// Focus on the web (docs/superpowers/specs/2026-09-27-focus-everywhere-design.md). A focus session is
// shared by every device: while one runs, every page here is the focus screen (see shellPage), with no
// lock. Leaving ends it everywhere and unpins.
fun Route.focusRoutes(service: TaskService) {
    // With no session, the "Focus on" picker; with one, shellPage shows the session instead.
    get("/focus") { call.respondHtml { shellPage(service, "Raspberry · Focus", "/focus") { div(classes = "focus-pick") { h2 { +"Focus on" }; focusPicker(service, finished = null) } } } }
    // The focus screen's own refresh: its task changed, done, or moved on elsewhere. Once the session
    // is over (left anywhere), back to the lists.
    get("/focus/body") {
        val session = service.focusSession() ?: return@get call.goTo("/doing")
        call.respondText(createHTML().div { focusBody(service, session) }, ContentType.Text.Html)
    }
    post("/focus/start") {
        call.request.queryParameters["task"]?.toLongOrNull()?.let(service::focus)
        call.goTo("/focus")
    }
    // A new task to focus on, from the picker.
    post("/focus/new") {
        service.quickAdd(call.receiveParameters()["text"].orEmpty(), fromDoing = false)?.let { service.focus(it.id) }
        call.goTo("/focus")
    }
    // Done: the session stays on the done task, so every device asks what's next.
    post("/focus/done") {
        service.focusSession()?.takeUnless { it.isComplete }?.let { service.completeWithDescendants(it.id) }
        val session = service.focusSession() ?: return@post call.goTo("/doing")
        call.respondText(createHTML().div { focusBody(service, session) }, ContentType.Text.Html)
    }
    // Ticking an item of the checklist in focus.
    post("/focus/item") {
        call.request.queryParameters["task"]?.toLongOrNull()?.let(service::get)?.let { if (it.isComplete) service.uncomplete(it.id) else service.complete(it.id) }
        val session = service.focusSession() ?: return@post call.goTo("/doing")
        call.respondText(createHTML().div { focusBody(service, session) }, ContentType.Text.Html)
    }
    post("/focus/leave") {
        service.unpin()
        call.goTo("/doing")
    }
}

// A full-page move, from htmx (HX-Redirect) or a plain form.
private suspend fun ApplicationCall.goTo(path: String) {
    if (request.headers["HX-Request"] != null) {
        response.header("HX-Redirect", path)
        respondText("")
    } else {
        respondRedirect(path)
    }
}

// Every page, while a session runs: just the task, big, with its timer, Done, quick add and Leave.
fun HTML.focusPage(service: TaskService, session: Task) = page("Raspberry · Focus") {
    div(classes = "focus-shell") { div { focusBody(service, session) } }
    div { id = "toast" }
    div { id = "timer"; attributes["hidden"] = "" }
}

fun DIV.focusBody(service: TaskService, session: Task) {
    id = "focus"
    classes = setOf("focus")
    // Follows the session from anywhere: a task done on the phone, the next one picked on the top bar.
    attributes["hx-get"] = "/focus/body"
    attributes["hx-trigger"] = "every 20s, visibilitychange from:document, refresh from:body"
    attributes["hx-swap"] = "outerHTML"
    if (session.isComplete) {
        h1(classes = "focus-title") { +"Done!" }
        p(classes = "focus-sub") { +"Focus on the next task, or finish?" }
        div(classes = "focus-pick") { focusPicker(service, finished = session) }
        button(classes = "leave") { hx("post", "/focus/leave", "this"); +"I'm done" }
        return
    }
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    div(classes = "focus-label") { +"Focus" }
    subtaskParentTitle(session, byId)?.let { p(classes = "focus-sub") { +it } }
    h1(classes = "focus-title") { +session.title }
    service.effectiveDueDate(session)?.let { p(classes = "focus-sub") { +dueText(it, service.now()) } }
    // The note: what to ask, the number to call.
    session.notes?.takeIf { it.isNotBlank() }?.let { div(classes = "focus-notes") { linkified(it) } }
    // A checklist: its items, big, to tick off one by one.
    if (session.type == TaskType.CHECKLIST) div(classes = "focus-items") {
        checklistItems(session.id, all).forEach { item ->
            button(classes = if (item.isComplete) "focus-item done" else "focus-item") {
                hx("post", "/focus/item?task=${item.id}", "#focus")
                span(classes = if (item.isComplete) "check done" else "check") { if (item.isComplete) icon(Icon.CHECK, "") }
                +item.title
            }
        }
    }
    // The timer: the same play button as a list row's (app.js runs it; it's shared with every device).
    session.durationMinutes?.let { minutes ->
        button(classes = "play") {
            attributes["data-task-id"] = session.id.toString()
            attributes["data-minutes"] = minutes.toString()
            attributes["data-title"] = session.title
            icon(Icon.PLAY, "play-icon")
            icon(Icon.PAUSE, "pause-icon")
            span(classes = "play-time") { +formatDuration(minutes) }
        }
    }
    div(classes = "focus-done") {
        button(classes = "check") { hx("post", "/focus/done", "#focus"); attributes["aria-label"] = "Done" }
        span { +"Done" }
    }
    div(classes = "focus-foot") {
        // A stray thought, kept for later without leaving.
        form(classes = "quickadd") {
            attributes["hx-post"] = "/quickadd"
            attributes["hx-swap"] = "none"
            textInput(name = "text") { id = "quickadd"; placeholder = "Add a task for later… (n)"; attributes["autocomplete"] = "off" }
        }
        button(classes = "leave") { hx("post", "/focus/leave", "this"); +"Leave focus" }
    }
}

// What to focus on: the next step of a sequential project just finished, then Doing (or Active when
// Doing is empty), like the phone's picker; or a new task.
fun FlowContent.focusPicker(service: TaskService, finished: Task?) {
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    val active = service.active()
    val doing = filterDoing(active, service.now(), byId, service.contextIdsByTask())
    val candidates = nextFocusTasks(active, doing, finished, byId).take(30)
    if (candidates.isEmpty()) p(classes = "empty") { +"Nothing in Doing or Active" }
    candidates.forEach { t ->
        button(classes = "cand") {
            hx("post", "/focus/start?task=${t.id}", "this")
            subtaskParentTitle(t, byId)?.let { span(classes = "parent") { +"$it: " } }
            +t.title
        }
    }
    form(classes = "inline focus-new") {
        attributes["hx-post"] = "/focus/new"
        textInput(name = "text") { placeholder = "New task to focus on…"; attributes["autocomplete"] = "off" }
    }
}
