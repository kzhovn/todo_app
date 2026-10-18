package com.kzhovn.todoapp.server.web

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import com.kzhovn.todoapp.data.SnoozeChoice
import com.kzhovn.todoapp.quickadd.typedType
import com.kzhovn.todoapp.server.TaskService
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
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
import com.kzhovn.todoapp.data.DEFAULT_DATE_KINDS
import com.kzhovn.todoapp.data.DateKind
import com.kzhovn.todoapp.data.localDay
import java.time.LocalDate
import java.time.YearMonth


// From webjars on the classpath, so there's no CDN and no vendored copy; each served at /static/<its key>.
internal const val HTMX = "htmx/htmx.min.js"
internal const val FULLCALENDAR = "fullcalendar/index.global.min.js" // the calendar page's month grid
internal val webjars: Map<String, ByteArray> = mapOf(
    HTMX to "htmx.org/2.0.11/dist/htmx.min.js",
    FULLCALENDAR to "fullcalendar/6.1.15/index.global.min.js",
).mapValues { (_, path) -> Route::class.java.classLoader.getResource("META-INF/resources/webjars/$path")!!.readBytes() }

// Static files never change under a URL (asset() puts a hash of the file in it), so browsers keep them.
private const val CACHE_FOR_GOOD = "max-age=31536000, immutable"

// No login here: Caddy's basic_auth guards every web page, and Main only mounts these routes when the
// server listens on loopback, i.e. is reachable solely through Caddy.
fun Route.webRoutes(service: TaskService) {
    // Explicit routes: staticResources() treats dots in a resource path as package separators, which
    // mangles the webjar's "htmx.org/2.0.11" directory.
    webjars.forEach { (path, bytes) ->
        get("/static/$path") {
            call.response.header(HttpHeaders.CacheControl, CACHE_FOR_GOOD)
            call.respondBytes(bytes, ContentType.Text.JavaScript)
        }
    }
    staticResources("/static", "static") { modify { _, call -> call.response.header(HttpHeaders.CacheControl, CACHE_FOR_GOOD) } }

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
    templateRoutes(service)
    calendarRoutes(service)
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
                val created = anchor?.let { service.addTyped(params["text"].orEmpty(), folder = it.parentId, under = it.parentId) }
                if (created != null) {
                    service.moveNextTo(created.id, id, after = true)
                    call.response.header("X-Focus", created.id.toString())
                }
            }
        }
        call.respondList(service, ListMode.ALL)
    }

    // A waiting item's "Still waiting": hidden until its next check-in.
    post("/tasks/{id}/still-waiting") {
        call.taskId()?.let(service::stillWaiting)
        call.respondList(service, call.mode())
    }
    // A step's "Don't hold up the next step" / "Hold up the next step", from its row menu.
    post("/tasks/{id}/alongside") {
        call.taskId()?.let(service::toggleAlongside)
        call.respondList(service, call.mode())
    }
    // A running wait's "+10 min", on Doing's Running line.
    post("/tasks/{id}/more-wait") {
        call.taskId()?.let(service::moreWait)
        call.respondList(service, call.mode())
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
        // The parent now waits on its new subtask, so in Doing a starred parent's subtask starts starred and
        // takes its place there, as on the phone. (Under a checklist, addTyped adds items.)
        service.addTyped(text, under = parent.id, star = call.mode() == ListMode.DOING && parent.isStarred)
        call.respondList(service, call.mode())
    }
    post("/tasks/{id}/uncomplete") { call.taskId()?.let(service::uncomplete); call.respondList(service, call.mode()) }
    post("/tasks/{id}/pin") {
        val task = call.taskId()?.let(service::get) ?: return@post call.respondList(service, call.mode())
        service.pin(task.id)
        call.respondList(service, call.mode(), extra = createHTML().div { pinnedToastContents(task) })
    }
    post("/tasks/{id}/pin-toggle") {
        val task = call.taskId()?.let(service::get) ?: return@post
        val pinned = service.pinned()?.id == task.id
        if (pinned) service.unpin() else service.pin(task.id)
        if (call.request.queryParameters["refresh"] != null) call.response.header("HX-Refresh", "true")
        call.respondText(createHTML().div { pinControl(task, !pinned) }.removePrefix("<div>").removeSuffix("</div>"), ContentType.Text.Html)
    }
    post("/tasks/{id}/skip") { call.taskId()?.let(service::skip); call.respondList(service, call.mode()) }
    post("/tasks/{id}/star") { call.taskId()?.let(service::toggleStar); call.respondList(service, call.mode()) }
    post("/tasks/{id}/snooze") {
        val choice = SnoozeChoice.entries.firstOrNull { it.name.equals(call.request.queryParameters["until"], ignoreCase = true) } ?: SnoozeChoice.HOUR
        val until = choice.at(service.now(), service.rolloverHour())
        call.taskId()?.let { service.snooze(it, until) }
        call.respondList(service, call.mode())
    }
    // A stalled project's "Add next" step.
    post("/tasks/{id}/next") {
        val id = call.taskId() ?: return@post
        if (service.get(id) != null) service.addTyped(call.receiveParameters()["text"].orEmpty(), under = id)
        call.respondList(service, call.mode())
    }
    get("/quickadd/type") {
        val params = call.request.queryParameters
        val html = createHTML().div { typeChip(quickAddType(params, service.now()), typedType(params["text"].orEmpty(), service.now())) }
        call.respondText(html, ContentType.Text.Html)
    }
    post("/quickadd") {
        val params = call.receiveParameters()
        val text = params["text"].orEmpty()
        val mode = params["mode"]?.let { runCatching { ListMode.valueOf(it) }.getOrNull() }
        val created = service.quickAdd(text, fromDoing = mode == ListMode.DOING, folderId = call.folder(), type = quickAddType(params, service.now()))
        if (mode != null) return@post call.respondList(service, mode)
        call.respondText(created?.let { t -> createHTML().div { addedToastContents(t) } }.orEmpty(), ContentType.Text.Html)
    }
}

