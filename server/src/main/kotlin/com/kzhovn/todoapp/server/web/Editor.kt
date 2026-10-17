package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.DAY_MS
import com.kzhovn.todoapp.data.monthDay
import com.kzhovn.todoapp.data.dueText
import com.kzhovn.todoapp.data.resolvesText
import com.kzhovn.todoapp.data.isTimedWait
import com.kzhovn.todoapp.data.folderOf
import com.kzhovn.todoapp.recurrence.recurrenceToSave
import com.kzhovn.todoapp.server.quickAddChips
import com.kzhovn.todoapp.quickadd.QuickAdd
import com.kzhovn.todoapp.data.reminderSummary
import com.kzhovn.todoapp.data.beforeDueText
import com.kzhovn.todoapp.repository.changedInheritedFields
import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.recurrence.ordinal
import com.kzhovn.todoapp.recurrence.WEEKDAY_NAMES
import com.kzhovn.todoapp.recurrence.NTH_NAMES
import com.kzhovn.todoapp.recurrence.recurrencePresets
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.mergeEdit
import kotlinx.html.summary
import kotlinx.html.details
import com.kzhovn.todoapp.data.formatDuration
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.hasTime
import com.kzhovn.todoapp.data.chipDate
import com.kzhovn.todoapp.data.nextRollover
import com.kzhovn.todoapp.data.ancestors
import com.kzhovn.todoapp.data.DueChoice
import com.kzhovn.todoapp.data.SnoozeChoice
import com.kzhovn.todoapp.data.folderTree
import com.kzhovn.todoapp.data.subtaskCounts
import com.kzhovn.todoapp.data.LinkKind
import com.kzhovn.todoapp.data.linkCandidates
import com.kzhovn.todoapp.data.nearbyFirst
import com.kzhovn.todoapp.data.checklistGroups
import com.kzhovn.todoapp.data.itemsIn
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.CHECK_IN_PRESETS
import com.kzhovn.todoapp.data.MAX_CHECK_IN_DAYS
import com.kzhovn.todoapp.data.nextCheckIn
import com.kzhovn.todoapp.data.checkInEvery
import com.kzhovn.todoapp.data.checkInText
import com.kzhovn.todoapp.data.stillWaiting
import com.kzhovn.todoapp.data.moveOutFolderId
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.RecurrenceUnit
import com.kzhovn.todoapp.recurrence.recurrenceSelectionFromTask
import com.kzhovn.todoapp.recurrence.toTaskFields
import com.kzhovn.todoapp.repository.InheritedField
import com.kzhovn.todoapp.server.TaskService
import com.kzhovn.todoapp.sync.SyncJson
import com.kzhovn.todoapp.sync.SyncRow
import com.kzhovn.todoapp.sync.TASKS
import com.kzhovn.todoapp.sync.contextIds
import com.kzhovn.todoapp.sync.dependsOn
import com.kzhovn.todoapp.sync.taskFields
import com.kzhovn.todoapp.sync.toTask
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.classes
import kotlinx.html.dateInput
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.numberInput
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.radioInput
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.textArea
import kotlinx.html.textInput
import kotlinx.html.timeInput
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// The task editor: one plain form for the task's own fields, plus htmx actions for its subtasks and
// dependents, which (like the phone's) take effect immediately.

private class EditState(val task: Task, val contextIds: Set<Long>, val dependsOn: Set<Long>) {
    // Travels in the form as the task was when the page loaded, so a save applies only what the
    // user changed and doesn't undo edits synced from the phone in the meantime.
    fun encode() = taskFields(task, contextIds, dependsOn).toString()
}

private fun decodeState(id: Long, json: String?): EditState? = runCatching {
    val row = SyncRow(TASKS, id, SyncJson.parseToJsonElement(json!!).jsonObject)
    EditState(row.toTask(), row.contextIds(), row.dependsOn())
}.getOrNull()

private fun TaskService.editState(id: Long): EditState? =
    get(id)?.let { EditState(it, contextIdsByTask()[id].orEmpty(), dependsOn(id)) }

private data class EditorView(
    val mode: ListMode,
    val list: ListData, // shown beside the editor; also where Save, Cancel and Delete return to
    val shown: EditState,
    val recurrence: RecurrenceSelection,
    val base: String,
    val error: String? = null,
    // Subtasks with their own value for changed inherited fields: "update them too?"
    val ask: Pair<Int, Set<InheritedField>>? = null,
    val needFirstStep: Boolean = false,
    val firstStep: String = "",
    val newSubtasks: String = "", // a new task's subtasks, one per line, created with it
    val newDep: String = "", // a new task this one depends on
    val newDependent: String = "", // a new task that depends on this one
    val pin: Boolean = false // a new task's pin, applied when it's saved
)

