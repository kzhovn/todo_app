package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.deadline
import com.kzhovn.todoapp.repository.BulkEdit
import com.kzhovn.todoapp.repository.DateChange
import com.kzhovn.todoapp.repository.DayCompletions
import com.kzhovn.todoapp.repository.FolderChange
import com.kzhovn.todoapp.repository.completionsByDay
import com.kzhovn.todoapp.server.TaskService
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
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
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.radioInput
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.textInput
import kotlinx.html.timeInput
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import java.util.Locale

// Search, Review and Contexts: the phone's secondary screens.

private const val MAX_RESULTS = 200 // ponytail: a cap, not paging; narrow the search past it
private const val CHART_DAYS = 14
private const val LIST_DAYS = 30

fun Route.pageRoutes(service: TaskService) {
    get("/search") { call.respondHtml { searchPage(service, call.request.queryParameters) } }
    get("/search/results") { call.respondResults(service, call.request.queryParameters) }
    // Completing or reopening from the results. The search form's fields come along, so the
    // refreshed results still match it; open subtasks are asked about, as in the lists.
    post("/search/toggle/{id}") {
        val p = call.receiveParameters()
        val id = call.taskId() ?: return@post
        val task = service.get(id) ?: return@post call.respondResults(service, p)
        val choice = call.request.queryParameters["subtasks"]
        val open = service.activeDescendantCount(id)
        when {
            task.isComplete -> service.uncomplete(id)
            open > 0 && choice == null -> return@post call.respondResults(service, p, extra = createHTML().div {
                askSubtasksToast(task, open, "/search/toggle/$id", target = "#results", include = "form.search")
            })
            else -> service.completeOrDecide(id, choice)
        }
        call.respondResults(service, p)
    }

    // Bulk edit, like the phone's: the list's selected tasks, and only what makes sense in bulk.
    get("/bulk") {
        val ids = call.request.queryParameters["ids"].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        call.respondHtml { bulkPage(service, ids, call.mode()) }
    }
    post("/bulk") {
        val p = call.receiveParameters()
        service.applyBulkEdit(p.getAll("id").orEmpty().mapNotNull { it.toLongOrNull() }, parseBulk(p))
        call.respondRedirect(call.mode().path)
    }

    get("/review") { call.respondHtml { reviewPage(service) } }

    get("/contexts") {
        val editing = call.request.queryParameters["edit"]?.toLongOrNull()?.let { id -> service.contexts().firstOrNull { it.id == id } }
        val form = editing?.let { ContextForm(it, service.timeWindows(it.id)) } ?: ContextForm(TaskContext(name = "", type = ContextType.PLACE), emptyList())
        call.respondHtml { contextsPage(service, form) }
    }
    post("/contexts") {
        val p = call.receiveParameters()
        val form = parseContextForm(p)
        val error = when {
            form.context.name.isBlank() -> "A name is required."
            form.context.type == ContextType.PLACE && form.context.wifiSsid.isNullOrBlank() -> "A place needs its wifi network's name."
            form.context.type == ContextType.TIME && form.windows.isEmpty() -> "A time context needs at least one window."
            else -> null
        }
        if (error != null) return@post call.respondHtml { contextsPage(service, form.copy(error = error)) }
        service.saveContext(form.context, form.windows)
        call.respondRedirect("/contexts")
    }
    // Confirmed in the browser (hx-confirm), like the phone: there's no undo for this one.
    post("/contexts/{id}/delete") {
        call.parameters["id"]?.toLongOrNull()?.let(service::deleteContext)
        call.response.header("HX-Redirect", "/contexts")
        call.respondText("")
    }
}

// --- Search

private suspend fun io.ktor.server.application.ApplicationCall.respondResults(service: TaskService, p: Parameters, extra: String = "") =
    respondText(createHTML().div { searchResults(service, p) } + extra, ContentType.Text.Html)

