package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.quickadd.quickAddTargets
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.formatDuration
import io.ktor.http.Parameters
import com.kzhovn.todoapp.data.CurrentTask
import com.kzhovn.todoapp.data.pinnedTask
import com.kzhovn.todoapp.data.folderTree
import com.kzhovn.todoapp.data.inMode
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.CompleteQuestion
import com.kzhovn.todoapp.data.completeQuestion
import com.kzhovn.todoapp.repository.openDescendants
import com.kzhovn.todoapp.server.web.completeOrDecide
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
    val defaultFolder: Long? = null,
    // Folder mode: the folder every device is zoomed into, and the folders to pick one from (indented
    // by depth, in tree order). Modes only with a list.
    val mode: FolderRow? = null,
    val modes: List<FolderRow> = emptyList()
)

@Serializable
data class FolderRow(val id: Long, val title: String, val checklist: Boolean = false, val depth: Int = 0)

// The session's task; `done`: it's finished, and every device asks what's next. `notes`: the start of
// its note, for the focus card.
@Serializable
// subtaskQuestion, here and on PinnedRow and the rows: what ticking it asks first (completeQuestion), answered
// with ?subtasks=complete or promote.
data class FocusRow(val id: Long, val title: String, val done: Boolean, val notes: String? = null, val subtaskQuestion: CompleteQuestion? = null)

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
data class PinnedRow(val id: Long, val title: String, val timerEndsAt: Long? = null, val timerRemaining: Long? = null, val durationMinutes: Int? = null, val durationText: String? = null, val subtaskQuestion: CompleteQuestion? = null)

// list: "doing", "active", "all", or anything else for none (the popup is closed: just the pin and counts).
fun trayState(service: TaskService, list: String?): TrayState {
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    val active = service.active()
    val doing = service.doing(active)
    val mode = service.modeFolder()
    val allOpen = TodoWidgetPresenter.allOpen(all, byId).filter { inMode(it, mode?.id, byId) }
    fun rows(tasks: List<Task>, urgentOnTop: Boolean) = TodoWidgetPresenter.rows(tasks, all, service.dependencyEdges(), service.now(), urgentOnTop).take(MAX_ROWS)
    val rows = when (list) {
        "doing" -> rows(doing, urgentOnTop = true)
        "active" -> rows(active, urgentOnTop = true)
        // Like the widget's All: by folder, with nothing moved to the top.
        "all" -> rows(allOpen, urgentOnTop = false)
        else -> emptyList()
    }
    fun question(t: Task) = completeQuestion(t, openDescendants(t.id, all).size)
    val pinned = pinnedTask(all)?.let { PinnedRow(it.id, it.title, it.timerEndsAt, it.timerRemaining, it.durationMinutes, it.durationMinutes?.let(::formatDuration), question(it)) }
    val focus = CurrentTask.focusSession(all)?.let { FocusRow(it.id, it.title, it.isComplete, it.notes?.takeIf { n -> n.isNotBlank() }?.lines()?.take(4)?.joinToString("\n")?.take(300), question(it)) }
    // The quick add and mode pickers only come with a list.
    val withList = list in LISTS
    val folders = if (!withList) emptyList() else quickAddTargets(all)
        .sortedBy { it.title.lowercase() }.map { FolderRow(it.id, it.title, checklist = it.type == TaskType.CHECKLIST) }
    val defaultFolder = if (!withList) null else service.defaultFolderId()
    // The same folders, in the same tree, as the phone's and the web's mode pickers.
    val modes = if (!withList) emptyList() else folderTree(all.filter { it.type == TaskType.FOLDER }).map { (f, depth) -> FolderRow(f.id, f.title, depth = depth) }
    return TrayState(pinned, TrayCounts(doing.size, active.size, allOpen.size), list, rows, focus, service.now(), folders, defaultFolder, mode?.let { FolderRow(it.id, it.title) }, modes)
}

// Bearer-token protected like /sync, so it's served even where the web pages aren't.
fun Route.trayRoutes(service: TaskService, hasToken: (RoutingContext) -> Boolean) {
    suspend fun RoutingContext.act(action: (Long?) -> Unit) {
        if (!hasToken(this)) return call.respond(HttpStatusCode.Unauthorized)
        action(call.parameters["id"]?.toLongOrNull())
        call.respond(trayState(service, call.request.queryParameters["list"]))
    }
    get("/api/tray") { act {} }
    // A row's checkbox. One with open subtasks is asked about in the popup first (its subtaskQuestion), and
    // comes with ?subtasks=complete or promote; without, its subtasks complete too (the top bar before the
    // question, or one opened since it last drew).
    post("/api/tasks/{id}/complete") {
        act { id -> service.get(id ?: return@act)?.let { if (it.isComplete) service.uncomplete(it.id) else service.completeOrDecide(it.id, call.request.queryParameters["subtasks"] ?: "complete") } }
    }
    // An expanded row's subtask or checklist item: just this one, unless answered as above.
    post("/api/tasks/{id}/toggle-item") {
        act { id -> service.get(id ?: return@act)?.let { if (it.isComplete) service.uncomplete(it.id) else service.completeOrDecide(it.id, call.request.queryParameters["subtasks"]) } }
    }
    post("/api/tasks/{id}/star") { act { id -> id?.let(service::toggleStar) } }
    post("/api/tasks/{id}/pin") { act { id -> id?.let(service::pin) } }
    // Unpinning also stops the timer and leaves focus, on every device.
    post("/api/unpin") { act { service.unpin() } }
    // The shared timer (CurrentTask): starting one pins its task.
    post("/api/tasks/{id}/timer") { act { id -> id?.let(service::startTimer) } }
    post("/api/timer/pause") { act { service.pauseTimer() } }
    post("/api/timer/resume") { act { service.resumeTimer() } }
    post("/api/timer/add") { act { call.request.queryParameters["minutes"]?.toIntOrNull()?.takeIf { it > 0 }?.let(service::addTime) } }
    // Focus, on every device; done keeps the session (it asks what's next), leaving is unpinning.
    post("/api/tasks/{id}/focus") { act { id -> id?.let(service::focus) } }
    post("/api/focus/done") { act { service.focusSession()?.takeUnless { it.isComplete }?.let { service.completeOrDecide(it.id, call.request.queryParameters["subtasks"] ?: "complete") } } }
    // Folder mode, on every device: ?folder=<id>, or none to leave it.
    post("/api/mode") { act { service.setMode(call.request.queryParameters["folder"]?.toLongOrNull()) } }
    post("/api/quickadd") {
        if (!hasToken(this)) return@post call.respond(HttpStatusCode.Unauthorized)
        val params = call.receiveParameters()
        val add = service.planQuickAdd(params["text"].orEmpty(), params.quickAddChips())
        service.add(add, defaultParent = service.defaultFolderId(), star = params["mode"] == "doing")
        call.respond(trayState(service, params["mode"]))
    }
}