// "complete" also completes the open subtasks; "promote" moves them out first (see planCompleteAndPromote);
// anything else completes just this task.
internal fun TaskService.completeOrDecide(id: Long, choice: String?) = when (choice) {
    "complete" -> completeChoosing(id, moveOut = false)
    "promote" -> completeChoosing(id, moveOut = true)
    else -> complete(id)
}

internal fun ApplicationCall.taskId() = parameters["id"]?.toLongOrNull()

internal fun ApplicationCall.mode() =
    request.queryParameters["mode"]?.let { runCatching { ListMode.valueOf(it) }.getOrNull() } ?: ListMode.DOING

// A folder's view of the All tree (?folder=), which every action on it carries along.
internal fun ApplicationCall.folder() = request.queryParameters["folder"]?.toLongOrNull()
internal fun ApplicationCall.listQuery() = listData(mode()).q
internal fun ApplicationCall.listPath() = listData(mode()).path

internal fun ApplicationCall.listData(service: TaskService, mode: ListMode, selected: Long? = null): ListData {
    service.ensureFolderColors()
    return ListData(service, mode, ids(COLLAPSED), ids(LATER), ids(SECTIONS), folder = folder(), selected = selected, calendar = calendarView(mode, service.now()))
}

// Just the list's query and path, without working out the list.
private fun ApplicationCall.listData(mode: ListMode) = ListPlace(mode, folder(), calendarView(mode, System.currentTimeMillis()))

// The calendar's month (?month=2026-10, else this one) and picked day (?day=2026-10-20), which every action on
// it carries along, and the kinds of date shown: the default's, with those flipped by this browser's chips.
internal fun ApplicationCall.calendarView(mode: ListMode, now: Long): CalendarView? {
    if (mode != ListMode.CALENDAR) return null
    val q = request.queryParameters
    val month = q["month"]?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.from(localDay(now))
    val flipped = ids(KINDS).mapNotNull { DateKind.entries.getOrNull(it.toInt()) }.toSet()
    return CalendarView(month, q["day"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, (DEFAULT_DATE_KINDS - flipped) + (flipped - DEFAULT_DATE_KINDS))
}
internal fun ApplicationCall.flipKind(kind: DateKind) = toggleIn(KINDS, kind.ordinal.toLong())

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
        call.request.queryParameters["task"]?.toLongOrNull()?.let(service::startTimer)
        call.respondTimer()
    }
    post("/timer/pause") { service.pauseTimer(); call.respondTimer() }
    post("/timer/resume") { service.resumeTimer(); call.respondTimer() }
    // Stopping unpins the task (and ends a focus session).
    post("/timer/stop") { service.unpin(); call.respondTimer() }
    post("/timer/add") { call.request.queryParameters["minutes"]?.toIntOrNull()?.takeIf { it > 0 }?.let(service::addTime); call.respondTimer() }
    // Time's up, and it's done. Open subtasks are asked about first, in the toast (?subtasks= complete or
    // promote); otherwise an empty toast, and a refresh for the timer and the list to catch up.
    post("/timer/done") {
        val choice = call.request.queryParameters["subtasks"]
        val task = service.pinned()?.takeUnless { it.isComplete }
        val open = task?.let { service.activeDescendantCount(it.id) } ?: 0
        if (task != null && open > 0 && choice == null) return@post call.respondText(createHTML().div { askSubtasksToast(task, open, "/timer/done", target = "#toast") }, ContentType.Text.Html)
        task?.let { service.completeOrDecide(it.id, choice) }
        call.response.header("HX-Trigger", "refresh")
        call.respondText(createHTML().div { attributes["id"] = "toast" }, ContentType.Text.Html)
    }
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

// The calendar's kinds of date flipped from the default (by ordinal), remembered for a year.
private val KINDS = IdCookie("kinds", maxAge = 365 * 24 * 3600)

// Stalled projects put off with "Later": for this browser session, like the phone's (which asks
// again the next time the app starts).
private val LATER = IdCookie("later", maxAge = null)

private fun ApplicationCall.ids(cookie: IdCookie): Set<Long> = attributes.getOrNull(cookie.override)
    ?: request.cookies[cookie.name].orEmpty().split('.').mapNotNull { it.toLongOrNull() }.toSet()

// The All tree's folds, which a checklist's sections share (one cookie, per browser).
internal fun ApplicationCall.collapsedIds(): Set<Long> = ids(COLLAPSED)
internal fun ApplicationCall.toggleCollapsed(id: Long) = toggleIn(COLLAPSED, id)

private fun ApplicationCall.toggleIn(cookie: IdCookie, id: Long) {
    val ids = ids(cookie).let { if (id in it) it - id else it + id }
    attributes.put(cookie.override, ids)
    response.cookies.append(
        Cookie(cookie.name, ids.joinToString("."), encoding = CookieEncoding.RAW, maxAge = cookie.maxAge, path = "/", httpOnly = true, extensions = mapOf("SameSite" to "Lax"))
    )
}