private fun Parameters.searchFilters() = SearchFilters(
    folderId = this["folder"]?.toLongOrNull(),
    contextId = this["context"]?.toLongOrNull(),
    starredOnly = this["starred"] != null,
    includeCompleted = this["completed"] != null,
    dueAfter = dateTime(this["after"], null),
    dueBefore = dateTime(this["before"], null)
)

private fun HTML.searchPage(service: TaskService, p: Parameters) = shellPage("Raspberry · Search", "/search") {
    // Works as a plain GET form; htmx re-runs it as you type.
    form(action = "/search", method = FormMethod.get, classes = "search") {
        attributes["hx-get"] = "/search/results"
        attributes["hx-trigger"] = "input delay:250ms, change"
        attributes["hx-target"] = "#results"
        attributes["hx-swap"] = "outerHTML"
        input(type = InputType.search, name = "q", classes = "search-q") {
            value = p["q"].orEmpty(); placeholder = "Search tasks…"; attributes["autofocus"] = ""; attributes["autocomplete"] = "off"
        }
        div(classes = "pills") {
            label(classes = "pill") { checkBoxInput(name = "starred") { checked = p["starred"] != null }; +"Starred" }
            label(classes = "pill") { checkBoxInput(name = "completed") { checked = p["completed"] != null }; +"Completed" }
            select {
                name = "folder"
                option { value = ""; +"Any folder" }
                service.folders().sortedBy { it.title.lowercase() }.forEach { f ->
                    option { value = f.id.toString(); selected = p["folder"] == f.id.toString(); +f.title }
                }
            }
            select {
                name = "context"
                option { value = ""; +"Any context" }
                service.contexts().sortedBy { it.name.lowercase() }.forEach { c ->
                    option { value = c.id.toString(); selected = p["context"] == c.id.toString(); +"@${c.name}" }
                }
            }
            label(classes = "date-filter") { +"Due after "; dateInput(name = "after") { value = p["after"].orEmpty() } }
            label(classes = "date-filter") { +"Due before "; dateInput(name = "before") { value = p["before"].orEmpty() } }
        }
    }
    div { searchResults(service, p) }
}

private fun DIV.searchResults(service: TaskService, p: Parameters) {
    id = "results"
    classes = setOf("results")
    val results = service.search(p["q"].orEmpty(), p.searchFilters())
    val byId = service.tasks().associateBy { it.id }
    val contextNames = service.contexts().associate { it.id to it.name }
    val contextIds = service.contextIdsByTask()
    val now = service.now()
    val shown = if (results.size > MAX_RESULTS) ", showing the first $MAX_RESULTS" else ""
    p(classes = "hint") { +"${results.size} task${if (results.size == 1) "" else "s"}$shown" }
    results.take(MAX_RESULTS).forEach { task ->
        div(classes = "result") {
            if (task.type == TaskType.TASK) button(classes = if (task.isComplete) "check done" else "check") {
                attributes["hx-post"] = "/search/toggle/${task.id}"
                attributes["hx-include"] = "form.search"
                attributes["hx-target"] = "#results"
                attributes["hx-swap"] = "outerHTML"
                attributes["aria-label"] = if (task.isComplete) "Mark not done" else "Complete"
                if (task.isComplete) icon(Icon.CHECK, "")
            } else span(classes = "project") { icon(Icon.PROJECT, "") }
            a(href = "/tasks/${task.id}?mode=ALL", classes = if (task.isComplete) "done" else null) { +task.title }
            div(classes = "meta") {
                task.dueDate?.let { dueChip(it, now, overdue = !task.isComplete && deadline(it) <= now) }
                byId[task.parentId]?.let { span { +(if (it.type == TaskType.FOLDER) it.title else "↳ ${it.title}") } }
                contextIds[task.id].orEmpty().mapNotNull(contextNames::get).forEach { span { +"@$it" } }
                if (task.isStarred) span(classes = "starred") { +"★" }
            }
        }
    }
}

// --- Bulk edit