fun Route.editorRoutes(service: TaskService) {
    get("/repeat-preview") {
        val p = call.request.queryParameters
        val anchor = dateTime(p["startDate"], p["startTime"]) ?: dateTime(p["dueDate"], p["dueTime"]) ?: service.now()
        call.respondText(createHTML().div { repeatPreview(parseRecurrence(p), anchor, service.now()) }.removePrefix("<div>").removeSuffix("</div>"), ContentType.Text.Html)
    }

    get("/tasks/{id}") {
        val state = call.taskId()?.let(service::editState) ?: return@get call.respond(HttpStatusCode.NotFound)
        val recurrence = recurrenceSelectionFromTask(state.task.recurrenceType, state.task.recurrenceRule)
        call.respondHtml { editorPage(service, EditorView(call.mode(), call.listData(service, call.mode(), selected = state.task.id), state, recurrence, state.encode())) }
    }

    // A blank editor, like the phone's "+ Project" / "+ Folder". Made from a folder's view, it goes in
    // that folder; otherwise a new task goes in Personal, as quick add's do, and a project or folder
    // starts at the top. With `text` (the top bar's "Edit all details"), a draft of that quick add.
    get("/tasks/new") {
        // ?template=1: "+ New template" (a checklist unless said otherwise: most are lists, packing).
        val asTemplate = call.request.queryParameters["template"] != null
        val type = call.request.queryParameters["type"]?.let { runCatching { TaskType.valueOf(it) }.getOrNull() } ?: if (asTemplate) TaskType.CHECKLIST else TaskType.TASK
        val parent = call.folder()?.takeIf { service.get(it)?.type == TaskType.FOLDER } ?: if (type == TaskType.TASK) service.defaultFolderId() else null
        val draft = call.request.queryParameters["text"]?.let { text ->
            service.planQuickAdd(text, call.request.queryParameters.quickAddChips()).let { add -> add.takeIf { it.task != null } ?: QuickAdd(Task(title = text)) }
        }
        val task = draft?.task?.let { it.copy(parentId = it.parentId ?: parent) } ?: Task(title = "", type = type, parentId = parent, isTemplate = asTemplate)
        val state = EditState(task, draft?.contextIds.orEmpty(), emptySet())
        val recurrence = recurrenceSelectionFromTask(task.recurrenceType, task.recurrenceRule)
        call.respondHtml {
            editorPage(service, EditorView(call.mode(), call.listData(service, call.mode()), state, recurrence, state.encode(), newSubtasks = draft?.items?.joinToString("\n").orEmpty(), pin = draft?.pin == true))
        }
    }
    post("/tasks/new") { saveTask(service, id = null) }
    post("/tasks/{id}") { saveTask(service, call.taskId() ?: return@post call.respond(HttpStatusCode.NotFound)) }
    // Auto-save, a moment after each change in an open editor. Writes only what changed (so another
    // device's edit made meanwhile isn't undone), and skips anything that needs a question first
    // (a project's first step, updating subtasks), leaving that to Save. Replies with the new base.
    post("/tasks/{id}/autosave") {
        val id = call.taskId() ?: return@post
        val current = service.editState(id) ?: return@post call.respond(HttpStatusCode.NoContent)
        val params = call.receiveParameters()
        val base = decodeState(id, params["base"]) ?: current
        val form = parseForm(params, base, parseRecurrence(params), service)
        val merged = merge(base, form, current)
        val changed = changedInheritedFields(base.task, form.task, base.contextIds, form.contextIds)
        val needsQuestion = (merged.task.type == TaskType.PROJECT && service.tasks().none { it.parentId == id }) ||
            service.overridingSubtasks(id, changed).first.isNotEmpty()
        val status = when {
            form.task.title.isBlank() || merged == current -> null
            needsQuestion -> "Not saved: press Save"
            else -> { service.edit(merged.task, merged.contextIds, merged.dependsOn); "Saved" }
        }
        val stored = if (status == "Saved") service.editState(id) ?: current else base
        call.respondText(createHTML().div {
            hiddenInput(name = "base") { this.id = "base"; value = stored.encode(); attributes["form"] = "editor-form"; attributes["hx-swap-oob"] = "true" }
            span { this.id = "save-status"; attributes["hx-swap-oob"] = "true"; status?.let { +it } }
        }, ContentType.Text.Html)
    }

    // No confirmation: the list it lands on offers Undo instead.
    post("/tasks/{id}/delete") {
        call.taskId()?.let(service::delete)
        val back = call.listPath()
        call.respondRedirect("$back${if ('?' in back) '&' else '?'}deleted=${call.taskId()}")
    }
    post("/tasks/{id}/restore") {
        call.taskId()?.let(service::restore)
        call.respondList(service, call.mode(), extra = createHTML().div { id = "toast"; attributes["hx-swap-oob"] = "true" })
    }

    // Unlinks a prerequisite (this task waits on other) or a dependent (other waits on this task).
    post("/tasks/{id}/{kind}/{other}/remove") {
        val id = call.taskId() ?: return@post
        val other = call.parameters["other"]?.toLongOrNull()
        if (other != null) when (call.parameters["kind"]) {
            "prerequisite" -> service.removeDependency(id, other)
            "dependent" -> service.removeDependency(other, id)
        }
        call.respondSubtasks(service, id)
    }
    // The one add field: `kind` picks subtask, prerequisite or dependent; `existing` (a suggestion) or
    // `text` (a new task, through quick add; pasted lines make a subtask each).
    post("/tasks/{id}/related") {
        val id = call.taskId() ?: return@post
        val p = call.receiveParameters()
        val existing = p["existing"]?.toLongOrNull()
        val text = p["text"].orEmpty()
        val task = service.get(id)
        val kind = p["kind"] ?: "subtask"
        if (task != null) when (kind) {
            "prerequisite" -> existing?.let { service.addDependency(id, it) } ?: service.addTyped(text, folder = folderOf(task, service.tasks().associateBy { it.id }))?.let { service.addDependency(id, it.id) }
            "dependent" -> existing?.let { service.addDependency(it, id) } ?: service.addTyped(text, folder = folderOf(task, service.tasks().associateBy { it.id }))?.let { service.addDependency(it.id, id) }
            else -> existing?.let { service.reparent(it, id) } ?: text.lines().filter { it.isNotBlank() }.forEach { service.addTyped(it, under = id) }
        }
        call.respondSubtasks(service, id, focusAddItem = true, kind = kind)
    }
    // The add field's suggestions: existing tasks matching what's typed, that can be linked this way.
    get("/tasks/{id}/related/suggest") {
        val id = call.taskId() ?: return@get
        val q = call.request.queryParameters["text"].orEmpty().trim()
        val kind = call.request.queryParameters["kind"] ?: "subtask"
        val all = service.tasks()
        val byId = all.associateBy { it.id }
        val task = byId[id] ?: return@get
        val candidates = linkCandidates(task, LINK_KINDS[kind] ?: LinkKind.SUBTASK, all, service.dependencyEdges())
        // Typed: what matches. Empty (the field just focused): the nearest few first, as the phone's picker.
        val sections = if (q.isEmpty()) nearbyFirst(task, candidates, all, service.now()).filter { it.first != Labels.EVERYTHING_ELSE }.map { (label, ts) -> label to ts.take(5) }
            else listOf(null to candidates.filter { it.title.contains(q, ignoreCase = true) }.take(8))
        call.respondText(createHTML().div(classes = "suggest") {
            if (q.isNotEmpty()) button(type = ButtonType.submit, classes = "sugg new") { +"↵ New ${KIND_NOUNS[kind] ?: "subtask"} “$q”" }
            sections.forEach { (label, matches) ->
                label?.let { div(classes = "sugg-head") { +it } }
                matches.forEach { t ->
                    button(type = ButtonType.submit, classes = "sugg") {
                        name = "existing"; value = t.id.toString()
                        span { +t.title }
                        span(classes = "where") { +ancestors(t, byId).joinToString(" › ") { it.title } }
                    }
                }
            }
        }, ContentType.Text.Html)
    }
    // A subtask's ✕ menu: move it out to this task's folder (and with unlink=1 drop its dependency on or
    // from this task too), or delete it.
    post("/tasks/{id}/subtasks/{sub}/moveout") {
        val id = call.taskId() ?: return@post
        val task = service.get(id)
        call.parameters["sub"]?.toLongOrNull()?.let(service::get)?.takeIf { task != null && it.parentId == id }?.let { sub ->
            service.reparent(sub.id, moveOutFolderId(task!!, service.tasks().associateBy { t -> t.id }))
            if (call.request.queryParameters["unlink"] == "1") { service.removeDependency(id, sub.id); service.removeDependency(sub.id, id) }
        }
        call.respondSubtasks(service, id)
    }
    // A subtask or item (and what's under it) goes, offered back in a toast.
    post("/tasks/{id}/subtasks/{sub}/delete") {
        val id = call.taskId() ?: return@post
        val sub = call.parameters["sub"]?.toLongOrNull()?.let(service::get)?.takeIf { it.parentId == id } ?: return@post call.respondSubtasks(service, id)
        service.delete(sub.id)
        call.respondSubtasks(service, id) {
            this.id = "toast"; attributes["hx-swap-oob"] = "true"; classes = setOf("show")
            span { +Labels.deleted(sub.title) }
            button { hx("post", "/tasks/$id/subtasks/${sub.id}/restore?mode=${call.listQuery()}", "#related"); +Labels.UNDO }
        }
    }
    post("/tasks/{id}/subtasks/{sub}/restore") {
        val id = call.taskId() ?: return@post
        call.parameters["sub"]?.toLongOrNull()?.let(service::restore)
        call.respondSubtasks(service, id, toast = { this.id = "toast"; attributes["hx-swap-oob"] = "true" })
    }
    // Drag (or Alt+arrows): the subtask goes right before/after another of this task's subtasks.
    post("/tasks/{id}/subtasks/{sub}/move") {
        val id = call.taskId() ?: return@post
        val p = call.receiveParameters()
        val sub = call.parameters["sub"]?.toLongOrNull()
        // A subtask, or a checklist's item or section heading (an item drags into a section, see planMoveItem).
        fun ofThis(t: Task?) = t != null && (t.parentId == id || t.parentId?.let(service::get)?.takeIf { it.type == TaskType.SECTION }?.parentId == id)
        val anchor = p["anchor"]?.toLongOrNull()?.let(service::get)?.takeIf(::ofThis)
        if (sub != null && anchor != null && ofThis(service.get(sub))) service.moveNextTo(sub, anchor.id, after = p["after"] == "1")
        call.respondSubtasks(service, id)
    }
    // A checklist's items: add ("milk, eggs" is two), uncheck all, clear checked.
    // ?in=: into one of its sections ("+ Add to Clothes").
    post("/tasks/{id}/items") {
        val id = call.taskId() ?: return@post
        val text = call.receiveParameters()["text"].orEmpty()
        val into = call.request.queryParameters["in"]?.toLongOrNull()?.let(service::get)?.takeIf { it.type == TaskType.SECTION && it.parentId == id }
        if (into != null) service.addItems(into.id, text) else if (service.get(id)?.type == TaskType.CHECKLIST) service.addItems(id, text)
        // The field stays open (and focused) for the next one.
        call.respondSubtasks(service, id, focusAddItem = into == null, addingTo = into?.id)
    }
    // A checklist's sections: add one, fold one (per browser, with the All tree's folds), rename, check all,
    // uncheck all. Deleting one is a subtask's delete (with Undo).
    post("/tasks/{id}/sections") {
        val id = call.taskId() ?: return@post
        call.receiveParameters()["text"]?.takeIf { it.isNotBlank() && service.get(id)?.type == TaskType.CHECKLIST }?.let { service.addSection(id, it) }
        call.respondSubtasks(service, id)
    }
    post("/tasks/{id}/sections/{sec}/{action}") {
        val id = call.taskId() ?: return@post
        val sec = call.parameters["sec"]?.toLongOrNull()?.let(service::get)?.takeIf { it.type == TaskType.SECTION && it.parentId == id } ?: return@post call.respondSubtasks(service, id)
        when (call.parameters["action"]) {
            "fold" -> call.toggleCollapsed(sec.id)
            "rename" -> call.receiveParameters()["text"]?.takeIf { it.isNotBlank() }?.let { name -> service.update(sec.id) { it.copy(title = name.trim()) } }
            "check" -> service.checkAll(sec.id)
            "uncheck" -> service.uncheckAll(sec.id)
            // Its +: the add field opens under its heading (unfolded, if it was folded).
            "adding" -> { if (sec.id in call.collapsedIds()) call.toggleCollapsed(sec.id); return@post call.respondSubtasks(service, id, addingTo = sec.id) }
        }
        call.respondSubtasks(service, id)
    }
    post("/tasks/{id}/items/uncheck") {
        val id = call.taskId() ?: return@post
        service.uncheckAll(id)
        call.respondSubtasks(service, id)
    }
    // Undoable, like a delete: a toast offers the items back.
    post("/tasks/{id}/items/clear") {
        val id = call.taskId() ?: return@post
        val count = itemsIn(id, service.tasks()).count { it.isComplete }
        val at = service.clearChecked(id)
        call.respondSubtasks(service, id, toast = at?.let { { clearedToast(id, count, it, call.mode()) } })
    }
    post("/tasks/{id}/items/restore") {
        val id = call.taskId() ?: return@post
        call.request.queryParameters["at"]?.toLongOrNull()?.let { service.restoreCleared(id, it) }
        call.respondSubtasks(service, id, toast = { this.id = "toast"; attributes["hx-swap-oob"] = "true" })
    }
    // Completing a checklist (on purpose, from its editor); unchecked items move to a new copy or are completed too.
    post("/tasks/{id}/complete-list") {
        val id = call.taskId() ?: return@post
        service.completeChecklist(id, moveUncheckedToNewList = call.request.queryParameters["move"] == "1")
        call.respondRedirect(call.listPath())
    }
    // Ticks a subtask, prerequisite or dependent off (or back on) from this task's editor.
    post("/tasks/{id}/subtasks/{sub}/toggle") {
        val id = call.taskId() ?: return@post
        val linked = service.dependencyEdges().filter { it.taskId == id || it.dependsOnTaskId == id }.flatMap { listOf(it.taskId, it.dependsOnTaskId) }.toSet()
        // A subtask, one under a subtask (shown nested), or a linked task.
        val sub = call.parameters["sub"]?.toLongOrNull()?.let(service::get)?.takeIf { it.parentId == id || it.parentId?.let(service::get)?.parentId == id || it.id in linked }
            ?: return@post call.respondSubtasks(service, id)
        // Open subtasks of its own are asked about first, as in the lists.
        val choice = call.request.queryParameters["subtasks"]
        val open = if (sub.isComplete) 0 else service.activeDescendantCount(sub.id)
        if (open > 0 && choice == null) return@post call.respondSubtasks(service, id) {
            askSubtasksToast(sub, open, "/tasks/$id/subtasks/${sub.id}/toggle?mode=${call.listQuery()}", target = "#related")
        }
        if (sub.isComplete) service.uncomplete(sub.id) else service.completeOrDecide(sub.id, choice)
        call.respondSubtasks(service, id, toast = { this.id = "toast"; attributes["hx-swap-oob"] = "true" }) // an answered question goes
    }
}

