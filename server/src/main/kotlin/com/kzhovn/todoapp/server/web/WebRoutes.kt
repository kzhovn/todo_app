package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.TaskType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.nextRollover
import com.kzhovn.todoapp.quickadd.QuickAddParser
import com.kzhovn.todoapp.server.TaskService
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.html.div
import kotlinx.html.stream.createHTML

private const val HOUR_MS = 60 * 60 * 1000L

// No login here: Caddy's basic_auth guards every web page, and Main only mounts these routes when the
// server listens on loopback, i.e. is reachable solely through Caddy.
fun Route.webRoutes(service: TaskService) {
    // Explicit route: staticResources() treats dots in a resource path as package separators, which
    // mangles the webjar's "htmx.org/2.0.8" directory.
    val htmx = Route::class.java.classLoader.getResource("META-INF/resources/webjars/htmx.org/2.0.8/dist/htmx.min.js")!!.readBytes()
    get("/static/htmx/htmx.min.js") { call.respondBytes(htmx, ContentType.Text.JavaScript) }
    staticResources("/static", "static")

    get("/") { call.respondRedirect("/doing") }
    ListMode.entries.forEach { mode ->
        get("/${mode.name.lowercase()}") {
            val deleted = call.request.queryParameters["deleted"]?.toLongOrNull()?.let(service::deletedTask)
            call.respondHtml { listPage(service, call.listData(service, mode), deleted) }
        }
        // ?toggle folds/unfolds an All-tree node, ?fold an Active section; ?later puts off a stalled
        // project's prompt.
        get("/list/${mode.name.lowercase()}") {
            call.request.queryParameters["toggle"]?.toLongOrNull()?.let { call.toggleIn(COLLAPSED, it) }
            call.request.queryParameters["later"]?.toLongOrNull()?.let { call.toggleIn(LATER, it) }
            call.request.queryParameters["fold"]?.toLongOrNull()?.let { call.toggleIn(SECTIONS, it) }
            call.respondList(service, mode)
        }
    }

    editorRoutes(service)
    pageRoutes(service)
    focusRoutes(service)
    timerRoutes(service)

    // The All tree's outliner actions (app.js sends them from keys and drag).
    post("/outline/{id}/{action}") {
        val id = call.taskId() ?: return@post
        // Most actions carry no form body at all.
        val params = runCatching { call.receiveParameters() }.getOrDefault(Parameters.Empty)
        when (call.parameters["action"]) {
            "indent" -> service.indent(id)
            "outdent" -> service.outdent(id)
            "up" -> service.moveAmongSiblings(id, down = false)
            "down" -> service.moveAmongSiblings(id, down = true)
            "move" -> params["target"]?.toLongOrNull()?.let { target ->
                when (params["zone"]) {
                    "before" -> service.moveNextTo(id, target, after = false)
                    "after" -> service.moveNextTo(id, target, after = true)
                    "into" -> service.reparent(id, target)
                }
            }
            // Enter: a new task right after this one, same parent. app.js focuses it (X-Focus).
            "sibling" -> {
                val anchor = service.get(id)
                val parsed = QuickAddParser.parse(params["text"].orEmpty())
                if (anchor != null && parsed.title.isNotBlank()) {
                    val created = service.create(parsed.copy(parentId = anchor.parentId))
                    service.moveNextTo(created.id, id, after = true)
                    call.response.header("X-Focus", created.id.toString())
                }
            }
        }
        call.respondList(service, ListMode.ALL)
    }

    // A task with open subtasks asks first, like the phone: complete them too, or move them out.
    post("/tasks/{id}/complete") {
        val id = call.taskId() ?: return@post
        val mode = call.mode()
        val task = service.get(id) ?: return@post call.respondList(service, mode)
        val choice = call.request.queryParameters["subtasks"]
        val open = service.activeDescendantCount(id)
        if (open > 0 && choice == null) {
            return@post call.respondList(service, mode, extra = createHTML().div {
                askSubtasksToast(task, open, "/tasks/$id/complete?mode=${call.listQuery()}", target = "#list")
            })
        }
        service.completeOrDecide(id, choice)
        call.respondList(service, mode, extra = createHTML().div { undoToastContents(task, call.listQuery()) })
    }
    // From a row's menu: a subtask under it, or items into a checklist ("milk, eggs" is two).
    post("/tasks/{id}/add-subtask") {
        val parent = call.taskId()?.let(service::get) ?: return@post call.respondList(service, call.mode())
        val text = call.receiveParameters()["text"].orEmpty()
        if (parent.type == TaskType.CHECKLIST) service.addItems(parent.id, text)
        // The parent now waits on its new subtask, so in Doing a starred parent's subtask starts starred and
        // takes its place there, as on the phone.
        else QuickAddParser.parse(text).takeIf { it.title.isNotBlank() }?.let { t ->
            service.create(t.copy(parentId = parent.id, isStarred = t.isStarred || (call.mode() == ListMode.DOING && parent.isStarred)))
        }
        call.respondList(service, call.mode())
    }
    post("/tasks/{id}/uncomplete") { call.taskId()?.let(service::uncomplete); call.respondList(service, call.mode()) }
    post("/tasks/{id}/pin") {
        val task = call.taskId()?.let(service::get) ?: return@post call.respondList(service, call.mode())
        service.pin(task.id)
        call.respondList(service, call.mode(), extra = createHTML().div { pinnedToastContents(task) })
    }
    post("/tasks/{id}/pin-toggle") {
        val id = call.taskId() ?: return@post
        val pinned = service.pinned()?.id == id
        if (pinned) service.unpin() else service.pin(id)
        call.respondText(createHTML().div { pinToggle(id, !pinned) }.removePrefix("<div>").removeSuffix("</div>"), ContentType.Text.Html)
    }
    post("/tasks/{id}/star") { call.taskId()?.let(service::toggleStar); call.respondList(service, call.mode()) }
    post("/tasks/{id}/snooze") {
        val now = service.now()
        // "Tomorrow" starts at the day rollover (4am by default), not 24 hours from now.
        val until = when (call.request.queryParameters["until"]) {
            "tomorrow" -> nextRollover(now, service.rolloverHour())
            "week" -> now + 7 * 24 * HOUR_MS
            else -> now + HOUR_MS
        }
        call.taskId()?.let { service.snooze(it, until) }
        call.respondList(service, call.mode())
    }
    // A stalled project's "Add next" step.
    post("/tasks/{id}/next") {
        val id = call.taskId() ?: return@post
        val title = call.receiveParameters()["text"].orEmpty().trim()
        if (title.isNotEmpty() && service.get(id) != null) service.create(Task(title = title, parentId = id))
        call.respondList(service, call.mode())
    }
    post("/quickadd") {
        val params = call.receiveParameters()
        val text = params["text"].orEmpty()
        val mode = params["mode"]?.let { runCatching { ListMode.valueOf(it) }.getOrNull() }
        val created = service.quickAdd(text, fromDoing = mode == ListMode.DOING, folderId = call.folder())
        if (mode != null) return@post call.respondList(service, mode)
        call.respondText(created?.let { t -> createHTML().div { addedToastContents(t) } }.orEmpty(), ContentType.Text.Html)
    }
}