// Blank or "keep" means leave as is, matching BulkEdit's nulls.
private fun parseBulk(p: Parameters): BulkEdit {
    fun date(prefix: String) =
        if (p["${prefix}Clear"] != null) DateChange(null) else dateTime(p["${prefix}Date"], p["${prefix}Time"])?.let(::DateChange)
    fun ids(name: String) = p.getAll(name).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    return BulkEdit(
        starred = when (p["star"]) { "star" -> true; "unstar" -> false; else -> null },
        maybe = when (p["maybe"]) { "yes" -> true; "no" -> false; else -> null },
        startDate = date("start"),
        dueDate = date("due"),
        moveTo = when (val folder = p["folder"]) { null, "", "keep" -> null; "top" -> FolderChange(null); else -> folder.toLongOrNull()?.let(::FolderChange) },
        addContextIds = ids("addCtx"),
        removeContextIds = ids("removeCtx") - ids("addCtx"),
        dependsOnId = p["waitFor"]?.toLongOrNull()
    )
}

private fun HTML.bulkPage(service: TaskService, ids: List<Long>, mode: ListMode) = shellPage("Raspberry · Edit tasks", mode.path) {
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    // Folders and projects are skipped, as on the phone: none of the bulk properties apply.
    val tasks = ids.mapNotNull(byId::get).filter { it.type == TaskType.TASK }
    val contexts = service.contexts().sortedBy { it.name.lowercase() }
    fun FlowContent.choice(label: String, name: String, options: List<Pair<String, String>>) = field(label, "") {
        div(classes = "pills") {
            options.forEach { (value, text) -> label(classes = "pill") { radioInput(name = name) { this.value = value; checked = value == "keep" }; +text } }
        }
    }
    fun FlowContent.dateChoice(label: String, prefix: String) = field(label, "") {
        dateInput(name = "${prefix}Date")
        timeInput(name = "${prefix}Time")
        label(classes = "pill") { checkBoxInput(name = "${prefix}Clear"); +"Clear" }
        span(classes = "hint") { +"Leave empty to keep each task's own." }
    }
    fun FlowContent.contextPills(label: String, name: String) = field(label, "") {
        div(classes = "pills") { contexts.forEach { c -> label(classes = "pill") { checkBoxInput(name = name) { value = c.id.toString() }; +"@${c.name}" } } }
    }

    form(action = "/bulk?mode=${mode.name}", method = FormMethod.post, classes = "editor bulk") {
        h1 { +"Edit ${tasks.size} task${if (tasks.size == 1) "" else "s"}" }
        p(classes = "hint") { +(tasks.take(8).joinToString(", ") { it.title } + if (tasks.size > 8) " and ${tasks.size - 8} more" else "") }
        tasks.forEach { hiddenInput(name = "id") { value = it.id.toString() } }
        choice("Star", "star", listOf("keep" to "Keep", "star" to "Star", "unstar" to "Unstar"))
        choice("Maybe (?)", "maybe", listOf("keep" to "Keep", "yes" to "Maybe", "no" to "Not maybe"))
        dateChoice("Start date", "start")
        dateChoice("Due date", "due")
        field("Folder", "") {
            select {
                name = "folder"
                option { value = "keep"; +"Keep each task's folder" }
                option { value = "top"; +"Top level (no folder)" }
                all.filter { it.type == TaskType.FOLDER }.map { it to folderPath(it, byId) }.sortedBy { it.second.lowercase() }
                    .forEach { (f, path) -> option { value = f.id.toString(); +path } }
            }
        }
        if (contexts.isNotEmpty()) {
            contextPills("Add contexts", "addCtx")
            contextPills("Remove contexts", "removeCtx")
        }
        field("Wait for", "") {
            select {
                name = "waitFor"
                option { value = ""; +"Nothing new" }
                all.filter { it.type == TaskType.TASK && !it.isComplete && it.id !in ids }.sortedBy { it.title.lowercase() }
                    .forEach { option { value = it.id.toString(); +it.title } }
            }
        }
        div(classes = "actions") {
            a(href = mode.path, classes = "cancel") { +"Cancel" }
            button(type = ButtonType.submit, classes = "primary") { +"Apply to ${tasks.size} task${if (tasks.size == 1) "" else "s"}" }
        }
    }
}

