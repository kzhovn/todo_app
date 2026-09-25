package com.kzhovn.todoapp.server.web

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
private const val DEFAULT_FOLDER = "Personal" // matches the app's quick add

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
            call.respondHtml { listPage(ListData(service, mode, call.collapsed()), deleted) }
        }
        get("/list/${mode.name.lowercase()}") {
            call.request.queryParameters["toggle"]?.toLongOrNull()?.let { call.setCollapsed(call.collapsed().let { c -> if (it in c) c - it else c + it }) }
            call.respondList(service, mode)
        }
    }

    editorRoutes(service)
    pageRoutes(service)

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

    post("/tasks/{id}/complete") {
        val id = call.taskId() ?: return@post
        service.complete(id)
        val task = service.tasks().firstOrNull { it.id == id } ?: service.get(id)
        call.respondList(service, call.mode(), extra = task?.let { t -> createHTML().div { undoToastContents(t, call.mode()) } })
    }
    post("/tasks/{id}/uncomplete") { call.taskId()?.let(service::uncomplete); call.respondList(service, call.mode()) }
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
    post("/quickadd") {
        val params = call.receiveParameters()
        val text = params["text"].orEmpty()
        val mode = params["mode"]?.let { runCatching { ListMode.valueOf(it) }.getOrNull() }
        val parsed = QuickAddParser.parse(text)
        val created = if (parsed.title.isNotBlank()) service.create(parsed.copy(parentId = service.findFolder(DEFAULT_FOLDER)?.id)) else null
        if (mode != null) return@post call.respondList(service, mode)
        call.respondText(created?.let { t -> createHTML().div { addedToastContents(t) } }.orEmpty(), ContentType.Text.Html)
    }
}

internal fun ApplicationCall.taskId() = parameters["id"]?.toLongOrNull()

internal fun ApplicationCall.mode() =
    request.queryParameters["mode"]?.let { runCatching { ListMode.valueOf(it) }.getOrNull() } ?: ListMode.DOING

internal suspend fun ApplicationCall.respondList(service: TaskService, mode: ListMode, extra: String? = null) =
    respondText(createHTML().div { listContents(ListData(service, mode, collapsed())) } + extra.orEmpty(), ContentType.Text.Html)

// Folded All-tree nodes live in a cookie: per browser, and read by the server so the list's periodic
// re-render keeps them folded. ponytail: ~250 ids fit a cookie; plenty for folded nodes.
private const val COLLAPSED = "collapsed"

// A just-set value wins over the request's, so a toggle's own response renders it.
private val CollapsedOverride = AttributeKey<Set<Long>>("collapsed")

internal fun ApplicationCall.collapsed(): Set<Long> = attributes.getOrNull(CollapsedOverride)
    ?: request.cookies[COLLAPSED].orEmpty().split('.').mapNotNull { it.toLongOrNull() }.toSet()

private fun ApplicationCall.setCollapsed(ids: Set<Long>) {
    attributes.put(CollapsedOverride, ids)
    response.cookies.append(
        Cookie(COLLAPSED, ids.joinToString("."), encoding = CookieEncoding.RAW, maxAge = 365 * 24 * 3600, path = "/", httpOnly = true, extensions = mapOf("SameSite" to "Lax"))
    )
}