// "complete" also completes the open subtasks; "promote" moves them out first (to the top level, as
// the phone does); anything else completes just this task.
internal fun TaskService.completeOrDecide(id: Long, choice: String?) = when (choice) {
    "complete" -> completeWithDescendants(id)
    "promote" -> { promoteChildren(id); complete(id) }
    else -> complete(id)
}

internal fun ApplicationCall.taskId() = parameters["id"]?.toLongOrNull()

internal fun ApplicationCall.mode() =
    request.queryParameters["mode"]?.let { runCatching { ListMode.valueOf(it) }.getOrNull() } ?: ListMode.DOING

// A folder's view of the All tree (?folder=), which every action on it carries along.
internal fun ApplicationCall.folder() = request.queryParameters["folder"]?.toLongOrNull()
internal fun ApplicationCall.listQuery() = mode().name + (folder()?.let { "&folder=$it" } ?: "")
internal fun ApplicationCall.listPath() = mode().path + (folder()?.let { "?folder=$it" } ?: "")

internal fun ApplicationCall.listData(service: TaskService, mode: ListMode, selected: Long? = null): ListData {
    service.ensureFolderColors()
    return ListData(service, mode, ids(COLLAPSED), ids(LATER), ids(SECTIONS), folder = folder(), selected = selected)
}

