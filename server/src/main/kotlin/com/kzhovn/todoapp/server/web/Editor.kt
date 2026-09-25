package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.hasTime
import com.kzhovn.todoapp.data.nextRollover
import com.kzhovn.todoapp.data.wouldCreateCycle
import com.kzhovn.todoapp.data.wouldCreateDependencyCycle
import com.kzhovn.todoapp.quickadd.QuickAddParser
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.classes
import kotlinx.html.dateInput
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
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
    val shown: EditState,
    val recurrence: RecurrenceSelection,
    val base: String,
    val error: String? = null,
    // Subtasks with their own value for changed inherited fields: "update them too?"
    val ask: Pair<Int, Set<InheritedField>>? = null,
    val needFirstStep: Boolean = false,
    val firstStep: String = ""
)

fun Route.editorRoutes(service: TaskService) {
    get("/tasks/{id}") {
        val state = call.taskId()?.let(service::editState) ?: return@get call.respond(HttpStatusCode.NotFound)
        val recurrence = recurrenceSelectionFromTask(state.task.recurrenceType, state.task.recurrenceRule)
        call.respondHtml { editorPage(service, EditorView(call.mode(), state, recurrence, state.encode())) }
    }

    post("/tasks/{id}") {
        val mode = call.mode()
        val id = call.taskId() ?: return@post call.respond(HttpStatusCode.NotFound)
        // Deleted elsewhere while the editor was open.
        val current = service.editState(id) ?: return@post call.respondRedirect(mode.path)
        val params = call.receiveParameters()
        val base = decodeState(id, params["base"]) ?: current
        val recurrence = parseRecurrence(params)
        val form = parseForm(params, base, recurrence, service)
        val firstStep = params["firstStep"]?.trim().orEmpty()
        val view = EditorView(mode, form, recurrence, base.encode(), firstStep = firstStep)
        suspend fun reshow(v: EditorView) = call.respondHtml { editorPage(service, v) }

        if (form.task.title.isBlank()) return@post reshow(view.copy(error = "A title is required."))
        val merged = merge(base, form, current)
        val needsFirstStep = merged.task.type == TaskType.PROJECT && service.tasks().none { it.parentId == id }
        if (needsFirstStep && firstStep.isBlank()) {
            return@post reshow(view.copy(needFirstStep = true, error = "A project needs a first step."))
        }
        val changed = buildSet {
            if (form.task.startDate != base.task.startDate) add(InheritedField.START)
            if (form.task.dueDate != base.task.dueDate) add(InheritedField.DUE)
            if (form.contextIds != base.contextIds) add(InheritedField.CONTEXTS)
        }.filterTo(mutableSetOf()) { service.descendantsOverriding(id, setOf(it)).isNotEmpty() }
        val overriding = if (changed.isEmpty()) emptyList() else service.descendantsOverriding(id, changed)
        val inherit = params["inherit"]
        if (changed.isNotEmpty() && inherit == null) return@post reshow(view.copy(ask = overriding.size to changed))

        service.edit(merged.task, merged.contextIds, merged.dependsOn)
        if (inherit == "update") service.clearInherited(overriding, changed)
        if (needsFirstStep) service.create(Task(title = firstStep, parentId = id))
        call.respondRedirect(mode.path)
    }

    // No confirmation: the list it lands on offers Undo instead.
    post("/tasks/{id}/delete") {
        call.taskId()?.let(service::delete)
        call.respondRedirect("${call.mode().path}?deleted=${call.taskId()}")
    }
    post("/tasks/{id}/restore") {
        call.taskId()?.let(service::restore)
        call.respondList(service, call.mode(), extra = createHTML().div { id = "toast"; attributes["hx-swap-oob"] = "true" })
    }

    post("/tasks/{id}/subtasks") {
        val id = call.taskId() ?: return@post
        val parsed = QuickAddParser.parse(call.receiveParameters()["text"].orEmpty())
        if (parsed.title.isNotBlank() && service.get(id) != null) service.create(parsed.copy(parentId = id))
        call.respondSubtasks(service, id)
    }
    post("/tasks/{id}/adopt") {
        val id = call.taskId() ?: return@post
        call.receiveParameters()["child"]?.toLongOrNull()?.let { service.reparent(it, id) }
        call.respondSubtasks(service, id)
    }
    post("/tasks/{id}/dependent") {
        val id = call.taskId() ?: return@post
        call.receiveParameters()["dependent"]?.toLongOrNull()?.let { service.addDependency(it, id) }
        call.respondSubtasks(service, id)
    }
    post("/tasks/{id}/subtasks/{sub}/toggle") {
        val id = call.taskId() ?: return@post
        call.parameters["sub"]?.toLongOrNull()?.let(service::get)?.takeIf { it.parentId == id }?.let {
            if (it.isComplete) service.uncomplete(it.id) else service.complete(it.id)
        }
        call.respondSubtasks(service, id)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondSubtasks(service: TaskService, id: Long) =
    respondText(createHTML().div { subtasksSection(service, id, mode()) }, ContentType.Text.Html)

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
        parentId = p["parent"]?.toLongOrNull(),
        recurrenceType = recurrenceType,
        recurrenceRule = recurrenceRule,
        isMaybe = p["maybe"] != null,
        expiresAt = if (p["today"] != null) base.task.expiresAt ?: nextRollover(service.now(), service.rolloverHour()) else null,
        sequential = p["sequential"] != null
    )
    return EditState(task, p.ids("ctx"), p.ids("dep"))
}

