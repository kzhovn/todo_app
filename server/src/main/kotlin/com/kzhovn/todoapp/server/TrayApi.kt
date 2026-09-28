package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.folderColorsArgb
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.data.subtaskCounts
import com.kzhovn.todoapp.widget.TodoWidgetPresenter
import com.kzhovn.todoapp.widget.WidgetTaskRow
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

// The desktop tray's data and actions (docs/superpowers/specs/2026-09-27-desktop-tray-design.md).
// Rows are the widget's own, so the tray shows exactly what a Doing/Active widget would. Every
// action answers with the new state, so each costs one round trip.
@Serializable
data class TrayState(val pinned: PinnedRow?, val doing: List<WidgetTaskRow>, val active: List<WidgetTaskRow>, val all: List<WidgetTaskRow>)

@Serializable
data class PinnedRow(val id: Long, val title: String)

fun trayState(service: TaskService): TrayState {
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    val contexts = service.contextIdsByTask()
    val counts = subtaskCounts(all)
    val colors = folderColorsArgb(all)
    fun rows(tasks: List<Task>, urgentOnTop: Boolean = true) = TodoWidgetPresenter.toRows(
        tasks, counts, service.now(), byId, colors, { resolveEffective(it, byId, contexts).effectiveDueDate }, urgentOnTop
    )
    return TrayState(service.pinned()?.let { PinnedRow(it.id, it.title) }, rows(service.doing()), rows(service.active()),
        // Like the widget's All: by folder, with nothing moved to the top.
        rows(TodoWidgetPresenter.allOpen(all, byId), urgentOnTop = false))
}

// Bearer-token protected like /sync, so it's served even where the web pages aren't.
fun Route.trayRoutes(service: TaskService, hasToken: (RoutingContext) -> Boolean) {
    suspend fun RoutingContext.act(action: (Long?) -> Unit) {
        if (!hasToken(this)) return call.respond(HttpStatusCode.Unauthorized)
        action(call.parameters["id"]?.toLongOrNull())
        call.respond(trayState(service))
    }
    get("/api/tray") { act {} }
    // The row's checkbox, like the widget's: completing takes the subtasks too (no room to ask).
    post("/api/tasks/{id}/complete") {
        act { id -> service.get(id ?: return@act)?.let { if (it.isComplete) service.uncomplete(it.id) else service.completeWithDescendants(it.id) } }
    }
    // An expanded row's subtask or checklist item: just this one.
    post("/api/tasks/{id}/toggle-item") {
        act { id -> service.get(id ?: return@act)?.let { if (it.isComplete) service.uncomplete(it.id) else service.complete(it.id) } }
    }
    post("/api/tasks/{id}/star") { act { id -> id?.let(service::toggleStar) } }
    post("/api/tasks/{id}/pin") { act { id -> id?.let(service::pin) } }
    post("/api/unpin") { act { service.unpin() } }
    post("/api/quickadd") {
        if (!hasToken(this)) return@post call.respond(HttpStatusCode.Unauthorized)
        val params = call.receiveParameters()
        service.quickAdd(params["text"].orEmpty(), fromDoing = params["mode"] == "doing")
        call.respond(trayState(service))
    }
}
