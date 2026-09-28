package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.DEFAULT_FOLDER
import com.kzhovn.todoapp.data.TaskType
import io.ktor.http.Parameters
import com.kzhovn.todoapp.data.CurrentTask
import com.kzhovn.todoapp.data.pinnedTask
import com.kzhovn.todoapp.repository.filterDoing
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
// Rows are the widget's own, so the tray shows exactly what a Doing/Active/All widget would. Every
// action answers with the new state, so each costs one round trip. Only the list on screen comes
// back (`list`), and at most MAX_ROWS of it: the popup is parsed and drawn inside GNOME Shell, and a
// full All list ran to over a megabyte.
@Serializable
data class TrayState(
    val pinned: PinnedRow?,
    val counts: TrayCounts,
    val list: String?,
    val rows: List<WidgetTaskRow>,
    val focus: FocusRow? = null, // a focus session (on every device): the popup shows just it
    val now: Long = 0, // the server's clock, for the countdown
    // Quick add's Folder chip: folders and open checklists, starting on Personal. Only with a list.
    val folders: List<FolderRow> = emptyList(),
    val defaultFolder: Long? = null
)

@Serializable
data class FolderRow(val id: Long, val title: String, val checklist: Boolean = false)

// The session's task; `done`: it's finished, and every device asks what's next.
@Serializable
data class FocusRow(val id: Long, val title: String, val done: Boolean)

@Serializable
data class TrayCounts(val doing: Int, val active: Int, val all: Int)

private const val MAX_ROWS = 100
private val LISTS = setOf("doing", "active", "all")

// The Folder chip and friends, from the top bar's quick add (and its "Edit all details").
internal fun Parameters.quickAddChips() = QuickAddChips(
    star = this["star"] == "1", start = this["start"], due = this["due"], todayOnly = this["today"] == "1", folderId = this["folder"]?.toLongOrNull()
)

// The current task, with its timer (running until timerEndsAt, or paused with timerRemaining left).
@Serializable
data class PinnedRow(val id: Long, val title: String, val timerEndsAt: Long? = null, val timerRemaining: Long? = null, val durationMinutes: Int? = null)

// list: "doing", "active", "all", or anything else for none (the popup is closed: just the pin and counts).
fun trayState(service: TaskService, list: String?): TrayState {
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    val active = service.active()
    val doing = filterDoing(active, service.now(), byId, service.contextIdsByTask())
    val allOpen = TodoWidgetPresenter.allOpen(all, byId)
    fun rows(tasks: List<Task>, urgentOnTop: Boolean): List<WidgetTaskRow> {
        val contexts = service.contextIdsByTask()
        return TodoWidgetPresenter.toRows(
            tasks, subtaskCounts(all), service.now(), byId, folderColorsArgb(all), { resolveEffective(it, byId, contexts).effectiveDueDate }, urgentOnTop
        ).take(MAX_ROWS)
    }
    val rows = when (list) {
        "doing" -> rows(doing, urgentOnTop = true)
        "active" -> rows(active, urgentOnTop = true)
        // Like the widget's All: by folder, with nothing moved to the top.
        "all" -> rows(allOpen, urgentOnTop = false)
        else -> emptyList()
    }
    val pinned = pinnedTask(all)?.let { PinnedRow(it.id, it.title, it.timerEndsAt, it.timerRemaining, it.durationMinutes) }
    val focus = CurrentTask.focusSession(all)?.let { FocusRow(it.id, it.title, it.isComplete) }
    val folders = if (list !in LISTS) emptyList() else all.filter { it.type == TaskType.FOLDER || (it.type == TaskType.CHECKLIST && !it.isComplete) }
        .sortedBy { it.title.lowercase() }.map { FolderRow(it.id, it.title, checklist = it.type == TaskType.CHECKLIST) }
    val defaultFolder = if (list !in LISTS) null else service.findFolder(DEFAULT_FOLDER)?.id
    return TrayState(pinned, TrayCounts(doing.size, active.size, allOpen.size), list, rows, focus, service.now(), folders, defaultFolder)
}

// Bearer-token protected like /sync, so it's served even where the web pages aren't.
fun Route.trayRoutes(service: TaskService, hasToken: (RoutingContext) -> Boolean) {
    suspend fun RoutingContext.act(action: (Long?) -> Unit) {
        if (!hasToken(this)) return call.respond(HttpStatusCode.Unauthorized)
        action(call.parameters["id"]?.toLongOrNull())
        call.respond(trayState(service, call.request.queryParameters["list"]))
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
    // Unpinning also stops the timer and leaves focus, on every device.
    post("/api/unpin") { act { service.unpin() } }
    // The shared timer (CurrentTask): starting one pins its task.
    post("/api/tasks/{id}/timer") { act { id -> id?.let { service.get(it)?.durationMinutes?.let { m -> service.startTimer(it, m) } } } }
    post("/api/timer/pause") { act { service.pauseTimer() } }
    post("/api/timer/resume") { act { service.resumeTimer() } }
    post("/api/timer/add") { act { call.request.queryParameters["minutes"]?.toIntOrNull()?.takeIf { it > 0 }?.let(service::addTime) } }
    // Focus, on every device; done keeps the session (it asks what's next), leaving is unpinning.
    post("/api/tasks/{id}/focus") { act { id -> id?.let(service::focus) } }
    post("/api/focus/done") { act { service.focusSession()?.takeUnless { it.isComplete }?.let { service.completeWithDescendants(it.id) } } }
    post("/api/quickadd") {
        if (!hasToken(this)) return@post call.respond(HttpStatusCode.Unauthorized)
        val params = call.receiveParameters()
        val add = service.planQuickAdd(params["text"].orEmpty(), params.quickAddChips())
        service.add(add, defaultParent = service.findFolder(DEFAULT_FOLDER)?.id, star = params["mode"] == "doing")
        call.respond(trayState(service, params["mode"]))
    }
}