// Saves the editor's form, to the task `id` or (null) to a new one.
private suspend fun RoutingContext.saveTask(service: TaskService, id: Long?) {
    val mode = call.mode()
    // Deleted elsewhere while the editor was open.
    val current = if (id == null) null else service.editState(id) ?: return call.respondRedirect(call.listPath())
    val params = call.receiveParameters()
    val base = decodeState(id ?: 0, params["base"]) ?: current ?: EditState(Task(title = ""), emptySet(), emptySet())
    val recurrence = parseRecurrence(params)
    val form = parseForm(params, base, recurrence, service)
    val firstStep = params["firstStep"]?.trim().orEmpty()
    val newSubtasks = params["newSubtasks"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val newDep = params["newDep"]?.trim().orEmpty()
    val newDependent = params["newDependent"]?.trim().orEmpty()
    val view = EditorView(mode, call.listData(service, mode, selected = id), form, recurrence, base.encode(), firstStep = firstStep, newSubtasks = params["newSubtasks"].orEmpty(), newDep = newDep, newDependent = newDependent, pin = params["pin"] != null)
    suspend fun reshow(v: EditorView) = call.respondHtml { editorPage(service, v) }

    if (form.task.title.isBlank()) return reshow(view.copy(error = "A title is required."))
    val merged = merge(base, form, current ?: base)
    // A project needs a step, asked only when it's saved without one, like the phone.
    val isProject = merged.task.type == TaskType.PROJECT
    if (id == null && isProject && newSubtasks.isEmpty()) return reshow(view.copy(error = "A project needs a first step: add a subtask."))
    val needsFirstStep = id != null && isProject && service.tasks().none { it.parentId == id }
    if (needsFirstStep && firstStep.isBlank()) {
        return reshow(view.copy(needFirstStep = true, error = "A project needs a first step."))
    }
    val (overriding, changed) = if (id == null) emptyList<Task>() to emptySet() else
        service.overridingSubtasks(id, changedInheritedFields(base.task, form.task, base.contextIds, form.contextIds))
    val inherit = params["inherit"]
    if (changed.isNotEmpty() && inherit == null) return reshow(view.copy(ask = overriding.size to changed))

    // A new task's prerequisite and dependent, typed in the form: made in its folder, as related work
    // usually belongs together.
    val folderId = folderOf(merged.task, service.tasks().associateBy { it.id })
    fun createTyped(text: String) = service.addTyped(text, folder = folderId)?.id
    val blocker = createTyped(newDep)
    val dependsOn = merged.dependsOn + listOfNotNull(blocker)
    val savedId = if (id == null) {
        // Created first, then edited, so contexts and dependencies go through edit()'s checks.
        service.create(merged.task).id.also { service.edit(merged.task.copy(id = it), merged.contextIds, dependsOn) }
    } else {
        service.edit(merged.task, merged.contextIds, dependsOn)
        id
    }
    if (inherit == "update") service.clearInherited(overriding, changed)
    if (needsFirstStep) service.addTyped(firstStep, under = savedId)
    if (params["resolve"] != null) service.complete(savedId)
    if (id == null && params["pin"] != null) service.pin(savedId)
    // A new checklist's lines are its items (see checklistItem); a task's, its subtasks.
    newSubtasks.forEach { service.addTyped(it, under = savedId) } // a new checklist's lines are its items (addTyped)
    createTyped(newDependent)?.let { service.addDependency(it, savedId) }
    call.respondRedirect(call.listPath())
}

// `toast`: an out-of-band #toast to go with it.
private suspend fun io.ktor.server.application.ApplicationCall.respondSubtasks(
    service: TaskService, id: Long, focusAddItem: Boolean = false, kind: String = "subtask", addingTo: Long? = null, toast: (DIV.() -> Unit)? = null
) = respondText(createHTML().div { relatedSection(service, id, mode(), focusAddItem, kind, collapsedIds(), addingTo) } + toast?.let { createHTML().div(block = it) }.orEmpty(), ContentType.Text.Html)

// "Deleted 3 checked items · Undo" after Clear checked.
private fun DIV.clearedToast(checklistId: Long, count: Int, deletedAt: Long, mode: ListMode) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    classes = setOf("show")
    span { +"Deleted $count checked item${if (count == 1) "" else "s"}" }
    button {
        hx("post", "/tasks/$checklistId/items/restore?at=$deletedAt&mode=${mode.name}", "#related")
        +Labels.UNDO
    }
}

private val LINK_KINDS = mapOf("subtask" to LinkKind.SUBTASK, "prerequisite" to LinkKind.PREREQUISITE, "dependent" to LinkKind.DEPENDENT)
private val KIND_NOUNS = mapOf("subtask" to "subtask", "prerequisite" to "prerequisite", "dependent" to "dependent task")

// A prerequisite's (amber) or dependent's (green) tinted tag.
private fun FlowContent.kindTag(kind: String) =
    span(classes = "kind-tag ${if (kind == Labels.DEPENDENT) "dependent" else "prerequisite"}") { +kind }


private fun Parameters.ids(name: String) = getAll(name).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