// --- Review

private fun HTML.reviewPage(service: TaskService) = shellPage("Raspberry · Review", "/review") {
    val all = service.tasks()
    val folderNames = all.filter { it.type == TaskType.FOLDER }.associate { it.id to it.title }
    val days = completionsByDay(all, service.now(), service.rolloverHour(), LIST_DAYS)
    val week = days.take(7).sumOf { it.tasks.size }
    div(classes = "review") {
        h1 { +"Review" }
        p(classes = "hint") { +"$week done in the last 7 days · ${"%.1f".format(week / 7.0)} a day" }
        completionChart(days.take(CHART_DAYS).reversed())
        // The chart's table view, too: every completion, day by day.
        days.filter { it.tasks.isNotEmpty() }.forEach { day ->
            h2 { +"${SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(day.dayStart))} · ${day.tasks.size}" }
            day.tasks.forEach { task ->
                div(classes = "done-row") {
                    span(classes = "tick") { +"✓" }
                    a(href = "/tasks/${task.id}?mode=ALL") { +task.title }
                    task.parentId?.let(folderNames::get)?.let { span(classes = "folder-name") { +it } }
                }
            }
        }
        if (days.none { it.tasks.isNotEmpty() }) p(classes = "empty") { +"Nothing completed in the last $LIST_DAYS days." }
    }
}

// Oldest to newest, left to right: count above each bar, weekday below, and the full date
// on hover. Mirrors the phone's CompletionChart.
private fun FlowContent.completionChart(days: List<DayCompletions>) {
    val max = days.maxOfOrNull { it.tasks.size }?.coerceAtLeast(1) ?: return
    div(classes = "chart") {
        attributes["role"] = "img"
        attributes["aria-label"] = "Tasks completed per day, last ${days.size} days"
        days.forEach { day ->
            val date = SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(day.dayStart))
            div(classes = "day") {
                attributes["title"] = "$date: ${day.tasks.size} done"
                span(classes = "count") { +(if (day.tasks.isEmpty()) "" else day.tasks.size.toString()) }
                div(classes = "plot") { div(classes = "bar") { style = "height: ${100.0 * day.tasks.size / max}%" } }
                // Two letters: the JVM, unlike Android, has no one-letter weekday pattern.
                span(classes = "weekday") { +SimpleDateFormat("EEE", Locale.US).format(Date(day.dayStart)).take(2) }
            }
        }
    }
}

// --- Contexts

private data class ContextForm(val context: TaskContext, val windows: List<ContextTimeWindow>, val error: String? = null)

private fun minutesOf(time: String?) = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }?.let { it.hour * 60 + it.minute }

private fun parseContextForm(p: Parameters): ContextForm {
    val type = p["type"]?.let { runCatching { ContextType.valueOf(it) }.getOrNull() } ?: ContextType.PLACE
    val starts = p.getAll("start").orEmpty()
    val ends = p.getAll("end").orEmpty()
    // A window row left blank is dropped; one with no days ticked counts as every day.
    val windows = starts.indices.mapNotNull { i ->
        val start = minutesOf(starts[i]) ?: return@mapNotNull null
        val end = minutesOf(ends.getOrNull(i)) ?: return@mapNotNull null
        val mask = p.getAll("days$i").orEmpty().mapNotNull { it.toIntOrNull()?.takeIf { d -> d in 0..6 } }.fold(0) { m, d -> m or (1 shl d) }
        ContextTimeWindow(contextId = 0, windowStartMinute = start, windowEndMinute = end, daysMask = if (mask == 0) ContextTimeWindow.ALL_DAYS else mask)
    }
    val context = TaskContext(
        id = p["id"]?.toLongOrNull() ?: 0,
        name = p["name"].orEmpty().trim(),
        type = type,
        wifiSsid = p["ssid"]?.trim()?.takeIf { it.isNotEmpty() }
    )
    return ContextForm(context, windows)
}