internal suspend fun ApplicationCall.respondList(service: TaskService, mode: ListMode, extra: String? = null) {
    // A focus session started (here or elsewhere): the page reloads, into it.
    if (service.focusSession() != null) response.header("HX-Refresh", "true")
    val data = listData(service, mode)
    respondText(createHTML().div { listContents(data) } + countsOob(service, data) + extra.orEmpty(), ContentType.Text.Html)
}

// The timer, shared with every device: it's the current task's (CurrentTask). app.js runs the countdown
// from this, and re-reads it now and then; `now` lets it correct for its own clock.
private fun Route.timerRoutes(service: TaskService) {
    suspend fun ApplicationCall.respondTimer() {
        val t = service.pinned()?.takeIf { it.timerEndsAt != null || it.timerRemaining != null }
        val body = buildJsonObject {
            put("now", service.now())
            if (t == null) put("timer", JsonNull) else put("timer", buildJsonObject {
                put("id", t.id.toString()); put("title", t.title); put("endsAt", t.timerEndsAt); put("remaining", t.timerRemaining)
            })
        }
        respondText(body.toString(), ContentType.Application.Json)
    }
    get("/timer") { call.respondTimer() }
    post("/timer/start") {
        call.request.queryParameters["task"]?.toLongOrNull()?.let { id -> service.get(id)?.durationMinutes?.let { service.startTimer(id, it) } }
        call.respondTimer()
    }
    post("/timer/pause") { service.pauseTimer(); call.respondTimer() }
    post("/timer/resume") { service.resumeTimer(); call.respondTimer() }
    // Stopping unpins the task (and ends a focus session).
    post("/timer/stop") { service.unpin(); call.respondTimer() }
    post("/timer/add") { call.request.queryParameters["minutes"]?.toIntOrNull()?.takeIf { it > 0 }?.let(service::addTime); call.respondTimer() }
    // Time's up, and it's done: like the phone's, subtasks too.
    post("/timer/done") { service.pinned()?.let { service.completeWithDescendants(it.id) }; call.respondTimer() }
}

// Per-browser id sets kept in cookies the server reads, so the list's periodic re-render honours them.
// ponytail: ~250 ids fit a cookie; plenty for these.
private class IdCookie(val name: String, val maxAge: Int?) {
    // A just-set value wins over the request's, so the toggling request's own response renders it.
    val override = AttributeKey<Set<Long>>(name)
}

// Folded All-tree nodes, remembered for a year.
private val COLLAPSED = IdCookie("collapsed", maxAge = 365 * 24 * 3600)

// Active's folded folder sections, remembered for a year like the tree's folds.
private val SECTIONS = IdCookie("sections", maxAge = 365 * 24 * 3600)

// Stalled projects put off with "Later": for this browser session, like the phone's (which asks
// again the next time the app starts).
private val LATER = IdCookie("later", maxAge = null)

private fun ApplicationCall.ids(cookie: IdCookie): Set<Long> = attributes.getOrNull(cookie.override)
    ?: request.cookies[cookie.name].orEmpty().split('.').mapNotNull { it.toLongOrNull() }.toSet()

private fun ApplicationCall.toggleIn(cookie: IdCookie, id: Long) {
    val ids = ids(cookie).let { if (id in it) it - id else it + id }
    attributes.put(cookie.override, ids)
    response.cookies.append(
        Cookie(cookie.name, ids.joinToString("."), encoding = CookieEncoding.RAW, maxAge = cookie.maxAge, path = "/", httpOnly = true, extensions = mapOf("SameSite" to "Lax"))
    )
}