// Normalised so that an untouched picker compares equal to the task's own rule.
private fun parseRecurrence(p: Parameters): RecurrenceSelection {
    val n = p["n"]?.toIntOrNull()?.coerceIn(1, 999) ?: 1
    return when (p["repeat"]?.let { runCatching { RecurrencePreset.valueOf(it) }.getOrNull() }) {
        RecurrencePreset.CALENDAR -> {
            val unit = p["unit"]?.let { runCatching { RecurrenceUnit.valueOf(it) }.getOrNull() } ?: RecurrenceUnit.DAY
            val mask = if (unit != RecurrenceUnit.WEEK) 0 else p.getAll("wd").orEmpty().mapNotNull { it.toIntOrNull()?.takeIf { d -> d in 0..6 } }.fold(0) { m, d -> m or (1 shl d) }
            RecurrenceSelection(RecurrencePreset.CALENDAR, n, unit, mask)
        }
        RecurrencePreset.AFTER_COMPLETION_N_DAYS -> RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n)
        else -> RecurrenceSelection(RecurrencePreset.NONE)
    }
}

// A date without a time is local midnight (see hasTime).
internal fun dateTime(date: String?, time: String?): Long? {
    val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val t = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.MIDNIGHT
    return d.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

// Three-way merge: a field the user changed in the form wins; any other keeps its current value.
private fun merge(base: EditState, form: EditState, current: EditState): EditState {
    fun <T> pick(field: (Task) -> T): T = if (field(form.task) != field(base.task)) field(form.task) else field(current.task)
    val (recurrenceType, recurrenceRule) = pick { it.recurrenceType to it.recurrenceRule }
    val task = current.task.copy(
        title = pick { it.title }, type = pick { it.type }, isStarred = pick { it.isStarred },
        startDate = pick { it.startDate }, dueDate = pick { it.dueDate }, reminderOffsetMinutes = pick { it.reminderOffsetMinutes },
        parentId = pick { it.parentId }, recurrenceType = recurrenceType, recurrenceRule = recurrenceRule,
        isMaybe = pick { it.isMaybe }, expiresAt = pick { it.expiresAt }, sequential = pick { it.sequential }
    )
    return EditState(
        task,
        if (form.contextIds != base.contextIds) form.contextIds else current.contextIds,
        if (form.dependsOn != base.dependsOn) form.dependsOn else current.dependsOn
    )
}

private fun localDateTime(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

private fun HTML.editorPage(service: TaskService, v: EditorView) = shellPage("Raspberry · ${v.shown.task.title.ifBlank { "Edit" }}", v.mode.path) {
    val t = v.shown.task
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    form(action = "/tasks/${t.id}?mode=${v.mode.name}", method = FormMethod.post, classes = "editor") {
        hiddenInput(name = "base") { value = v.base }
        v.error?.let { p(classes = "error") { +it } }
        v.ask?.let { (count, fields) ->
            div(classes = "ask") {
                val what = fields.joinToString(" and ") { it.label }
                p { +"$count subtask${if (count == 1) " has its" else "s have their"} own $what. Clear ${if (count == 1) "it" else "them"} so ${if (count == 1) "it follows" else "they follow"} this task?" }
                button(type = ButtonType.submit, classes = "primary") { name = "inherit"; value = "update"; +"Update subtasks" }
                button(type = ButtonType.submit) { name = "inherit"; value = "only"; +"Only this task" }
            }
        }

        div(classes = "title-row") {
            textInput(name = "title", classes = "title-input") { value = t.title; placeholder = "Title"; required = true }
            label(classes = "star-toggle") {
                attributes["title"] = "Star"
                checkBoxInput(name = "starred") { checked = t.isStarred }
                icon(Icon.STAR, "on"); icon(Icon.STAR_BORDER, "off")
            }
        }

        div(classes = "pills") {
            listOf(TaskType.TASK to "Task", TaskType.PROJECT to "Project", TaskType.FOLDER to "Folder").forEach { (type, label) ->
                label(classes = "pill") { radioInput(name = "type") { value = type.name; checked = t.type == type }; +label }
            }
        }

        div(classes = "field-row") {
            dateField("Start", "start", t.startDate, "")
            dateField("Due", "due", t.dueDate, "task-only")
        }

        field("Remind me", "task-only") {
            select {
                name = "reminder"
                listOf(null to "No reminder", 0 to "At due time", 5 to "5 min before", 30 to "30 min before", 60 to "1 hour before", 1440 to "1 day before")
                    .forEach { (m, label) -> option { value = m?.toString().orEmpty(); selected = t.reminderOffsetMinutes == m; +label } }
            }
        }

        field("Folder", "") {
            select {
                name = "parent"
                option { value = ""; selected = t.parentId == null; +"No folder" }
                byId[t.parentId]?.takeIf { it.type != TaskType.FOLDER }?.let { parent ->
                    option { value = parent.id.toString(); selected = true; +"Subtask of “${parent.title}”" }
                }
                all.filter { it.type == TaskType.FOLDER && !wouldCreateCycle(it.id, t.id, byId) }
                    .map { it to folderPath(it, byId) }.sortedBy { it.second.lowercase() }
                    .forEach { (f, path) -> option { value = f.id.toString(); selected = f.id == t.parentId; +path } }
            }
        }

        field("Repeat", "repeat task-only") {
            val r = v.recurrence
            select {
                name = "repeat"
                listOf(RecurrencePreset.NONE to "Doesn't repeat", RecurrencePreset.CALENDAR to "Every", RecurrencePreset.AFTER_COMPLETION_N_DAYS to "After completion, wait")
                    .forEach { (preset, label) -> option { value = preset.name; selected = r.preset == preset; +label } }
            }
            numberInput(name = "n", classes = "rep-n") { value = r.n.toString(); min = "1"; max = "999" }
            select(classes = "rep-unit") {
                name = "unit"
                listOf(RecurrenceUnit.DAY to "day(s)", RecurrenceUnit.WEEK to "week(s)", RecurrenceUnit.MONTH to "month(s)")
                    .forEach { (unit, label) -> option { value = unit.name; selected = r.unit == unit; +label } }
            }
            span(classes = "rep-after") { +"day(s)" }
            // Monday first, like the phone's pickers; bits are Su=0..Sa=6.
            div(classes = "rep-wd") {
                listOf(1 to "Mo", 2 to "Tu", 3 to "We", 4 to "Th", 5 to "Fr", 6 to "Sa", 0 to "Su").forEach { (bit, label) ->
                    label(classes = "pill") { checkBoxInput(name = "wd") { value = bit.toString(); checked = r.weekdaysMask and (1 shl bit) != 0 }; +label }
                }
            }
            if (t.recurrenceType != null && r.preset == RecurrencePreset.NONE) {
                p(classes = "hint") { +"Custom rule ${t.recurrenceRule}; kept unless you change it here." }
            }
        }

        field("Depends on", "task-only") {
            val edges = service.dependencyEdges().filter { it.taskId != t.id }
            val selected = v.shown.dependsOn
            val candidates = all.filter {
                it.id in selected || (it.id != t.id && !it.isComplete && (it.type == TaskType.TASK || it.type == TaskType.PROJECT) &&
                    !wouldCreateDependencyCycle(it.id, t.id, edges))
            }.sortedWith(compareBy({ it.id !in selected }, { it.title.lowercase() }))
            div(classes = "picker") {
                input(type = InputType.search, classes = "filter") { placeholder = "Filter…"; attributes["data-filter"] = "" }
                div(classes = "options") {
                    candidates.forEach { c ->
                        label { checkBoxInput(name = "dep") { value = c.id.toString(); checked = c.id in selected }; +c.title }
                    }
                }
            }
        }

        field("Contexts", "") {
            val contexts = service.contexts().sortedBy { it.name.lowercase() }
            if (contexts.isEmpty()) span(classes = "hint") { +"No contexts yet. "; a(href = "/contexts") { +"Create one" } }
            div(classes = "pills") {
                contexts.forEach { c ->
                    label(classes = "pill") { checkBoxInput(name = "ctx") { value = c.id.toString(); checked = c.id in v.shown.contextIds }; +"@${c.name}" }
                }
            }
        }

        div(classes = "toggles") {
            label(classes = "task-only") { checkBoxInput(name = "maybe") { checked = t.isMaybe }; +"Maybe (?)" }
            label(classes = "task-only") { checkBoxInput(name = "today") { checked = t.expiresAt != null }; +"Just for today" }
            label { checkBoxInput(name = "sequential") { checked = t.sequential }; +"Complete subtasks in order" }
        }

        if (v.needFirstStep || v.firstStep.isNotEmpty()) {
            field("First step of this project", "") { textInput(name = "firstStep") { value = v.firstStep; placeholder = "Subtask" } }
        }

        div(classes = "actions") {
            button(type = ButtonType.submit, classes = "delete") {
                attributes["formaction"] = "/tasks/${t.id}/delete?mode=${v.mode.name}"
                attributes["formnovalidate"] = ""
                +"Delete"
            }
            a(href = v.mode.path, classes = "cancel") { +"Cancel" }
            button(type = ButtonType.submit, classes = "primary") { +"Save" }
        }
    }
    div { subtasksSection(service, t.id, v.mode) }
}

private fun FlowContent.field(label: String, classes: String, content: FlowContent.() -> Unit) = div(classes = "field $classes") {
    span(classes = "field-label") { +label }
    content()
}

private fun FlowContent.dateField(label: String, prefix: String, millis: Long?, classes: String) = field(label, classes) {
    val at = millis?.let(::localDateTime)
    dateInput(name = "${prefix}Date") { value = at?.toLocalDate()?.toString().orEmpty() }
    timeInput(name = "${prefix}Time") { value = if (millis != null && hasTime(millis)) at!!.toLocalTime().withSecond(0).withNano(0).toString() else "" }
}

private fun folderPath(folder: Task, byId: Map<Long, Task>): String =
    generateSequence(folder) { byId[it.parentId]?.takeIf { p -> p.type == TaskType.FOLDER } }.map { it.title }.toList().reversed().joinToString(" / ")

// Filled into a div by the caller so the fragment's root is #subtasks, which htmx swaps.
fun DIV.subtasksSection(service: TaskService, id: Long, mode: ListMode) {
    this.id = "subtasks"
    classes = setOf("subtasks")
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    val task = byId[id] ?: return
    fun kotlinx.html.HTMLTag.htmx(url: String) {
        attributes["hx-post"] = url
        attributes["hx-target"] = "#subtasks"
        attributes["hx-swap"] = "outerHTML"
    }

    h2 { +"Subtasks" }
    all.filter { it.parentId == id }.sortedWith(TaskOrder).forEach { sub ->
        div(classes = "sub-row") {
            when (sub.type) {
                TaskType.TASK -> button(classes = if (sub.isComplete) "check done" else "check") {
                    attributes["hx-post"] = "/tasks/$id/subtasks/${sub.id}/toggle?mode=${mode.name}"
                    attributes["hx-target"] = "#subtasks"
                    attributes["hx-swap"] = "outerHTML"
                    attributes["aria-label"] = if (sub.isComplete) "Mark not done" else "Complete"
                    if (sub.isComplete) icon(Icon.CHECK, "")
                }
                TaskType.PROJECT -> span(classes = "project") { icon(Icon.PROJECT, "") }
                TaskType.FOLDER -> span(classes = "project") { icon(Icon.FOLDER, "folder-icon") }
            }
            a(href = "/tasks/${sub.id}?mode=${mode.name}", classes = if (sub.isComplete) "done" else null) { +sub.title }
        }
    }
    form(classes = "inline") {
        htmx("/tasks/$id/subtasks?mode=${mode.name}")
        textInput(name = "text") { placeholder = "Add a subtask…"; attributes["autocomplete"] = "off" }
    }
    val moveable = all.filter {
        it.id != id && it.type != TaskType.FOLDER && !it.isComplete && it.parentId != id && !wouldCreateCycle(id, it.id, byId)
    }.sortedBy { it.title.lowercase() }
    if (moveable.isNotEmpty()) {
        form(classes = "inline") {
            htmx("/tasks/$id/adopt?mode=${mode.name}")
            select { name = "child"; moveable.forEach { option { value = it.id.toString(); +it.title } } }
            button(type = ButtonType.submit) { +"Move here as a subtask" }
        }
    }

    // A dependent waits for this task. Folders can't be completed, so nothing can wait on one.
    if (task.type == TaskType.FOLDER) return
    val edges = service.dependencyEdges()
    val waiting = edges.filter { it.dependsOnTaskId == id }.mapNotNull { byId[it.taskId] }
    h2 { +"Waiting for this" }
    waiting.forEach { w -> div(classes = "sub-row") { a(href = "/tasks/${w.id}?mode=${mode.name}") { +w.title } } }
    val candidates = all.filter {
        it.id != id && it.type == TaskType.TASK && !it.isComplete && it !in waiting && !wouldCreateDependencyCycle(id, it.id, edges)
    }.sortedBy { it.title.lowercase() }
    if (candidates.isNotEmpty()) {
        form(classes = "inline") {
            htmx("/tasks/$id/dependent?mode=${mode.name}")
            select { name = "dependent"; candidates.forEach { option { value = it.id.toString(); +it.title } } }
            button(type = ButtonType.submit) { +"Add a task that waits for this" }
        }
    }
}
