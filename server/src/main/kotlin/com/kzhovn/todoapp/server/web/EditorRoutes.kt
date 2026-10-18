package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.isTimedWait
import com.kzhovn.todoapp.data.folderOf
import com.kzhovn.todoapp.recurrence.recurrenceToSave
import com.kzhovn.todoapp.server.quickAddChips
import com.kzhovn.todoapp.quickadd.QuickAdd
import com.kzhovn.todoapp.repository.changedInheritedFields
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.mergeEdit
import kotlinx.html.details
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.eventWhen
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.hasTime
import com.kzhovn.todoapp.data.atTimeMillis
import com.kzhovn.todoapp.data.localDay
import com.kzhovn.todoapp.data.nextRollover
import com.kzhovn.todoapp.data.ancestors
import com.kzhovn.todoapp.data.LinkKind
import com.kzhovn.todoapp.data.linkCandidates
import com.kzhovn.todoapp.data.nearbyFirst
import com.kzhovn.todoapp.data.itemsIn
import com.kzhovn.todoapp.data.MAX_CHECK_IN_DAYS
import com.kzhovn.todoapp.data.nextCheckIn
import com.kzhovn.todoapp.data.moveOutFolderId
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.RecurrenceUnit
import com.kzhovn.todoapp.recurrence.recurrenceSelectionFromTask
import com.kzhovn.todoapp.server.TaskService
import com.kzhovn.todoapp.sync.contextIds
import com.kzhovn.todoapp.sync.dependsOn
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
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.serialization.json.add
import java.time.LocalDate
import java.time.LocalTime

// The editor's routes, and saving its form; the page itself is in Editor.kt.

fun Route.editorRoutes(service: TaskService) {
    get("/repeat-preview") {
        val p = call.request.queryParameters
        val anchor = dateTime(p["startDate"], p["startTime"]) ?: dateTime(p["dueDate"], p["dueTime"]) ?: service.now()
        call.respondText(createHTML().div { repeatPreview(parseRecurrence(p), anchor, service.now()) }.removePrefix("<div>").removeSuffix("</div>"), ContentType.Text.Html)
    }

    get("/tasks/{id}") {
        val state = call.taskId()?.let(service::editState) ?: return@get call.respond(HttpStatusCode.NotFound)
        val recurrence = recurrenceSelectionFromTask(state.task.recurrenceType, state.task.recurrenceRule)
        val focusAdd = call.request.queryParameters["add"] != null
        call.respondHtml { editorPage(service, EditorView(call.mode(), call.listData(service, call.mode(), selected = state.task.id), state, recurrence, state.encode(), focusAdd = focusAdd)) }
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
        // An event picked: as a prerequisite this waits till it's over; as a dependent this is due when it starts.
        val event = p["event"]
        if (task != null && event != null && kind != "subtask") service.linkEvent(id, event, before = kind == "dependent")
        else if (task != null) when (kind) {
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
        // Linking, upcoming events too (soonest first), under the tasks.
        val events = if (kind == "subtask") emptyList() else service.shownEvents().filter { it.end > service.now() && it.title.contains(q, ignoreCase = true) }.sortedBy { it.start }.take(5)
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
            if (events.isNotEmpty()) div(classes = "sugg-head") { +Labels.EVENTS }
            events.forEach { e ->
                button(type = ButtonType.submit, classes = "sugg") {
                    name = "event"; value = e.id
                    span { calDot(calendarColor(service, e.calendarId)); +" ${e.title}" }
                    span(classes = "where") { +eventWhen(e, service.now()) }
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

private fun Parameters.ids(name: String) = getAll(name).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

private fun parseForm(p: Parameters, base: EditState, recurrence: RecurrenceSelection, service: TaskService): EditState {
    val (recurrenceType, recurrenceRule) = recurrenceToSave(recurrence, base.task)
    val type = p["type"]?.let { runCatching { TaskType.valueOf(it) }.getOrNull() } ?: base.task.type
    // A waiting item's date is "Resolves on" (Task.typeRule drops its star and priority on save).
    val waiting = type == TaskType.WAITING
    val baseCheckIn = localDay(nextCheckIn(base.task, service.now())).toString()
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
        alongsideNext = if (p["alongsideShown"] != null) p["alongsideNext"] != null else base.task.alongsideNext,
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

// A date without a time is local midnight (see hasTime).
internal fun dateTime(date: String?, time: String?): Long? {
    val t = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    // A time alone means today, as in quick add ("start 5pm").
    val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now().takeIf { t != null } ?: return null
    return d.atTimeMillis(t ?: LocalTime.MIDNIGHT)
}

// Three-way merge: what the user changed in the form wins; anything else keeps its current value (see mergeEdit).
private fun merge(base: EditState, form: EditState, current: EditState): EditState =
    EditState(
        mergeEdit(base.task, form.task, current.task),
        if (form.contextIds != base.contextIds) form.contextIds else current.contextIds,
        if (form.dependsOn != base.dependsOn) form.dependsOn else current.dependsOn
    )