private fun parseForm(p: Parameters, base: EditState, recurrence: RecurrenceSelection, service: TaskService): EditState {
    val (recurrenceType, recurrenceRule) = recurrenceToSave(recurrence, base.task)
    val type = p["type"]?.let { runCatching { TaskType.valueOf(it) }.getOrNull() } ?: base.task.type
    // A waiting item's date is "Resolves on" (Task.typeRule drops its star and priority on save).
    val waiting = type == TaskType.WAITING
    val baseCheckIn = localDateTime(nextCheckIn(base.task, service.now())).toLocalDate().toString()
    val task = base.task.copy(
        title = p["title"].orEmpty().trim(),
        type = type,
        isStarred = p["starred"] != null,
        // A waiting item's start is its next check-in: kept to the minute unless its day was changed.
        startDate = if (!waiting) dateTime(p["startDate"], p["startTime"])
            else if (p["checkInDate"] == baseCheckIn) base.task.startDate else dateTime(p["checkInDate"], null),
        dueDate = if (waiting) dateTime(p["resolvesDate"], p["resolvesTime"]) else dateTime(p["dueDate"], p["dueTime"]),
        checkInDays = if (!waiting) base.task.checkInDays
            else p["checkInN"]?.toIntOrNull()?.let { it * (p["checkInUnit"]?.toIntOrNull() ?: 1) }?.coerceIn(1, MAX_CHECK_IN_DAYS),
        reminderOffsetMinutes = p["reminder"]?.toIntOrNull(),
        remindAtStart = p["remindStart"] != null,
        // A running timed wait's reminder is its own switch (the Remind pill is hidden for waiting items).
        remindAt = if (waiting && p["waitRemind"] != null) dateTime(p["resolvesDate"], p["resolvesTime"])
            else if (waiting && base.task.isTimedWait && base.task.dueDate != null) null else dateTime(p["remindAtDate"], p["remindAtTime"]),
        parentId = p["parent"]?.toLongOrNull(),
        recurrenceType = recurrenceType,
        recurrenceRule = recurrenceRule,
        isMaybe = p["priority"] == "maybe",
        isHighPriority = p["priority"] == "high",
        // Two Timer pills, the waiting item's first (see timerPill's callers): the one for the type picked.
        durationMinutes = p.getAll("duration").orEmpty().let { if (waiting) it.firstOrNull() else it.lastOrNull() }?.toIntOrNull()?.takeIf { it > 0 },
        expiresAt = if (p["today"] != null) base.task.expiresAt ?: nextRollover(service.now(), service.rolloverHour()) else null,
        sequential = p["sequential"] != null,
        activeWithSubtasks = p["activeWithSubtasks"] != null,
        notes = p["notes"]?.replace("\r\n", "\n")?.trimEnd()?.ifBlank { null }
    )
    return EditState(task, p.ids("ctx"), base.dependsOn)
}

// Normalised so that an untouched picker compares equal to the task's own rule.
private fun parseRecurrence(p: Parameters): RecurrenceSelection {
    val n = p["n"]?.toIntOrNull()?.coerceIn(1, 999) ?: 1
    val unit = p["unit"]?.let { runCatching { RecurrenceUnit.valueOf(it) }.getOrNull() } ?: RecurrenceUnit.DAY
    return when (p["repeat"]?.let { runCatching { RecurrencePreset.valueOf(it) }.getOrNull() }) {
        RecurrencePreset.CALENDAR -> {
            val mask = if (unit != RecurrenceUnit.WEEK) 0 else p.getAll("wd").orEmpty().mapNotNull { it.toIntOrNull()?.takeIf { d -> d in 0..6 } }.fold(0) { m, d -> m or (1 shl d) }
            val nth = if (unit == RecurrenceUnit.MONTH && p["monthly"] == "nth") p["nth"]?.toIntOrNull()?.takeIf { it in -1..4 && it != 0 } ?: 1 else null
            RecurrenceSelection(
                RecurrencePreset.CALENDAR, n, unit, mask,
                monthlyNth = nth,
                monthlyWeekday = if (nth == null) 6 else p["mwd"]?.toIntOrNull()?.coerceIn(0, 6) ?: 6,
                until = if (p["ends"] == "until") dateTime(p["until"], null) else null,
                count = if (p["ends"] == "count") p["count"]?.toIntOrNull()?.coerceIn(1, 999) else null
            )
        }
        RecurrencePreset.AFTER_COMPLETION_N_DAYS -> RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n, unit)
        else -> RecurrenceSelection(RecurrencePreset.NONE)
    }
}

// A preset's form values, which app.js copies into the builder's fields.
private fun presetFields(r: RecurrenceSelection): String = buildList {
    add("\"repeat\":\"${r.preset.name}\""); add("\"n\":\"${r.n}\""); add("\"unit\":\"${r.unit.name}\"")
    add("\"wd\":[${(0..6).filter { r.weekdaysMask and (1 shl it) != 0 }.joinToString(",") { "\"$it\"" }}]")
    add("\"monthly\":\"${if (r.monthlyNth == null) "day" else "nth"}\""); add("\"ends\":\"never\"")
}.joinToString(",", "{", "}")

// "Next: Sat Oct 3 · Sat Nov 7 · Sat Dec 5"; re-rendered (hx) on every change in the popover. It
// also carries the pill's text (data-summary), read by app.js.
private fun FlowContent.repeatPreview(r: RecurrenceSelection, anchor: Long, now: Long) = div(classes = "rep-preview") {
    attributes["hx-get"] = "/repeat-preview"
    attributes["hx-trigger"] = "change from:closest .pop, input changed delay:300ms from:closest .pop"
    attributes["hx-include"] = "closest form"
    attributes["hx-swap"] = "outerHTML"
    attributes["hx-sync"] = "this:replace" // a newer change wins over an in-flight one
    attributes["data-summary"] = Labels.repeat(r).orEmpty()
    val (type, rule) = r.toTaskFields()
    val next = if (type == null || rule == null) emptyList() else RecurrenceEngine.preview(type, rule, anchor, now)
    span(classes = "menu-label") { +Labels.repeatPreviewLabel(afterCompletion = r.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS) }
    +Labels.repeatPreview(next)
}

