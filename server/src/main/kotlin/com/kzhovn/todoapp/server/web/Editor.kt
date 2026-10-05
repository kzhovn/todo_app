package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.server.quickAddChips
import com.kzhovn.todoapp.quickadd.QuickAdd
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.reminderSummary
import com.kzhovn.todoapp.data.isLinkable
import com.kzhovn.todoapp.repository.changedInheritedFields
import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.recurrence.ordinal
import com.kzhovn.todoapp.recurrence.WEEKDAY_NAMES
import com.kzhovn.todoapp.recurrence.NTH_NAMES
import com.kzhovn.todoapp.recurrence.recurrencePresets
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import com.kzhovn.todoapp.data.Task
import kotlinx.html.summary
import kotlinx.html.details
import com.kzhovn.todoapp.data.formatDuration
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.hasTime
import com.kzhovn.todoapp.data.nextRollover
import com.kzhovn.todoapp.data.wouldCreateCycle
import com.kzhovn.todoapp.data.wouldCreateDependencyCycle
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
        val type = call.request.queryParameters["type"]?.let { runCatching { TaskType.valueOf(it) }.getOrNull() } ?: TaskType.TASK
        val parent = call.folder()?.takeIf { service.get(it)?.type == TaskType.FOLDER } ?: if (type == TaskType.TASK) service.defaultFolderId() else null
        val draft = call.request.queryParameters["text"]?.let { text ->
            service.planQuickAdd(text, call.request.queryParameters.quickAddChips()).let { add -> add.takeIf { it.task != null } ?: QuickAdd(Task(title = text)) }
        }
        val task = draft?.task?.let { it.copy(parentId = it.parentId ?: parent) } ?: Task(title = "", type = type, parentId = parent)
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
            (changed.isNotEmpty() && service.descendantsOverriding(id, changed).isNotEmpty())
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

    // A new subtask (text), or an existing task moved under this one (child).
    post("/tasks/{id}/subtasks") {
        val id = call.taskId() ?: return@post
        val params = call.receiveParameters()
        params["child"]?.toLongOrNull()?.let { service.reparent(it, id) }
        if (service.get(id) != null) service.addTyped(params["text"].orEmpty(), under = id)
        call.respondSubtasks(service, id)
    }
    // An existing task (prerequisite), or a new one (text), that this one depends on. A new one goes in
    // this task's folder, like a new dependent.
    post("/tasks/{id}/prerequisite") {
        val id = call.taskId() ?: return@post
        val params = call.receiveParameters()
        params["prerequisite"]?.toLongOrNull()?.let { service.addDependency(id, it) }
        service.get(id)?.let { task -> service.addTyped(params["text"].orEmpty(), folder = service.folderIdOf(task))?.let { service.addDependency(id, it.id) } }
        call.respondSubtasks(service, id)
    }
    post("/tasks/{id}/prerequisite/{other}/remove") {
        val id = call.taskId() ?: return@post
        call.parameters["other"]?.toLongOrNull()?.let { service.removeDependency(id, it) }
        call.respondSubtasks(service, id)
    }
    post("/tasks/{id}/dependent/{other}/remove") {
        val id = call.taskId() ?: return@post
        call.parameters["other"]?.toLongOrNull()?.let { service.removeDependency(it, id) }
        call.respondSubtasks(service, id)
    }
    // An existing task, or (text) a new one, that depends on this one. A new one goes in this task's
    // folder, like the phone's, since dependent work usually belongs together.
    post("/tasks/{id}/dependent") {
        val id = call.taskId() ?: return@post
        val params = call.receiveParameters()
        params["dependent"]?.toLongOrNull()?.let { service.addDependency(it, id) }
        service.get(id)?.let { task -> service.addTyped(params["text"].orEmpty(), folder = service.folderIdOf(task))?.let { service.addDependency(it.id, id) } }
        call.respondSubtasks(service, id)
    }
    // A checklist's items: add ("milk, eggs" is two), uncheck all, clear checked.
    post("/tasks/{id}/items") {
        val id = call.taskId() ?: return@post
        val text = call.receiveParameters()["text"].orEmpty()
        if (service.get(id)?.type == TaskType.CHECKLIST) service.addItems(id, text)
        call.respondSubtasks(service, id, focusAddItem = true)
    }
    post("/tasks/{id}/items/uncheck") {
        val id = call.taskId() ?: return@post
        service.uncheckAll(id)
        call.respondSubtasks(service, id)
    }
    post("/tasks/{id}/items/clear") {
        val id = call.taskId() ?: return@post
        service.clearChecked(id)
        call.respondSubtasks(service, id)
    }
    // Completing a checklist (on purpose, from its editor); unchecked items move to a new copy or are completed too.
    post("/tasks/{id}/complete-list") {
        val id = call.taskId() ?: return@post
        service.completeChecklist(id, moveUncheckedToNewList = call.request.queryParameters["move"] == "1")
        call.respondRedirect(call.listPath())
    }
    post("/tasks/{id}/subtasks/{sub}/toggle") {
        val id = call.taskId() ?: return@post
        call.parameters["sub"]?.toLongOrNull()?.let(service::get)?.takeIf { it.parentId == id }?.let {
            if (it.isComplete) service.uncomplete(it.id) else service.complete(it.id)
        }
        call.respondSubtasks(service, id)
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
    val changed = if (id == null) emptySet() else changedInheritedFields(base.task, form.task, base.contextIds, form.contextIds)
        .filterTo(mutableSetOf()) { service.descendantsOverriding(id, setOf(it)).isNotEmpty() }
    val overriding = if (id == null || changed.isEmpty()) emptyList() else service.descendantsOverriding(id, changed)
    val inherit = params["inherit"]
    if (changed.isNotEmpty() && inherit == null) return reshow(view.copy(ask = overriding.size to changed))

    // A new task's prerequisite and dependent, typed in the form: made in its folder, as related work
    // usually belongs together.
    val folderId = service.folderIdOf(merged.task)
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
    if (id == null && params["pin"] != null) service.pin(savedId)
    newSubtasks.forEach { service.addTyped(it, under = savedId) }
    createTyped(newDependent)?.let { service.addDependency(it, savedId) }
    call.respondRedirect(call.listPath())
}