private fun describe(context: TaskContext, windows: List<ContextTimeWindow>): String = when (context.type) {
    ContextType.PLACE -> "on wifi “${context.wifiSsid}”"
    ContextType.TIME -> windows.joinToString(", ") { w ->
        val days = if (w.daysMask == ContextTimeWindow.ALL_DAYS) "every day"
        else WEEKDAYS.filter { (bit, _) -> w.daysMask and (1 shl bit) != 0 }.joinToString(" ") { it.second }
        "${hhmm(w.windowStartMinute)}–${hhmm(w.windowEndMinute)} $days"
    }
}

private fun hhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

// Monday first, like the phone's pickers; bits are Su=0..Sa=6.
private val WEEKDAYS = listOf(1 to "Mo", 2 to "Tu", 3 to "We", 4 to "Th", 5 to "Fr", 6 to "Sa", 0 to "Su")

private fun HTML.contextsPage(service: TaskService, form: ContextForm) = shellPage("Raspberry · Contexts", "/contexts") {
    div(classes = "contexts") {
        h1 { +"Contexts" }
        p(classes = "hint") { +"Tasks with a context are active only while it holds: on a wifi network (the phone checks), or within a time window." }
        service.contexts().sortedBy { it.name.lowercase() }.forEach { c ->
            div(classes = "context-row") {
                span(classes = "context-name") { +"@${c.name}" }
                span(classes = "hint") { +describe(c, service.timeWindows(c.id)) }
                a(href = "/contexts?edit=${c.id}") { +"Edit" }
                form(classes = "inline-delete") {
                    attributes["hx-post"] = "/contexts/${c.id}/delete"
                    attributes["hx-confirm"] = "Delete @${c.name}? It will be removed from every task using it. This can't be undone."
                    button(type = ButtonType.submit, classes = "delete") { +"Delete" }
                }
            }
        }

        val c = form.context
        form(action = "/contexts", method = FormMethod.post, classes = "editor context-form") {
            h2 { +(if (c.id == 0L) "New context" else "Edit @${c.name}") }
            form.error?.let { p(classes = "error") { +it } }
            if (c.id != 0L) hiddenInput(name = "id") { value = c.id.toString() }
            textInput(name = "name", classes = "title-input") { value = c.name; placeholder = "Name"; required = true }
            div(classes = "pills") {
                label(classes = "pill") { radioInput(name = "type") { value = ContextType.PLACE.name; checked = c.type == ContextType.PLACE }; +"Place (wifi)" }
                label(classes = "pill") { radioInput(name = "type") { value = ContextType.TIME.name; checked = c.type == ContextType.TIME }; +"Time window" }
            }
            div(classes = "field place-only") {
                span(classes = "field-label") { +"Wifi network name (SSID)" }
                textInput(name = "ssid") { value = c.wifiSsid.orEmpty(); placeholder = "e.g. HomeNetwork" }
            }
            div(classes = "field time-only") {
                span(classes = "field-label") { +"Windows (an end before the start runs past midnight)" }
                // Existing windows plus two blank rows; save and reopen to add more.
                val rows = form.windows.map { Triple(hhmm(it.windowStartMinute), hhmm(it.windowEndMinute), it.daysMask) } +
                    List(2) { Triple("", "", ContextTimeWindow.ALL_DAYS) }
                rows.forEachIndexed { i, (start, end, mask) ->
                    div(classes = "window-row") {
                        timeInput(name = "start") { value = start }
                        +"–"
                        timeInput(name = "end") { value = end }
                        WEEKDAYS.forEach { (bit, label) ->
                            label(classes = "pill") { checkBoxInput(name = "days$i") { value = bit.toString(); checked = mask and (1 shl bit) != 0 }; +label }
                        }
                    }
                }
            }
            div(classes = "actions") {
                if (c.id != 0L) a(href = "/contexts", classes = "cancel") { +"Cancel" }
                button(type = ButtonType.submit, classes = "primary") { +(if (c.id == 0L) "Create" else "Save") }
            }
        }
    }
}