// A date without a time is local midnight (see hasTime).
internal fun dateTime(date: String?, time: String?): Long? {
    val t = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    // A time alone means today, as in quick add ("start 5pm").
    val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now().takeIf { t != null } ?: return null
    return d.atTime(t ?: LocalTime.MIDNIGHT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

// Three-way merge: what the user changed in the form wins; anything else keeps its current value (see mergeEdit).
private fun merge(base: EditState, form: EditState, current: EditState): EditState =
    EditState(
        mergeEdit(base.task, form.task, current.task),
        if (form.contextIds != base.contextIds) form.contextIds else current.contextIds,
        if (form.dependsOn != base.dependsOn) form.dependsOn else current.dependsOn
    )

private fun localDateTime(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

// A date and time input's values ("2026-10-07", "17:30"); no time: an empty one (see hasTime).
private fun dateAndTime(millis: Long): Pair<String, String> = localDateTime(millis).let {
    it.toLocalDate().toString() to if (hasTime(millis)) it.toLocalTime().withSecond(0).withNano(0).toString() else ""
}

// The folder a task sits directly in, where its new related tasks go (related work usually belongs together).

// A note, collapsed (5 lines; 2 on a phone's screen) with links that open; clicking it edits the whole
// note (app.js). With no note it's only shown while the title card is being edited (CSS).
private fun FlowContent.notesField(notes: String?) = div(classes = "notes") {
    if (!notes.isNullOrBlank()) div(classes = "notes-preview") { linkified(notes) }
    textArea(classes = "notes-input") { name = "notes"; rows = "1"; placeholder = "Notes"; +notes.orEmpty() }
}

private fun HTML.editorPage(service: TaskService, v: EditorView) = shellPage(
    service, "Raspberry · ${v.shown.task.title.ifBlank { "New ${v.shown.task.type.name.lowercase()}" }}", v.list.path, v.list,
    detail = { editorPanel(service, v) }
) { listColumn(v.list) }

// The editor, in the detail panel beside the list. ✕ (or Esc) closes it, back to the list alone.
private fun FlowContent.editorPanel(service: TaskService, v: EditorView) {
    a(href = v.list.path, classes = "detail-close") { attributes["aria-label"] = "Close"; +"✕" }
    val t = v.shown.task
    val isNew = t.id == 0L
    val all = service.tasksAround(t.id)
    val byId = all.associateBy { it.id }
    val formId = "editor-form"
    // An open task saves itself a moment after each change (see /autosave); the form still submits
    // normally on Save, so this lives on its own element.
    if (!isNew) div {
        attributes["hx-post"] = "/tasks/${t.id}/autosave?mode=${v.list.q}"
        attributes["hx-trigger"] = "change delay:700ms from:.editor, change delay:700ms from:.editor-foot, input changed delay:1200ms from:.title-input, input changed delay:1200ms from:.notes-input"
        attributes["hx-include"] = "#$formId"
        attributes["hx-swap"] = "none"
        attributes["hx-sync"] = "this:replace"
    }
    form(action = "/tasks/${if (isNew) "new" else t.id}?mode=${v.list.q}", method = FormMethod.post, classes = "editor") {
        id = formId
        // Enter in a field "clicks" the form's first submit button. Without this one that's Delete (tied to
        // the form from the footer below), so Enter deleted the task; now it saves.
        button(type = ButtonType.submit, classes = "default-submit") { attributes["tabindex"] = "-1"; attributes["aria-hidden"] = "true"; +Labels.SAVE }
        hiddenInput(name = "base") { id = "base"; value = v.base }
        v.error?.let { p(classes = "error") { +it } }
        if (t.isTemplate && !isNew) templateBanner(t)
        v.ask?.let { (count, fields) ->
            div(classes = "ask") {
                p { +Labels.subtasksOverride(count, fields.joinToString(" and ") { it.label }) }
                button(type = ButtonType.submit, classes = "primary") { name = "inherit"; value = "update"; +Labels.UPDATE_SUBTASKS }
                button(type = ButtonType.submit) { name = "inherit"; value = "only"; +Labels.ONLY_THIS_TASK }
            }
        }

        // The title wraps (up to four lines), with the star inside the box by the first line. A starred
        // task is never a maybe (Maybe is under Properties); app.js unticks the other when one is ticked.
        // The note sits under it, in the same card.
        // Where it lives, outermost first; each step opens that folder or task.
        if (!isNew) ancestors(t, byId).takeIf { it.isNotEmpty() }?.let { path ->
            div(classes = "crumbs") { path.forEach { step -> a(href = "/tasks/${step.id}?mode=${v.list.q}") { +step.title }; span(classes = "sep") { +"›" } } }
        }
        div(classes = "title-card") {
        div(classes = "title-box") {
            // An existing task pins at once, like the phone's; a new one when it's saved.
            if (isNew) label(classes = "pin-toggle") { attributes["title"] = Labels.PIN; checkBoxInput(name = "pin") { checked = v.pin }; icon(Icon.PUSH_PIN, "") }
            else if (t.type.isDoable && !t.isComplete) {
                pinControl(t, service.pinned()?.id == t.id)
            }
            textArea(classes = "title-input") { name = "title"; rows = "1"; placeholder = Labels.TITLE; required = true; +t.title }
            label(classes = "flag-toggle star-toggle task-only not-waiting") {
                attributes["title"] = Labels.STAR
                checkBoxInput(name = "starred") { checked = t.isStarred }
                icon(Icon.STAR, "")
            }
        }
        notesField(t.notes)
        }

        // One joined bar, the chosen type filled: a pick-one set, unlike the pills below.
        div(classes = "type-seg") {
            Labels.TYPES.forEach { (type, label) ->
                label { radioInput(name = "type") { value = type.name; checked = t.type == type }; +label }
            }
        }

        // A waiting item's timing instead (CSS shows one or the other by the type picked): how often it comes
        // up for a check-in, or the day it resolves itself.
        div(classes = "field-label section waiting-only") { +Labels.WAITING }
        div(classes = "pills waiting-only") {
            val every = t.checkInEvery()
            // Presets fill the line below (app.js's .rep-preset handler) and close; changing the line moves the
            // next check-in to that far from today (app.js, as withCheckInEvery).
            popPill(Labels.CHECK_IN, Icon.REPEAT, Labels.checkIn(every), "checkin", "") {
                div(classes = "rep") {
                    val today = localDateTime(service.now()).toLocalDate()
                    div(classes = "rep-presets") {
                        CHECK_IN_PRESETS.forEach { days ->
                            val (n, unit) = if (days % 7 == 0) days / 7 to 7 else days to 1
                            button(type = ButtonType.button, classes = if (days == every) "rep-preset on" else "rep-preset") {
                                attributes["data-days"] = days.toString()
                                attributes["data-set"] = """{"checkInN":"$n","checkInUnit":"$unit","checkInDate":"${today.plusDays(days.toLong())}"}"""
                                +Labels.checkIn(days)
                                span(classes = "rep-when") { +monthDay(service.now() + days * DAY_MS) }
                            }
                        }
                    }
                    div(classes = "rep-line") {
                        val weeks = every % 7 == 0
                        +"Every"
                        numberInput(name = "checkInN", classes = "rep-n") { value = (if (weeks) every / 7 else every).toString(); min = "1"; max = MAX_CHECK_IN_DAYS.toString() }
                        label(classes = "pill") { radioInput(name = "checkInUnit") { value = "1"; checked = !weeks }; +"days" }
                        label(classes = "pill") { radioInput(name = "checkInUnit") { value = "7"; checked = weeks }; +"weeks" }
                    }
                    label(classes = "rep-line") {
                        +Labels.NEXT_CHECK_IN
                        dateInput(name = "checkInDate") { value = localDateTime(nextCheckIn(t, service.now())).toLocalDate().toString() }
                    }
                }
            }
            datePill(Labels.RESOLVES_ON, "resolves", Icon.CALENDAR, t.dueDate.takeIf { t.type == TaskType.WAITING }, "")
            // A timed wait (a workflow's 50 minutes): it resolves itself that long after it starts. Comes before the
            // task's own Timer pill, which parseForm relies on to tell the two "duration" fields apart.
            timerPill(t, "")
            if (t.isTimedWait && t.dueDate != null) togglePill("waitRemind", t.remindAt != null, Icon.BELL, Labels.REMIND_WHEN_DONE)
        }
        if (t.type == TaskType.WAITING && !isNew) p(classes = "hint waiting-only") {
            val now = service.now()
            val resolves = resolvesText(t)
            span { +(resolves ?: checkInText(t, now)) }
            // Restarts the clock: app.js moves the next check-in an interval from today, saved like any edit.
            if (resolves == null) button(type = ButtonType.button, classes = "still-waiting") {
                val next = stillWaiting(t, now)
                attributes["data-date"] = localDateTime(nextCheckIn(next, now)).toLocalDate().toString()
                attributes["data-hint"] = checkInText(next, now)
                +Labels.STILL_WAITING
            }
        }
        // Everything about when, as pills that open a small popover (app.js keeps their text current).
        div(classes = "field-label section not-waiting") { +Labels.TIMING }
        div(classes = "pills when not-waiting") {
            // Quick choices under the fields: the snooze menu's for Start, days for Due (the phone's long press).
            val now = service.now()
            fun at(ms: Long) = dateAndTime(ms)
            datePill(Labels.START, "start", Icon.CALENDAR, t.startDate, "", SnoozeChoice.entries.map { it.label to at(it.at(now, service.rolloverHour())) })
            datePill(Labels.DUE, "due", Icon.FLAG, t.dueDate, "task-only", DueChoice.entries.map { it.label to at(it.at(now)) })
            // Set here, rung by the phone: when it starts, before it's due, and/or at a time of its own. A
            // date with no time rings at the reminder hour in Settings.
            popPill(Labels.REMIND, Icon.BELL, reminderSummary(t, service.reminderHour()), "remind", "task-only reminder") {
                label { checkBoxInput(name = "remindStart") { checked = t.remindAtStart }; +" When it starts" }
                div(classes = "pop-label") { +"Before it's due" }
                select {
                    name = "reminder"
                    Labels.REMINDERS.forEach { (m, label) -> option { value = m?.toString().orEmpty(); selected = t.reminderOffsetMinutes == m; m?.let { attributes["data-short"] = beforeDueText(it) }; +label } }
                }
                div(classes = "pop-label") { +"At a time" }
                val (atDate, atTime) = t.remindAt?.let(::dateAndTime) ?: ("" to "")
                dateInput(name = "remindAtDate") { value = atDate }
                timeInput(name = "remindAtTime") { value = atTime }
                p(classes = "hint") { +"Rings on your phone. A date with no time rings at ${"%02d:00".format(service.reminderHour())} (Settings)." }
            }
            val r = v.recurrence
            // Presets on top (one click); "After completion…" and "Custom…" open the builder below.
            // The preview and the pill's text come from /repeat-preview, so the wording is Labels.repeat's.
            val anchor = t.startDate ?: t.dueDate ?: service.now()
            val presets = recurrencePresets(anchor)
            val building = r.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS || (r.preset == RecurrencePreset.CALENDAR && presets.none { it.second == r })
            popPill(Labels.REPEAT, Icon.REPEAT, Labels.repeat(r) ?: t.recurrenceType?.let { "Custom" }, "repeat", "repeat task-only") {
                div(classes = if (building) "rep building" else "rep") {
                    div(classes = "rep-presets") {
                        (listOf(Labels.DONT_REPEAT to RecurrenceSelection(RecurrencePreset.NONE)) + presets).forEach { (label, preset) ->
                            button(type = ButtonType.button, classes = if (preset == r) "rep-preset on" else "rep-preset") { attributes["data-set"] = presetFields(preset); +label }
                        }
                        button(type = ButtonType.button, classes = "rep-preset rep-open") {
                            attributes["data-set"] = presetFields(if (r.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS) r else RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 3))
                            +"${Labels.AFTER_COMPLETION}…"
                        }
                        button(type = ButtonType.button, classes = "rep-preset rep-open") {
                            attributes["data-set"] = presetFields(if (r.preset == RecurrencePreset.CALENDAR) r else RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.WEEK))
                            +"Custom…"
                        }
                    }
                    div(classes = "rep-builder") {
                        div(classes = "seg") {
                            label(classes = "rep-none") { radioInput(name = "repeat") { value = RecurrencePreset.NONE.name; checked = r.preset == RecurrencePreset.NONE } }
                            label { radioInput(name = "repeat") { value = RecurrencePreset.CALENDAR.name; checked = r.preset == RecurrencePreset.CALENDAR }; +Labels.ON_A_SCHEDULE }
                            label { radioInput(name = "repeat") { value = RecurrencePreset.AFTER_COMPLETION_N_DAYS.name; checked = r.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS }; +Labels.AFTER_COMPLETION }
                        }
                        div(classes = "rep-line") {
                            span(classes = "rep-sched") { +"Every" }
                            numberInput(name = "n", classes = "rep-n") { value = r.n.toString(); min = "1"; max = "999" }
                            listOf(RecurrenceUnit.DAY to "days", RecurrenceUnit.WEEK to "weeks", RecurrenceUnit.MONTH to "months").forEach { (unit, name) ->
                                label(classes = "pill") { radioInput(name = "unit") { value = unit.name; checked = r.unit == unit }; +name }
                            }
                            span(classes = "rep-after") { +Labels.AFTER_COMPLETION_SUFFIX }
                        }
                        div(classes = "rep-wd") {
                            Labels.WEEKDAYS.forEach { (bit, label) ->
                                label(classes = "pill") { checkBoxInput(name = "wd") { value = bit.toString(); checked = r.weekdaysMask and (1 shl bit) != 0 }; +label }
                            }
                        }
                        div(classes = "rep-month") {
                            val day = localDateTime(anchor).dayOfMonth
                            label { radioInput(name = "monthly") { value = "day"; checked = r.monthlyNth == null }; +"On the ${ordinal(day)}" }
                            label {
                                radioInput(name = "monthly") { value = "nth"; checked = r.monthlyNth != null }; +"On the "
                                select { name = "nth"; NTH_NAMES.forEach { (n, name) -> option { value = n.toString(); selected = (r.monthlyNth ?: 1) == n; +name } } }
                                select { name = "mwd"; WEEKDAY_NAMES.forEachIndexed { i, name -> option { value = i.toString(); selected = r.monthlyWeekday == i; +name } } }
                            }
                        }
                        div(classes = "rep-ends") {
                            span(classes = "menu-label") { +"Ends" }
                            label { radioInput(name = "ends") { value = "never"; checked = r.until == null && r.count == null }; +"Never" }
                            label {
                                radioInput(name = "ends") { value = "until"; checked = r.until != null }; +"On "
                                dateInput(name = "until") { value = r.until?.let { localDateTime(it).toLocalDate().toString() }.orEmpty() }
                            }
                            label {
                                radioInput(name = "ends") { value = "count"; checked = r.count != null }; +"After "
                                numberInput(name = "count", classes = "rep-n") { value = (r.count ?: 10).toString(); min = "1"; max = "999" }; +" times"
                            }
                        }
                        repeatPreview(r, anchor, service.now())
                    }
                }
                if (t.recurrenceType != null && r.preset == RecurrencePreset.NONE) {
                    p(classes = "hint") { +"Custom rule ${t.recurrenceRule}; kept unless you change it here." }
                }
            }
            // A timed task: its play button counts this down (app.js on the web, TaskTimer on the phone).
            timerPill(t, "task-only reminder")
            togglePill("today", t.expiresAt != null, Icon.SNOWFLAKE, Labels.TODAY_ONLY, "task-only")
        }
        // Not set here, but shown as its due date: a task waiting on this one is due sooner.
        if (!isNew && !t.isComplete) service.dueDates().of(t)?.takeIf { it.forTask != null }?.let { due ->
            p(classes = "hint due-for not-waiting") { +Labels.dueFromWaiting(dueText(due.at, service.now()), due.forTask!!.title) }
        }

        div(classes = "field-label section") { +Labels.PROPERTIES }
        div(classes = "pills") {
            val parentLabel = byId[t.parentId]?.let { p -> if (p.type == TaskType.FOLDER) folderPath(p, byId) else "Subtask of “${p.title}”" }
            // A set folder shows in its own colour, like the app's chip; app.js follows the pick.
            val colors = folderColorsHex(all)
            // A tree like the phone's folder picker: each folder in its colour, subfolders indented.
            // Under a project or checklist (or another task), its icon, not a folder's.
            val parentIcon = when (byId[t.parentId]?.type) { null, TaskType.FOLDER -> Icon.FOLDER; TaskType.CHECKLIST -> Icon.CHECKLIST; else -> Icon.PROJECT }
            popPill(Labels.FOLDER, parentIcon, parentLabel, "tree", "tinted", tint = t.parentId?.let(colors::get)) {
                div(classes = "folder-tree") {
                    fun FlowContent.treeRow(value: String, title: String, depth: Int, picked: Boolean, label: String? = null, color: String? = null) =
                        label(classes = "tree-row") {
                            attributes["style"] = "padding-left: ${8 + depth * 18}px; --tint: ${color ?: "var(--muted)"}"
                            radioInput(name = "parent") { this.value = value; checked = picked; label?.let { attributes["data-label"] = it }; color?.let { attributes["data-color"] = it } }
                            icon(Icon.FOLDER, "")
                            span { +title }
                        }
                    treeRow("", Labels.NO_FOLDER, 0, t.parentId == null)
                    byId[t.parentId]?.takeIf { it.type != TaskType.FOLDER }?.let { parent ->
                        treeRow(parent.id.toString(), "Subtask of “${parent.title}”", 0, true, label = "Subtask of “${parent.title}”")
                    }
                    folderTree(all.filter { it.type == TaskType.FOLDER }, excluding = t.id.takeIf { it != 0L }, allById = byId).forEach { (f, depth) ->
                        treeRow(f.id.toString(), f.title, depth, f.id == t.parentId, label = folderPath(f, byId), color = colors[f.id])
                    }
                }
            }
            // Folders keep contexts too: their tasks inherit them.
            service.contexts().sortedBy { it.name.lowercase() }.forEach { c ->
                label(classes = "pill") { checkBoxInput(name = "ctx") { value = c.id.toString(); checked = c.id in v.shown.contextIds }; +"@${c.name}" }
            }
            a(href = "/contexts", classes = "pill manage") { +Labels.MANAGE_CONTEXTS }
        }
        // High (!), normal or maybe (?), picked like the type. A maybe is hidden from Active and Doing.
        div(classes = "field-label section task-only not-waiting") { +Labels.PRIORITY }
        div(classes = "type-seg task-only not-waiting") {
            Labels.PRIORITIES.forEach { (high, maybe, text) ->
                label {
                    radioInput(name = "priority") { value = if (high) "high" else if (maybe) "maybe" else "normal"; checked = t.isHighPriority == high && t.isMaybe == maybe }
                    +text
                }
            }
        }

        // A new task has no id for the related-task actions yet, so its subtasks, prerequisite and
        // dependent are typed here and created on save.
        if (isNew) {
            div(classes = "field-label section") { +Labels.RELATED }
            field(Labels.SUBTASK, "") {
                textArea(classes = "new-subtasks") { name = "newSubtasks"; rows = "3"; placeholder = "One per line"; +v.newSubtasks }
            }
            field(Labels.PREREQUISITE, "task-only") {
                textInput(name = "newDep", classes = "new-dep") { value = v.newDep; placeholder = "A new task this depends on"; attributes["autocomplete"] = "off" }
            }
            field(Labels.DEPENDENT, "task-only") {
                textInput(name = "newDependent", classes = "new-dep") { value = v.newDependent; placeholder = "A new task that depends on this"; attributes["autocomplete"] = "off" }
            }
        }

        if (v.needFirstStep || v.firstStep.isNotEmpty()) {
            field("First step of this project", "") { textInput(name = "firstStep") { value = v.firstStep; placeholder = Labels.SUBTASK } }
        }
    }
    if (!isNew && !t.isTemplate) t.fromTemplateId?.let(service::get)?.takeIf { it.isTemplate }?.let { fromTemplateLine(t.id, it) }
    if (!isNew) div { relatedSection(service, t.id, v.mode, folded = v.list.collapsed) }
    // Outside the form (so Related tasks can sit above them), tied to it by the form attribute.
    div(classes = "editor-foot") {
        // Subtask options (none for a checklist, via CSS).
        div(classes = "subtask-options not-waiting") {
            div(classes = "pills") {
                // Only the first incomplete child counts as active.
                togglePill("sequential", t.sequential, Icon.LIST_NUMBERED, Labels.SEQUENTIAL, form = formId)
                // Normally a task waits on its open subtasks; this keeps it in Doing/Active anyway.
                togglePill("activeWithSubtasks", t.activeWithSubtasks, Icon.BOLT, Labels.ACTIVE_WITH_SUBTASKS, "task-type-only", form = formId)
            }
        }
        div(classes = "actions") {
            if (!isNew) button(type = ButtonType.submit, classes = "delete") {
                attributes["form"] = formId
                attributes["formaction"] = "/tasks/${t.id}/delete?mode=${v.list.q}"
                attributes["formnovalidate"] = ""
                +Labels.DELETE
            }
            // A copy kept as a template; this one carries on as it is (answered with a toast).
            if (!isNew && !t.isTemplate) button(type = ButtonType.button, classes = "save-template") {
                attributes["hx-post"] = "/tasks/${t.id}/save-template"
                attributes["hx-swap"] = "none"
                +Labels.SAVE_AS_TEMPLATE
            }
            // Checking the last item completes nothing; a checklist is completed here, on purpose (a template never is).
            if (!isNew && t.type == TaskType.CHECKLIST && !t.isComplete && !t.isTemplate) {
                val unchecked = service.tasks().count { it.parentId == t.id && !it.isComplete }
                fun completeButton(move: Boolean, label: String) = button(type = ButtonType.submit, classes = "complete-list") {
                    attributes["form"] = formId
                    attributes["formaction"] = "/tasks/${t.id}/complete-list?mode=${v.list.q}&move=${if (move) 1 else 0}"
                    attributes["formnovalidate"] = ""
                    +label
                }
                // With items unchecked, the same question as the phone's, in a popover.
                if (unchecked == 0) completeButton(false, Labels.COMPLETE_LIST)
                else details(classes = "pp complete-ask") {
                    summary(classes = "complete-list") { +Labels.COMPLETE_LIST }
                    div(classes = "pop") {
                        p(classes = "hint") { +Labels.uncheckedItems(unchecked) }
                        completeButton(true, Labels.MOVE_TO_NEW_LIST)
                        completeButton(false, Labels.COMPLETE_THEM_TOO)
                    }
                }
            }
            span(classes = "hint") { id = "save-status" }
            a(href = v.list.path, classes = "cancel") { +Labels.CANCEL }
            // A waiting item is resolved here (it saves itself as it's edited).
            if (!isNew && t.type == TaskType.WAITING && !t.isComplete) button(type = ButtonType.submit, classes = "primary resolve") {
                attributes["form"] = formId; name = "resolve"; value = "1"; +Labels.RESOLVED
            }
            else button(type = ButtonType.submit, classes = "primary") { attributes["form"] = formId; +Labels.SAVE }
        }
    }
}

// An on/off choice: a pill holding a small switch, the choice's icon and its label. Set apart from the
// picker pills (which open a popover) by its switch and outline. `form`: for one outside the editor's form.
private fun FlowContent.togglePill(name: String, on: Boolean, icon: Icon, label: String, classes: String = "", form: String? = null) =
    label(classes = "pill toggle $classes".trim()) {
        checkBoxInput(name = name) { checked = on; form?.let { attributes["form"] = it } }
        span(classes = "sw")
        icon(icon, "pill-icon")
        +label
    }

internal fun FlowContent.field(label: String, classes: String, content: FlowContent.() -> Unit) = div(classes = "field $classes") {
    span(classes = "field-label") { +label }
    content()
}

// A pill that opens a popover of controls. `value` is its text when set (else the label shows);
// `kind` tells app.js how to re-derive that text as the controls change.
private fun FlowContent.timerPill(t: Task, classes: String) = popPill(Labels.TIMER, Icon.TIMER, t.durationMinutes?.let(::formatDuration), "timer", classes) {
    div(classes = "pills") {
        Labels.TIMER_PRESETS.forEach { m -> button(type = ButtonType.button, classes = "pill preset") { attributes["data-minutes"] = m.toString(); +formatDuration(m) } }
    }
    numberInput(name = "duration", classes = "duration-input") { value = t.durationMinutes?.toString().orEmpty(); min = "1"; placeholder = "minutes" }
    span(classes = "hint inline-hint") { +"minutes" }
}

private fun FlowContent.popPill(label: String, icon: Icon, value: String?, kind: String, classes: String, tint: String? = null, content: FlowContent.() -> Unit) =
    details(classes = "pp $classes") {
        tint?.let { attributes["style"] = "--tint: $it" }
        attributes["data-kind"] = kind
        attributes["data-label"] = label
        summary(classes = if (value != null) "pill set" else "pill") {
            icon(icon, "pill-icon")
            span(classes = "pp-text") { +(value ?: label) }
            span(classes = "pp-clear") { attributes["title"] = "Clear"; +"✕" }
        }
        div(classes = "pop") { content() }
    }

// quick: (label, (date, time)) buttons that fill the fields and close the popover (app.js).
private fun FlowContent.datePill(label: String, prefix: String, icon: Icon, millis: Long?, classes: String, quick: List<Pair<String, Pair<String, String>>> = emptyList()) =
    popPill(label, icon, millis?.let(::chipDate), "date", classes) {
        val (date, time) = millis?.let(::dateAndTime) ?: ("" to "")
        dateInput(name = "${prefix}Date") { value = date }
        timeInput(name = "${prefix}Time") { value = time }
        if (quick.isNotEmpty()) div(classes = "date-quick") {
            quick.forEach { (text, value) ->
                button(type = ButtonType.button, classes = "pill") { attributes["data-date"] = value.first; attributes["data-time"] = value.second; +text }
            }
        }
    }

// "Work / Reports": a folder with the folders it's in, outermost first.
internal fun folderPath(folder: Task, byId: Map<Long, Task>): String =
    generateSequence(folder) { byId[it.parentId]?.takeIf { p -> p.type == TaskType.FOLDER } }.map { it.title }.toList().reversed().joinToString(" / ")

// Related tasks: subtasks, prerequisites (what this depends on) and dependents (what depends on
// it), each added or unlinked at once via htmx. Filled into a div by the caller so the fragment's
// root is #related, which htmx swaps.
// focusAddItem: after adding items, the new field is focused again, for typing a list in one go.
// `folded`: the checklist sections folded in this browser (the All tree's folds cookie).
// `addingTo`: the section whose + is open (its add field under its heading).
fun DIV.relatedSection(service: TaskService, id: Long, mode: ListMode, focusAddItem: Boolean = false, addKind: String = "subtask", folded: Set<Long> = emptySet(), addingTo: Long? = null) {
    this.id = "related"
    classes = setOf("related")
    val all = service.tasksAround(id)
    val byId = all.associateBy { it.id }
    val task = byId[id] ?: return
    val m = mode.name
    fun kotlinx.html.HTMLTag.htmx(url: String) = hx("post", url, "#related")
    // A subtask's checkbox goes in front of its title, as every checkbox does; other rows keep its space.
    fun FlowContent.row(kind: String, t: Task, leading: FlowContent.() -> Unit = { span(classes = "check-space") {} }, trailing: FlowContent.() -> Unit) = div(classes = "rel-row") {
        leading()
        a(href = "/tasks/${t.id}?mode=$m", classes = if (t.isComplete) "done" else null) { +t.title; kindTag(kind) }
        trailing()
    }
    fun FlowContent.unlink(url: String) = button(classes = "unlink") { htmx(url); attributes["aria-label"] = "Remove"; +"✕" }

    // Its mark goes in front of its title, as in the lists (taskMark); ticking it toggles it here.
    val counts = subtaskCounts(all)
    fun FlowContent.check(t: Task) = taskMark(t, counts[t.id], m) {
        if (t.isComplete) classes = classes + "done"
        htmx("/tasks/$id/subtasks/${t.id}/toggle?mode=$m")
        attributes["aria-label"] = if (t.isComplete) "Mark not done" else "Complete"
        if (t.isComplete) icon(Icon.CHECK, "")
    }
    // A subtask's or an item's row: dragged by its handle (app.js) or moved with Alt+Up/Down, ticked, opened
    // (to edit it), a maybe marked "?"; `note` tags one that's also a prerequisite or dependent.
    fun FlowContent.childRow(child: Task, note: String? = null, inSection: Boolean = false, trailing: FlowContent.() -> Unit) = div(classes = if (inSection) "rel-row sub in-section" else "rel-row sub") {
        attributes["data-sub"] = child.id.toString()
        attributes["data-task"] = id.toString()
        span(classes = "drag-handle") { attributes["draggable"] = "true"; attributes["title"] = "Drag to reorder"; +"⠿" }
        check(child)
        a(href = "/tasks/${child.id}?mode=$m", classes = if (child.isComplete) "done" else if (child.isMaybe) "is-maybe" else null) {
            +child.title
            if (child.isMaybe && !child.isComplete) span(classes = "maybe-mark") { +"?" }
            note?.let { kindTag(it) }
        }
        trailing()
    }

    val isChecklist = task.type == TaskType.CHECKLIST
    if (isChecklist) {
        // Items: as subtasks are, open ones first; loose ones, then each section (Clothes, Winter) with its
        // heading (fold, count, ⋯) and its own add field. One list for app.js's drag, so an item drags into
        // another section (planMoveItem). ✕ deletes an item, the ⋯ menu a section, either with Undo.
        div(classes = "field-label section") { +Labels.ITEMS }
        val groups = checklistGroups(id, all)
        val items = groups.flatMap { it.second }
        fun FlowContent.addForm(placeholder: String, into: Task?, focus: Boolean) = form(classes = if (into != null) "inline add-item in-section" else "inline add-item") {
            htmx("/tasks/$id/items?mode=$m" + (into?.let { "&in=${it.id}" } ?: ""))
            textInput(name = "text") { this.placeholder = placeholder; attributes["autocomplete"] = "off"; if (focus) autoFocus = true }
        }
        if (groups.any { it.first != null || it.second.isNotEmpty() }) div(classes = "items") { groups.forEach { (section, sectionItems) ->
            val isFolded = section != null && section.id in folded
            section?.let { sec ->
                div(classes = if (isFolded) "rel-row sub section-head folded" else "rel-row sub section-head") {
                    attributes["data-sub"] = sec.id.toString()
                    attributes["data-task"] = id.toString()
                    button(classes = "fold") { htmx("/tasks/$id/sections/${sec.id}/fold?mode=$m"); attributes["aria-label"] = if (isFolded) "Expand" else "Collapse"; +(if (isFolded) "▸" else "▾") }
                    span(classes = "section-title") { +sec.title }
                    counts[sec.id]?.let { (done, total) -> span(classes = "tail") { +" $done/$total" } }
                    button(classes = "section-add") { htmx("/tasks/$id/sections/${sec.id}/adding?mode=$m"); attributes["aria-label"] = Labels.addTo(sec.title); attributes["title"] = Labels.addTo(sec.title); +"+" }
                    // The rest opens on right-click (app.js), as a row's menu does.
                    details(classes = "pp section-menu") {
                        summary(classes = "unlink") { attributes["aria-label"] = "Section menu"; +"⋯" }
                        div(classes = "pop") {
                            form(classes = "menu-item rename") {
                                htmx("/tasks/$id/sections/${sec.id}/rename?mode=$m")
                                textInput(name = "text") { value = sec.title; attributes["aria-label"] = Labels.RENAME }
                            }
                            button(classes = "menu-item") { htmx("/tasks/$id/sections/${sec.id}/check?mode=$m"); +Labels.checkAllIn(sec.title) }
                            button(classes = "menu-item") { htmx("/tasks/$id/sections/${sec.id}/uncheck?mode=$m"); +Labels.uncheckAllIn(sec.title) }
                            button(classes = "menu-item danger") { htmx("/tasks/$id/subtasks/${sec.id}/delete?mode=$m"); +Labels.DELETE_SECTION }
                        }
                    }
                }
            }
            if (section != null && section.id == addingTo) addForm(Labels.addTo(section.title), section, focus = true)
            if (!isFolded) {
                sectionItems.forEach { item ->
                    childRow(item, inSection = section != null) {
                        button(classes = "unlink") { htmx("/tasks/$id/subtasks/${item.id}/delete?mode=$m"); attributes["aria-label"] = Labels.DELETE; +"✕" }
                    }
                }
            }
        } }
        // "+ Add item" and "+ Add section" side by side.
        div(classes = "add-row") {
            addForm("+ ${Labels.ADD_ITEM}", null, focusAddItem)
            form(classes = "inline add-item add-section") {
                htmx("/tasks/$id/sections?mode=$m")
                textInput(name = "text") { placeholder = Labels.ADD_SECTION; attributes["autocomplete"] = "off" }
            }
        }
        if (items.any { it.isComplete }) div(classes = "adders") {
            button(classes = "add-link") { htmx("/tasks/$id/items/uncheck?mode=$m"); +Labels.UNCHECK_ALL }
            button(classes = "add-link") {
                htmx("/tasks/$id/items/clear?mode=$m")
                attributes["hx-confirm"] = Labels.clearChecked(items.count { it.isComplete })
                +Labels.CLEAR_CHECKED
            }
        }
    }
    div(classes = "field-label section") { +Labels.RELATED }
    val edges = service.dependencyEdges()
    // A folder can't be completed, so it neither depends on tasks nor has any depending on it.
    val prerequisites = if (task.type == TaskType.FOLDER) emptyList() else service.dependsOn(id).mapNotNull(byId::get).sortedBy { it.title.lowercase() }
    val dependents = if (task.type == TaskType.FOLDER) emptyList() else edges.filter { it.dependsOnTaskId == id }.mapNotNull { byId[it.taskId] }
    val subtasks = all.filter { it.parentId == id && !isChecklist }.sortedWith(TaskOrder)
    val outFolder = moveOutFolderId(task, byId)?.let { byId[it]?.title }
    // Subtasks first, together on a faint tile; then prerequisites and dependents, tagged after their titles
    // (as a subtask that's also one is).
    if (subtasks.isNotEmpty()) div(classes = "sub-tile") { subtasks.forEach { sub ->
        val note = when (sub) { in prerequisites -> Labels.PREREQUISITE; in dependents -> Labels.DEPENDENT; else -> null }
        childRow(sub, note) {
            // ✕: move it out, unlink it (it stays a subtask), both, or delete it.
            details(classes = "pp unlink-menu") {
                summary(classes = "unlink") { attributes["aria-label"] = "Remove"; +"✕" }
                div(classes = "pop") {
                    button(classes = "menu-item") { htmx("/tasks/$id/subtasks/${sub.id}/moveout?mode=$m"); +Labels.moveOutTo(outFolder) }
                    if (sub in prerequisites) button(classes = "menu-item") { htmx("/tasks/$id/prerequisite/${sub.id}/remove?mode=$m"); +Labels.NOT_PREREQUISITE }
                    if (sub in dependents) button(classes = "menu-item") { htmx("/tasks/$id/dependent/${sub.id}/remove?mode=$m"); +Labels.NOT_DEPENDENT }
                    if (sub in prerequisites || sub in dependents) button(classes = "menu-item") { htmx("/tasks/$id/subtasks/${sub.id}/moveout?mode=$m&unlink=1"); +Labels.MOVE_OUT_AND_UNLINK }
                    button(classes = "menu-item danger") {
                        htmx("/tasks/$id/subtasks/${sub.id}/delete?mode=$m")
                        attributes["hx-confirm"] = "Delete “${sub.title}”?"
                        +Labels.DELETE_TASK.removeSuffix("…")
                    }
                }
            }
        }
        // One level further, indented; anything deeper is only the "1/3" count on the row it's under.
        all.filter { it.parentId == sub.id }.sortedWith(TaskOrder).forEach { child ->
            div(classes = "rel-row nested") {
                check(child)
                a(href = "/tasks/${child.id}?mode=$m", classes = if (child.isComplete) "done" else null) {
                    +child.title
                    if (child.type != TaskType.CHECKLIST) counts[child.id]?.let { (done, total) -> span(classes = "tail") { +" $done/$total" } }
                }
            }
        }
    } }
    (prerequisites - subtasks.toSet()).forEach { p -> row(Labels.PREREQUISITE, p, leading = { check(p) }) { unlink("/tasks/$id/prerequisite/${p.id}/remove?mode=$m") } }
    (dependents - subtasks.toSet()).forEach { d -> row(Labels.DEPENDENT, d, leading = { check(d) }) { unlink("/tasks/$id/dependent/${d.id}/remove?mode=$m") } }

    // One field adds any kind: type a new task (Enter), or pick a suggestion (↓, or a click). The
    // kind switch says what to add; a checklist only links, a folder only takes subtasks.
    val kinds = buildList {
        if (!isChecklist) add("subtask" to Labels.SUBTASK)
        if (task.type != TaskType.FOLDER) { add("prerequisite" to Labels.PREREQUISITE); add("dependent" to Labels.DEPENDENT) }
    }
    if (kinds.isNotEmpty()) form(classes = "add-related") {
        htmx("/tasks/$id/related?mode=$m")
        attributes["autocomplete"] = "off"
        div(classes = "add-field") {
            textInput(name = "text") {
                placeholder = "+"
                attributes["aria-label"] = "Add a subtask, or link a task"
                attributes["hx-get"] = "/tasks/$id/related/suggest"
                // Not on "change": that fires when ↓ leaves the field, and the refresh would drop the suggestion just focused.
                attributes["hx-trigger"] = "input changed delay:200ms, kindchange, focus"
                attributes["hx-target"] = "next .suggest"
                attributes["hx-swap"] = "outerHTML"
                attributes["hx-include"] = "closest form"
                if (focusAddItem) autoFocus = true
            }
            div(classes = "kind-seg") {
                val selected = kinds.firstOrNull { it.first == addKind }?.first ?: kinds.first().first
                kinds.forEach { (value, label) ->
                    label { radioInput(name = "kind") { this.value = value; checked = value == selected }; +label }
                }
            }
        }
        div(classes = "suggest") {}
    }
}