private suspend fun io.ktor.server.application.ApplicationCall.respondSubtasks(service: TaskService, id: Long, focusAddItem: Boolean = false) =
    respondText(createHTML().div { relatedSection(service, id, mode(), focusAddItem) }, ContentType.Text.Html)

private fun Parameters.ids(name: String) = getAll(name).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

private fun parseForm(p: Parameters, base: EditState, recurrence: RecurrenceSelection, service: TaskService): EditState {
    // An imported rule the picker can't show is kept as long as the picker isn't touched.
    val (recurrenceType, recurrenceRule) =
        if (recurrence == recurrenceSelectionFromTask(base.task.recurrenceType, base.task.recurrenceRule)) base.task.recurrenceType to base.task.recurrenceRule
        else recurrence.toTaskFields()
    val task = base.task.copy(
        title = p["title"].orEmpty().trim(),
        type = p["type"]?.let { runCatching { TaskType.valueOf(it) }.getOrNull() } ?: base.task.type,
        isStarred = p["starred"] != null,
        startDate = dateTime(p["startDate"], p["startTime"]),
        dueDate = dateTime(p["dueDate"], p["dueTime"]),
        reminderOffsetMinutes = p["reminder"]?.toIntOrNull(),
        remindAtStart = p["remindStart"] != null,
        remindAt = dateTime(p["remindAtDate"], p["remindAtTime"]),
        parentId = p["parent"]?.toLongOrNull(),
        recurrenceType = recurrenceType,
        recurrenceRule = recurrenceRule,
        isMaybe = p["priority"] == "maybe",
        isHighPriority = p["priority"] == "high",
        durationMinutes = p["duration"]?.toIntOrNull()?.takeIf { it > 0 },
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

// Three-way merge: a field the user changed in the form wins; any other keeps its current value.
private fun merge(base: EditState, form: EditState, current: EditState): EditState {
    fun <T> pick(field: (Task) -> T): T = if (field(form.task) != field(base.task)) field(form.task) else field(current.task)
    val (recurrenceType, recurrenceRule) = pick { it.recurrenceType to it.recurrenceRule }
    val task = current.task.copy(
        title = pick { it.title }, type = pick { it.type }, isStarred = pick { it.isStarred },
        startDate = pick { it.startDate }, dueDate = pick { it.dueDate }, reminderOffsetMinutes = pick { it.reminderOffsetMinutes }, remindAtStart = pick { it.remindAtStart }, remindAt = pick { it.remindAt },
        parentId = pick { it.parentId }, recurrenceType = recurrenceType, recurrenceRule = recurrenceRule,
        isMaybe = pick { it.isMaybe }, isHighPriority = pick { it.isHighPriority }, expiresAt = pick { it.expiresAt }, sequential = pick { it.sequential }, activeWithSubtasks = pick { it.activeWithSubtasks },
        durationMinutes = pick { it.durationMinutes }, notes = pick { it.notes }
    )
    return EditState(
        task,
        if (form.contextIds != base.contextIds) form.contextIds else current.contextIds,
        if (form.dependsOn != base.dependsOn) form.dependsOn else current.dependsOn
    )
}

private fun localDateTime(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

// The folder a task sits directly in, where its new related tasks go (related work usually belongs together).
private fun TaskService.folderIdOf(task: Task): Long? = task.parentId?.takeIf { get(it)?.type == TaskType.FOLDER }

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
    val all = service.tasks()
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
        v.ask?.let { (count, fields) ->
            div(classes = "ask") {
                val what = fields.joinToString(" and ") { it.label }
                p { +"$count subtask${if (count == 1) " has its" else "s have their"} own $what. Clear ${if (count == 1) "it" else "them"} so ${if (count == 1) "it follows" else "they follow"} this task?" }
                button(type = ButtonType.submit, classes = "primary") { name = "inherit"; value = "update"; +"Update subtasks" }
                button(type = ButtonType.submit) { name = "inherit"; value = "only"; +"Only this task" }
            }
        }

        // The title wraps (up to four lines), with the star inside the box by the first line. A starred
        // task is never a maybe (Maybe is under Properties); app.js unticks the other when one is ticked.
        // The note sits under it, in the same card.
        div(classes = "title-card") {
        div(classes = "title-box") {
            // An existing task pins at once, like the phone's; a new one when it's saved.
            if (isNew) label(classes = "pin-toggle") { attributes["title"] = Labels.PIN; checkBoxInput(name = "pin") { checked = v.pin }; icon(Icon.PUSH_PIN, "") }
            else if (t.type == TaskType.TASK && !t.isComplete) {
                pinControl(t, service.pinned()?.id == t.id)
            }
            textArea(classes = "title-input") { name = "title"; rows = "1"; placeholder = Labels.TITLE; required = true; +t.title }
            label(classes = "flag-toggle star-toggle task-only") {
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

        // Everything about when, as pills that open a small popover (app.js keeps their text current).
        div(classes = "field-label section") { +Labels.TIMING }
        div(classes = "pills when") {
            // Quick choices under the fields: the snooze menu's for Start, days for Due (the phone's long press).
            val now = service.now()
            val day = { n: Long -> LocalDate.now().plusDays(n).toString() to "" }
            fun at(ms: Long) = localDateTime(ms).let { it.toLocalDate().toString() to it.toLocalTime().withSecond(0).withNano(0).toString() }
            datePill(Labels.START, "start", Icon.CALENDAR, t.startDate, "", listOf(
                Labels.SNOOZE_HOUR to at(now + 60 * 60 * 1000), Labels.SNOOZE_TOMORROW to at(nextRollover(now, service.rolloverHour())),
                Labels.SNOOZE_WEEK to at(now + 7L * 24 * 60 * 60 * 1000)
            ))
            datePill(Labels.DUE, "due", Icon.FLAG, t.dueDate, "task-only", listOf("Today" to day(0), Labels.SNOOZE_TOMORROW to day(1), "Next week" to day(7)))
            // Set here, rung by the phone: it schedules the alarm when this syncs to it.
            // Set here, rung by the phone: when it starts, before it's due, and/or at a time of its own. A
            // date with no time rings at the reminder hour in Settings.
            popPill(Labels.REMIND, Icon.BELL, reminderSummary(t, service.reminderHour()), "remind", "task-only reminder") {
                label { checkBoxInput(name = "remindStart") { checked = t.remindAtStart }; +" When it starts" }
                div(classes = "pop-label") { +"Before it's due" }
                select {
                    name = "reminder"
                    Labels.REMINDERS.forEach { (m, label) -> option { value = m?.toString().orEmpty(); selected = t.reminderOffsetMinutes == m; +label } }
                }
                div(classes = "pop-label") { +"At a time" }
                val at = t.remindAt?.let(::localDateTime)
                dateInput(name = "remindAtDate") { value = at?.toLocalDate()?.toString().orEmpty() }
                timeInput(name = "remindAtTime") { value = if (t.remindAt != null && hasTime(t.remindAt!!)) at!!.toLocalTime().withSecond(0).withNano(0).toString() else "" }
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
                        (listOf("Don't repeat" to RecurrenceSelection(RecurrencePreset.NONE)) + presets).forEach { (label, preset) ->
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
                            label { radioInput(name = "repeat") { value = RecurrencePreset.CALENDAR.name; checked = r.preset == RecurrencePreset.CALENDAR }; +"On a schedule" }
                            label { radioInput(name = "repeat") { value = RecurrencePreset.AFTER_COMPLETION_N_DAYS.name; checked = r.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS }; +Labels.AFTER_COMPLETION }
                        }
                        div(classes = "rep-line") {
                            span(classes = "rep-sched") { +"Every" }
                            numberInput(name = "n", classes = "rep-n") { value = r.n.toString(); min = "1"; max = "999" }
                            listOf(RecurrenceUnit.DAY to "days", RecurrenceUnit.WEEK to "weeks", RecurrenceUnit.MONTH to "months").forEach { (unit, name) ->
                                label(classes = "pill") { radioInput(name = "unit") { value = unit.name; checked = r.unit == unit }; +name }
                            }
                            span(classes = "rep-after") { +"after completion" }
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
            popPill(Labels.TIMER, Icon.TIMER, t.durationMinutes?.let(::formatDuration), "timer", "task-only reminder") {
                div(classes = "pills") {
                    Labels.TIMER_PRESETS.forEach { m -> button(type = ButtonType.button, classes = "pill preset") { attributes["data-minutes"] = m.toString(); +formatDuration(m) } }
                }
                numberInput(name = "duration", classes = "duration-input") { value = t.durationMinutes?.toString().orEmpty(); min = "1"; placeholder = "minutes" }
                span(classes = "hint inline-hint") { +"minutes" }
            }
            togglePill("today", t.expiresAt != null, Icon.SNOWFLAKE, Labels.TODAY_ONLY, "task-only")
        }

        div(classes = "field-label section") { +Labels.PROPERTIES }
        div(classes = "pills") {
            val parentLabel = byId[t.parentId]?.let { p -> if (p.type == TaskType.FOLDER) folderPath(p, byId) else "Subtask of “${p.title}”" }
            // A set folder shows in its own colour, like the app's chip; app.js follows the pick.
            val colors = folderColorsHex(all)
            popPill(Labels.FOLDER, Icon.FOLDER, parentLabel, "select", "tinted", tint = t.parentId?.let(colors::get)) {
                select {
                    name = "parent"
                    option { value = ""; selected = t.parentId == null; +Labels.NO_FOLDER }
                    byId[t.parentId]?.takeIf { it.type != TaskType.FOLDER }?.let { parent ->
                        option { value = parent.id.toString(); selected = true; +"Subtask of “${parent.title}”" }
                    }
                    all.filter { it.type == TaskType.FOLDER && !wouldCreateCycle(it.id, t.id, byId) }
                        .map { it to folderPath(it, byId) }.sortedBy { it.second.lowercase() }
                        .forEach { (f, path) -> option { value = f.id.toString(); selected = f.id == t.parentId; colors[f.id]?.let { attributes["data-color"] = it }; +path } }
                }
            }
            // Folders keep contexts too: their tasks inherit them.
            service.contexts().sortedBy { it.name.lowercase() }.forEach { c ->
                label(classes = "pill") { checkBoxInput(name = "ctx") { value = c.id.toString(); checked = c.id in v.shown.contextIds }; +"@${c.name}" }
            }
            a(href = "/contexts", classes = "pill manage") { +Labels.MANAGE_CONTEXTS }
        }
        // High (!), normal or maybe (?), picked like the type. A maybe is hidden from Active and Doing.
        div(classes = "field-label section task-only") { +Labels.PRIORITY }
        div(classes = "type-seg task-only") {
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
    if (!isNew) div { relatedSection(service, t.id, v.mode) }
    // Outside the form (so Related tasks can sit above them), tied to it by the form attribute.
    div(classes = "editor-foot") {
        // Subtask options (none for a checklist, via CSS).
        div(classes = "subtask-options") {
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
            // Checking the last item completes nothing; a checklist is completed here, on purpose.
            if (!isNew && t.type == TaskType.CHECKLIST && !t.isComplete) {
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
            a(href = v.list.path, classes = "cancel") { +"Cancel" }
            button(type = ButtonType.submit, classes = "primary") { attributes["form"] = formId; +Labels.SAVE }
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
    popPill(label, icon, millis?.let(::pillDate), "date", classes) {
        val at = millis?.let(::localDateTime)
        dateInput(name = "${prefix}Date") { value = at?.toLocalDate()?.toString().orEmpty() }
        timeInput(name = "${prefix}Time") { value = if (millis != null && hasTime(millis)) at!!.toLocalTime().withSecond(0).withNano(0).toString() else "" }
        if (quick.isNotEmpty()) div(classes = "date-quick") {
            quick.forEach { (text, value) ->
                button(type = ButtonType.button, classes = "pill") { attributes["data-date"] = value.first; attributes["data-time"] = value.second; +text }
            }
        }
    }

// The phone's chip date: "Sep 25", or "Sep 25 3:00 PM" with a time. app.js formats the same way.
private fun pillDate(millis: Long): String =
    java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(millis)) +
        if (hasTime(millis)) " " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(millis)) else ""

internal fun folderPath(folder: Task, byId: Map<Long, Task>): String =
    generateSequence(folder) { byId[it.parentId]?.takeIf { p -> p.type == TaskType.FOLDER } }.map { it.title }.toList().reversed().joinToString(" / ")

// Related tasks: subtasks, prerequisites (what this depends on) and dependents (what depends on
// it), each added or unlinked at once via htmx. Filled into a div by the caller so the fragment's
// root is #related, which htmx swaps.
// focusAddItem: after adding items, the new field is focused again, for typing a list in one go.
fun DIV.relatedSection(service: TaskService, id: Long, mode: ListMode, focusAddItem: Boolean = false) {
    this.id = "related"
    classes = setOf("related")
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    val task = byId[id] ?: return
    val m = mode.name
    fun kotlinx.html.HTMLTag.htmx(url: String) = hx("post", url, "#related")
    // A subtask's checkbox goes in front of its title, as every checkbox does; other rows keep its space.
    fun FlowContent.row(kind: String, t: Task, leading: FlowContent.() -> Unit = { span(classes = "check-space") {} }, trailing: FlowContent.() -> Unit) = div(classes = "rel-row") {
        span(classes = "rel-kind") { +kind }
        leading()
        a(href = "/tasks/${t.id}?mode=$m", classes = if (t.isComplete) "done" else null) { +t.title }
        trailing()
    }
    fun FlowContent.unlink(url: String) = button(classes = "unlink") { htmx(url); attributes["aria-label"] = "Remove"; +"✕" }

    val isChecklist = task.type == TaskType.CHECKLIST
    if (isChecklist) {
        // Items: a checkbox and a title each, open ones first, and a field that stays for the next one.
        div(classes = "field-label section") { +Labels.ITEMS }
        val items = checklistItems(id, all)
        items.forEach { item ->
            div(classes = "rel-row item") {
                button(classes = if (item.isComplete) "check done" else "check") {
                    htmx("/tasks/$id/subtasks/${item.id}/toggle?mode=$m")
                    attributes["aria-label"] = if (item.isComplete) "Uncheck" else "Check"
                    if (item.isComplete) icon(Icon.CHECK, "")
                }
                a(href = "/tasks/${item.id}?mode=$m", classes = if (item.isComplete) "done" else null) { +item.title }
            }
        }
        form(classes = "inline add-item") {
            htmx("/tasks/$id/items?mode=$m")
            textInput(name = "text") { placeholder = "+ ${Labels.ADD_ITEM}"; attributes["autocomplete"] = "off"; if (focusAddItem) autoFocus = true }
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
    subtasks.forEach { sub ->
        val kind = when (sub) { in prerequisites -> Labels.PREREQUISITE_SUBTASK; in dependents -> Labels.DEPENDENT_SUBTASK; else -> Labels.SUBTASK }
        row(kind, sub, leading = {
            if (sub.type == TaskType.TASK) button(classes = if (sub.isComplete) "check done" else "check") {
                htmx("/tasks/$id/subtasks/${sub.id}/toggle?mode=$m")
                attributes["aria-label"] = if (sub.isComplete) "Mark not done" else "Complete"
                if (sub.isComplete) icon(Icon.CHECK, "")
            } else span(classes = "check-space") {}
        }) {
            // ✕ unlinks the dependency; the subtask stays.
            if (sub in prerequisites) unlink("/tasks/$id/prerequisite/${sub.id}/remove?mode=$m")
            else if (sub in dependents) unlink("/tasks/$id/dependent/${sub.id}/remove?mode=$m")
        }
    }
    (prerequisites - subtasks.toSet()).forEach { p -> row(Labels.PREREQUISITE, p) { unlink("/tasks/$id/prerequisite/${p.id}/remove?mode=$m") } }
    (dependents - subtasks.toSet()).forEach { d -> row(Labels.DEPENDENT, d) { unlink("/tasks/$id/dependent/${d.id}/remove?mode=$m") } }

    // Each "+" opens a popover: type a new task, or pick an existing one.
    fun FlowContent.adder(label: String, url: String, existingName: String, placeholder: String, candidates: List<Task>) = details(classes = "pp adder") {
        summary(classes = "add-link") { +label }
        div(classes = "pop") {
            form(classes = "inline") {
                htmx(url)
                textInput(name = "text") { this.placeholder = placeholder; attributes["autocomplete"] = "off" }
            }
            if (candidates.isNotEmpty()) form(classes = "inline") {
                htmx(url)
                select { name = existingName; candidates.forEach { option { value = it.id.toString(); +it.title } } }
                button(type = ButtonType.submit) { +"Add" }
            }
        }
    }
    div(classes = "adders") {
        if (!isChecklist) adder(Labels.ADD_SUBTASK, "/tasks/$id/subtasks?mode=$m", "child", "New subtask",
            all.filter { it.id != id && it.type != TaskType.FOLDER && !it.isComplete && it.parentId != id && !wouldCreateCycle(id, it.id, byId) }.sortedBy { it.title.lowercase() })
        if (task.type != TaskType.FOLDER) {
            val otherEdges = edges.filter { it.taskId != id }
            adder(Labels.ADD_PREREQUISITE, "/tasks/$id/prerequisite?mode=$m", "prerequisite", "New task this depends on",
                all.filter { it.id != id && it.type.isLinkable && !it.isComplete && it !in prerequisites && !wouldCreateDependencyCycle(it.id, id, otherEdges) }.sortedBy { it.title.lowercase() })
            adder(Labels.ADD_DEPENDENT, "/tasks/$id/dependent?mode=$m", "dependent", "New task that depends on this",
                all.filter { it.id != id && it.type.isLinkable && !it.isComplete && it !in dependents && !wouldCreateDependencyCycle(id, it.id, edges) }.sortedBy { it.title.lowercase() })
        }
    }
}
