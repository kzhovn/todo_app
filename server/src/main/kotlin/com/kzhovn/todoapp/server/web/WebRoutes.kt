package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.quickadd.QuickAddParser
import com.kzhovn.todoapp.server.TaskService
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.div
import kotlinx.html.stream.createHTML

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
        get("/${mode.name.lowercase()}") { call.respondHtml { listPage(ListData(service, mode)) } }
        get("/list/${mode.name.lowercase()}") { call.respondList(service, mode) }
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
        val minutes = call.request.queryParameters["minutes"]?.toLongOrNull() ?: 60
        call.taskId()?.let { service.snooze(it, minutes * 60_000) }
        call.respondList(service, call.mode())
    }
    post("/quickadd") {
        val params = call.receiveParameters()
        val text = params["text"].orEmpty()
        val mode = params["mode"]?.let { runCatching { ListMode.valueOf(it) }.getOrNull() } ?: ListMode.DOING
        if (text.isNotBlank()) {
            val parsed = QuickAddParser.parse(text)
            if (parsed.title.isNotBlank()) service.create(parsed.copy(parentId = service.findFolder(DEFAULT_FOLDER)?.id))
        }
        call.respondList(service, mode)
    }
}

private fun ApplicationCall.taskId() = parameters["id"]?.toLongOrNull()

private fun ApplicationCall.mode() =
    request.queryParameters["mode"]?.let { runCatching { ListMode.valueOf(it) }.getOrNull() } ?: ListMode.DOING

private suspend fun ApplicationCall.respondList(service: TaskService, mode: ListMode, extra: String? = null) =
    respondText(createHTML().div { listContents(ListData(service, mode)) } + extra.orEmpty(), ContentType.Text.Html)
