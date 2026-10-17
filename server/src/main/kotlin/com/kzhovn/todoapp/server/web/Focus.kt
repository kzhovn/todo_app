package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.inMode
import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.data.checklistGroups
import com.kzhovn.todoapp.data.dueText
import com.kzhovn.todoapp.data.formatDuration
import com.kzhovn.todoapp.data.subtaskParentTitle
import com.kzhovn.todoapp.repository.focusSearch
import com.kzhovn.todoapp.repository.focusLists
import com.kzhovn.todoapp.data.nearestFolder
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
import kotlinx.html.checkBoxInput
import kotlinx.html.label
import kotlinx.html.InputType
import kotlinx.html.details
import kotlinx.html.input
import kotlinx.html.summary
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
    get("/focus") { call.respondHtml { shellPage(service, "Raspberry · Focus", "/focus") { div(classes = "focus-pick") { h2 { +Labels.FOCUS_ON }; focusPicker(service, finished = null) } } } }
    // The focus screen's own refresh: its task changed, done, or moved on elsewhere. Once the session
    // is over (left anywhere), back to the lists.
    get("/focus/body") {
        val session = service.focusSession() ?: return@get call.goTo("/doing")
        call.respondText(createHTML().div { focusBody(service, session, call.collapsedIds()) }, ContentType.Text.Html)
    }
    get("/focus/search") {
        val q = call.request.queryParameters
        val finished = q["finished"]?.toLongOrNull()?.let(service::get)
        call.respondText(createHTML().div { id = "focus-cands"; focusCandidates(service, finished, q["q"].orEmpty(), q["everywhere"] != null, q["list"]?.toIntOrNull()) }, ContentType.Text.Html)
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
    // Open subtasks are asked about first (?subtasks= complete or promote), as everywhere else.
    post("/focus/done") {
        val choice = call.request.queryParameters["subtasks"]
        val toast = service.focusSession()?.takeUnless { it.isComplete }?.let { t ->
            val open = service.activeDescendantCount(t.id)
            if (open > 0 && choice == null) createHTML().div { askSubtasksToast(t, open, "/focus/done", target = "#focus") }
            else { service.completeOrDecide(t.id, choice); null }
        } ?: createHTML().div { id = "toast"; attributes["hx-swap-oob"] = "true" } // an answered question goes
        val session = service.focusSession() ?: return@post call.goTo("/doing")
        call.respondText(createHTML().div { focusBody(service, session, call.collapsedIds()) } + toast, ContentType.Text.Html)
    }
    // Ticking an item of the checklist in focus.
    post("/focus/item") {
        call.request.queryParameters["task"]?.toLongOrNull()?.let(service::get)?.let { if (it.isComplete) service.uncomplete(it.id) else service.complete(it.id) }
        val session = service.focusSession() ?: return@post call.goTo("/doing")
        call.respondText(createHTML().div { focusBody(service, session, call.collapsedIds()) }, ContentType.Text.Html)
    }
    // Folding a section of the checklist in focus (with the editor's folds, per browser).
    post("/focus/fold") {
        call.request.queryParameters["section"]?.toLongOrNull()?.let { call.toggleCollapsed(it) }
        val session = service.focusSession() ?: return@post call.goTo("/doing")
        call.respondText(createHTML().div { focusBody(service, session, call.collapsedIds()) }, ContentType.Text.Html)
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
fun HTML.focusPage(service: TaskService, session: Task, folded: Set<Long> = emptySet()) = page("Raspberry · Focus") {
    div(classes = "focus-shell") { div { focusBody(service, session, folded) } }
    div { id = "toast" }
    div { id = "timer"; attributes["hidden"] = "" }
}

// `folded`: the checklist's sections folded in this browser.
fun DIV.focusBody(service: TaskService, session: Task, folded: Set<Long> = emptySet()) {
    id = "focus"
    classes = setOf("focus")
    // Follows the session from anywhere: a task done on the phone, the next one picked on the top bar.
    attributes["hx-get"] = "/focus/body"
    attributes["hx-trigger"] = "every 20s, visibilitychange from:document, refresh from:body"
    attributes["hx-swap"] = "outerHTML"
    if (session.isComplete) {
        h1(classes = "focus-title") { +Labels.FOCUS_DONE }
        p(classes = "focus-sub") { +Labels.FOCUS_NEXT_OR_FINISH }
        div(classes = "focus-pick") { focusPicker(service, finished = session) }
        button(classes = "leave") { hx("post", "/focus/leave", "this"); +Labels.IM_DONE }
        return
    }
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    div(classes = "focus-label") { +Labels.FOCUS }
    subtaskParentTitle(session, byId)?.let { p(classes = "focus-sub") { +it } }
    h1(classes = "focus-title") { +session.title }
    service.effectiveDueDate(session)?.let { p(classes = "focus-sub") { +dueText(it, service.now()) } }
    // The note: what to ask, the number to call.
    session.notes?.takeIf { it.isNotBlank() }?.let { div(classes = "focus-notes") { linkified(it) } }
    // A checklist: its items, big, to tick off one by one, under their sections (folded as in the editor).
    if (session.type == TaskType.CHECKLIST) div(classes = "focus-items") {
        checklistGroups(session.id, all).forEach { (section, items) ->
            val isFolded = section != null && section.id in folded
            section?.let { sec ->
                button(classes = "focus-section") {
                    hx("post", "/focus/fold?section=${sec.id}", "#focus")
                    +"${if (isFolded) "▸" else "▾"} ${sec.title}"
                    span(classes = "tail") { +" ${items.count { it.isComplete }}/${items.size}" }
                }
            }
            if (!isFolded) items.forEach { item ->
                button(classes = if (item.isComplete) "focus-item done" else "focus-item") {
                    hx("post", "/focus/item?task=${item.id}", "#focus")
                    span(classes = if (item.isComplete) "check done" else "check") { if (item.isComplete) icon(Icon.CHECK, "") }
                    +item.title
                }
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
        button(classes = "leave") { hx("post", "/focus/leave", "this"); +Labels.LEAVE_FOCUS }
    }
}

// What to focus on: the next step of a sequential project just finished, then Doing (or Active when
// Doing is empty), like the phone's picker. Search reaches any open task; or a new task.
fun FlowContent.focusPicker(service: TaskService, finished: Task?) {
    // The search replaces the list below while it has a query, and brings it back when cleared.
    // In folder mode it searches the mode's folder, unless Everywhere is ticked.
    val mode = service.modeFolder()
    details(classes = "focus-search") {
        summary { icon(Icon.SEARCH, "search-icon"); +(mode?.let { "Search ${it.title}" } ?: "Search all tasks") }
        form(classes = "focus-search-form") {
            attributes["hx-get"] = "/focus/search" + (finished?.let { "?finished=${it.id}" } ?: "")
            attributes["hx-trigger"] = "input changed delay:200ms, search, change"
            attributes["hx-target"] = "#focus-cands"
            attributes["hx-swap"] = "outerHTML"
            input(type = InputType.search, name = "q") { placeholder = "Any open task…"; attributes["autocomplete"] = "off" }
            if (mode != null) label(classes = "pill") { checkBoxInput(name = "everywhere"); +"Everywhere" }
        }
    }
    div { id = "focus-cands"; focusCandidates(service, finished, query = "") }
    form(classes = "inline focus-new") {
        attributes["hx-post"] = "/focus/new"
        textInput(name = "text") { placeholder = "New task to focus on…"; attributes["autocomplete"] = "off" }
    }
}

// The picker's list: with no query, Doing · Active · All as chips (`list` picks one; by default the first
// that isn't empty), as on the phone; with one, any matching open task (in the mode's folder, unless `everywhere`).
fun FlowContent.focusCandidates(service: TaskService, finished: Task?, query: String, everywhere: Boolean = false, list: Int? = null) {
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    val candidates = if (query.isBlank()) {
        val active = service.active()
        val mode = service.modeFolderId()
        val lists = focusLists(active, service.doing(active), all.filter { inMode(it, mode, byId) }, finished, byId)
        val chosen = list?.takeIf { it in lists.indices } ?: lists.indexOfFirst { it.second.isNotEmpty() }.coerceAtLeast(0)
        div(classes = "focus-chips") {
            lists.forEachIndexed { i, (label, tasks) ->
                button(classes = if (i == chosen) "chip on" else "chip") {
                    hx("get", "/focus/search?list=$i" + (finished?.let { "&finished=${it.id}" } ?: ""), "#focus-cands")
                    +"$label ${tasks.size}"
                }
            }
        }
        lists[chosen].second.take(60)
    } else {
        val mode = if (everywhere) null else service.modeFolderId()
        focusSearch(all, query).filter { inMode(it, mode, byId) }.take(30)
    }
    if (candidates.isEmpty()) p(classes = "empty") { +(if (query.isBlank()) Labels.NOTHING_HERE else "No open task matches") }
    candidates.forEach { t ->
        button(classes = "cand") {
            hx("post", "/focus/start?task=${t.id}", "this")
            subtaskParentTitle(t, byId)?.let { span(classes = "parent") { +"$it: " } }
            +t.title
            nearestFolder(t, byId)?.let { span(classes = "where") { +it.title } }
        }
    }
}
