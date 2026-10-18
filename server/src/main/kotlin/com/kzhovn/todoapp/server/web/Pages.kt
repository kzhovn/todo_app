package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.repository.ContextStates
import kotlinx.html.noScript
import com.kzhovn.todoapp.data.clockTime
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.isLinkable
import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.contextProblem
import com.kzhovn.todoapp.data.describeContext
import com.kzhovn.todoapp.data.deleteContextQuestion
import com.kzhovn.todoapp.data.folderTree
import com.kzhovn.todoapp.data.forcedChoices
import com.kzhovn.todoapp.data.forcedShown
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.sectionsByTopFolder
import com.kzhovn.todoapp.data.nearestFolder
import com.kzhovn.todoapp.repository.BulkEdit
import com.kzhovn.todoapp.repository.DateChange
import com.kzhovn.todoapp.repository.FolderChange
import com.kzhovn.todoapp.repository.DONE_BUCKETS
import com.kzhovn.todoapp.repository.ReviewBar
import com.kzhovn.todoapp.repository.ReviewPage
import com.kzhovn.todoapp.repository.WAITING_BUCKETS
import com.kzhovn.todoapp.repository.Zoom
import com.kzhovn.todoapp.repository.bucketCounts
import com.kzhovn.todoapp.repository.review
import com.kzhovn.todoapp.repository.shortAge
import com.kzhovn.todoapp.server.TaskService
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
import kotlinx.html.style
import kotlinx.html.textInput
import kotlinx.html.timeInput
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Review and Contexts: the phone's secondary screens.

fun Route.pageRoutes(service: TaskService) {
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

    get("/review") {
        val q = call.request.queryParameters
        val zoom = q["zoom"]?.let { z -> Zoom.entries.firstOrNull { it.name.equals(z, ignoreCase = true) } } ?: Zoom.MONTH
        val end = q["end"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        call.respondHtml { reviewPage(service, zoom, end, everywhere = q["everywhere"] != null) }
    }

    // Folder mode on every device: ?folder=<id>, or none to leave. The page reloads into it.
    post("/mode") {
        service.setMode(call.request.queryParameters["folder"]?.toLongOrNull())
        call.response.header("HX-Refresh", "true")
        call.respondText("")
    }

    get("/settings") { call.respondHtml { settingsPage(service) } }
    get("/syntax") { call.respondHtml { syntaxPage(service) } }
    post("/settings") {
        val p = call.receiveParameters()
        p["rolloverHour"]?.toIntOrNull()?.let(service::setRolloverHour)
        p["reminderHour"]?.toIntOrNull()?.takeIf { it in 0..23 }?.let(service::setReminderHour)
        service.setDigestOn(p["digest"] != null) // an unticked box sends nothing
        call.respondRedirect("/settings")
    }
    // Settings → Calendars: a calendar shown or hidden (?id=, since Google's ids have / and @ in them).
    post("/settings/calendar") {
        val p = call.receiveParameters()
        p["id"]?.let { service.setCalendarShown(it, p["shown"] == "true") }
        call.respondRedirect("/settings#calendars")
    }
    get("/contexts") {
        val editing = call.request.queryParameters["edit"]?.toLongOrNull()?.let { id -> service.contexts().firstOrNull { it.id == id } }
        val form = editing?.let { ContextForm(it, service.timeWindows(it.id)) } ?: ContextForm(TaskContext(name = "", type = ContextType.PLACE), emptyList())
        call.respondHtml { contextsPage(service, form) }
    }
    post("/contexts") {
        val p = call.receiveParameters()
        val form = parseContextForm(p)
        val error = contextProblem(form.context, form.windows)
        if (error != null) return@post call.respondHtml { contextsPage(service, form.copy(error = error)) }
        service.saveContext(form.context, form.windows)
        call.respondRedirect("/contexts")
    }
    // From the contexts page, or a context's ⋯ in the sidebar (?back: the page it was on).
    post("/contexts/{id}/forced") {
        val forced = call.receiveParameters()["forced"]?.toBooleanStrictOrNull() // "auto": null
        call.parameters["id"]?.toLongOrNull()?.let { service.setContextForced(it, forced) }
        call.respondRedirect(call.request.queryParameters["back"]?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/contexts")
    }
    // Confirmed in the browser (hx-confirm), like the phone: there's no undo for this one.
    post("/contexts/{id}/delete") {
        call.parameters["id"]?.toLongOrNull()?.let(service::deleteContext)
        call.response.header("HX-Redirect", "/contexts")
        call.respondText("")
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
        dependsOnId = p["dependsOn"]?.toLongOrNull()
    )
}

private fun HTML.bulkPage(service: TaskService, ids: List<Long>, mode: ListMode) = shellPage(service, "Raspberry · Edit tasks", mode.path) {
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    // Only tasks and checklists, as on the phone: none of the bulk properties apply to folders or projects.
    val tasks = ids.mapNotNull(byId::get).filter { it.type.isDoable }
    val contexts = service.contexts().sortedBy { it.name.lowercase() }
    fun FlowContent.choice(label: String, name: String, options: List<Pair<String, String>>) = field(label, "") {
        div(classes = "pills") {
            options.forEach { (value, text) -> label(classes = "pill") { radioInput(name = name) { this.value = value; checked = value == "keep" }; +text } }
        }
    }
    fun FlowContent.dateChoice(label: String, prefix: String) = field(label, "") {
        dateInput(name = "${prefix}Date")
        timeInput(name = "${prefix}Time")
        label(classes = "pill") { checkBoxInput(name = "${prefix}Clear"); +Labels.CLEAR }
        span(classes = "hint") { +"Leave empty to keep each task's own." }
    }
    fun FlowContent.contextPills(label: String, name: String) = field(label, "") {
        div(classes = "pills") { contexts.forEach { c -> label(classes = "pill") { checkBoxInput(name = name) { value = c.id.toString() }; +"@${c.name}" } } }
    }

    form(action = "/bulk?mode=${mode.name}", method = FormMethod.post, classes = "editor bulk") {
        h1 { +Labels.editTasks(tasks.size) }
        p(classes = "hint") { +(tasks.take(8).joinToString(", ") { it.title } + if (tasks.size > 8) " and ${tasks.size - 8} more" else "") }
        tasks.forEach { hiddenInput(name = "id") { value = it.id.toString() } }
        choice(Labels.STAR, "star", listOf("keep" to Labels.KEEP, "star" to Labels.STAR, "unstar" to Labels.UNSTAR))
        choice(Labels.MAYBE_CHOICE, "maybe", listOf("keep" to Labels.KEEP, "yes" to Labels.MAYBE, "no" to Labels.NOT_MAYBE))
        dateChoice(Labels.START_DATE, "start")
        dateChoice(Labels.DUE_DATE, "due")
        field(Labels.FOLDER, "") {
            select {
                name = "folder"
                option { value = "keep"; +Labels.KEEP }
                option { value = "top"; +Labels.TOP_LEVEL }
                folderTree(all.filter { it.type == TaskType.FOLDER }).forEach { (f, depth) -> option { value = f.id.toString(); +(INDENT.repeat(depth) + f.title) } }
            }
        }
        if (contexts.isNotEmpty()) {
            contextPills(Labels.ADD_CONTEXTS, "addCtx")
            contextPills(Labels.REMOVE_CONTEXTS, "removeCtx")
        }
        field(Labels.DEPENDS_ON, "") {
            select {
                name = "dependsOn"
                option { value = ""; +Labels.NOTHING_NEW }
                all.filter { it.type.isLinkable && !it.isComplete && it.id !in ids }.sortedBy { it.title.lowercase() }
                    .forEach { option { value = it.id.toString(); +it.title } }
            }
        }
        div(classes = "actions") {
            a(href = mode.path, classes = "cancel") { +Labels.CANCEL }
            button(type = ButtonType.submit, classes = "primary") { +Labels.applyToTasks(tasks.size) }
        }
    }
}

// --- Review

// Rolling windows, stepped and zoomed through links, so Back and bookmarks work without JavaScript
// (app.js adds ← → − + keys that follow the links marked with data-key).
private fun reviewUrl(zoom: Zoom, end: LocalDate, today: LocalDate, everywhere: Boolean) =
    "/review?zoom=${zoom.name.lowercase()}" + (if (zoom != Zoom.ALL && end != today) "&end=$end" else "") + if (everywhere) "&everywhere=1" else ""

// In folder mode, the mode's folder only, unless `everywhere`.
private fun HTML.reviewPage(service: TaskService, zoom: Zoom, end: LocalDate?, everywhere: Boolean) = shellPage(service, "Raspberry · Review", "/review") {
    val all = service.tasks()
    val byId = all.associateBy { it.id }
    val colors = folderColorsHex(all)
    // Grouped and coloured by top-level folder, like the phone's Review.
    val colorOf = { folder: Task? -> folder?.let { colors[it.id] } ?: "var(--muted)" }
    val mode = service.modeFolder()?.takeUnless { everywhere }
    val waiting = service.waiting(everywhere)
    val page = review(all, waiting, service.now(), service.rolloverHour(), zoom, end, modeFolderId = mode?.id)
    fun url(z: Zoom, e: LocalDate, all: Boolean = everywhere) = reviewUrl(z, e, page.today, all)
    val ages = page.timeToDone.associate { it.task.id to it.ms }
    // The tick takes the task's own folder shade, as its row's colour bar does.
    fun FlowContent.taskRow(task: Task, right: String?, done: Boolean = true) = div(classes = "done-row") {
        val tick = nearestFolder(task, byId)?.let { colors[it.id] } ?: "var(--muted)"
        span(classes = "tick") { style = "color: ${if (done) tick else "var(--muted)"}"; +(if (done) "✓" else "○") }
        a(href = "/tasks/${task.id}?mode=ALL") { +task.title }
        right?.let { span(classes = "age") { +it } }
    }
    fun FlowContent.hbars(counts: List<Pair<String, Int>>, color: String) = div(classes = "hbars") {
        val max = counts.maxOf { it.second }.coerceAtLeast(1)
        counts.forEach { (label, n) ->
            span(classes = "lab") { +label }
            div(classes = "track") { div(classes = "fill") { style = "width: ${n * 100 / max}%; background: $color" } }
            span(classes = "num") { +n.toString() }
        }
    }
    fun FlowContent.section(title: String, caption: String? = null) = div(classes = "sec") {
        +title
        caption?.let { span(classes = "cap") { +it } }
    }

    div(classes = "review") {
        h1 {
            +Labels.REVIEW
            mode?.let { span(classes = "mode-name") { +it.title } }
        }
        // Folder mode: this is the mode's; the whole list is a click away (and back).
        service.modeFolder()?.let { m ->
            p(classes = "hint") {
                if (everywhere) a(href = url(zoom, page.end, all = false)) { +Labels.justFolder(m.title) }
                else a(href = url(zoom, page.end, all = true)) { +Labels.SEE_ALL_FOLDERS }
            }
        }
        div(classes = "review-controls") {
            div(classes = "seg") {
                Zoom.entries.forEach { z ->
                    a(href = url(z, page.end), classes = if (z == zoom) "on" else null) {
                        if (z.ordinal == zoom.ordinal + 1) attributes["data-key"] = "-"
                        if (z.ordinal == zoom.ordinal - 1) attributes["data-key"] = "+"
                        +z.name.lowercase().replaceFirstChar { it.uppercase() }
                    }
                }
            }
            div(classes = "step") {
                page.previous?.let { a(href = url(zoom, it)) { attributes["data-key"] = "ArrowLeft"; attributes["aria-label"] = "Earlier"; +"‹" } }
                span { +page.windowLabel }
                page.next?.let { a(href = url(zoom, it)) { attributes["data-key"] = "ArrowRight"; attributes["aria-label"] = "Later"; +"›" } }
            }
            if (page.end != page.today && zoom != Zoom.ALL) a(href = url(zoom, page.today), classes = "today-link") { +Labels.BACK_TO_TODAY }
        }

        reviewChart(page, byId, colorOf) { bar -> page.zoomIn(bar)?.let { (z, e) -> url(z, e) } ?: "#d-${bar.first}" }
        div(classes = "legend") {
            sectionsByTopFolder(page.done, byId).forEach { (folder, _) ->
                span { span(classes = "swatch") { style = "background: ${colorOf(folder)}" }; +(folder?.title ?: Labels.NO_FOLDER) }
            }
        }

        if (page.timeToDone.isNotEmpty()) {
            section(Labels.TIME_TO_DONE, Labels.timeToDoneCaption(page.timeToDone.size))
            div(classes = "twocol") {
                div { hbars(bucketCounts(page.timeToDone, DONE_BUCKETS), "var(--accent)") }
                div {
                    div(classes = "folder-name") { +Labels.TOOK_LONGEST }
                    page.timeToDone.sortedByDescending { it.ms }.take(5).forEach { taskRow(it.task, shortAge(it.ms)) }
                }
            }
        }
        if (page.waiting.isNotEmpty()) {
            section(Labels.WAITING_NOW, Labels.WAITING_NOW_CAPTION)
            div(classes = "twocol") {
                div { hbars(bucketCounts(page.waiting, WAITING_BUCKETS), "var(--overdue)") }
                div {
                    div(classes = "folder-name") { +Labels.WAITED_LONGEST }
                    page.waiting.take(5).forEach { taskRow(it.task, shortAge(it.ms), done = false) }
                }
            }
        }
        if (page.dueOutcomes.isNotEmpty()) {
            val late = page.dueOutcomes.filter { it.ms > 0 }.sortedByDescending { it.ms }
            section(Labels.DUE_DATES)
            div(classes = "due-bar") {
                attributes["role"] = "img"
                attributes["aria-label"] = "${page.dueOutcomes.size - late.size} on time, ${late.size} late"
                div(classes = "on-time") { style = "flex: ${page.dueOutcomes.size - late.size}" }
                div(classes = "late") { style = "flex: ${late.size}" }
            }
            if (late.isNotEmpty()) {
                div(classes = "folder-name") { +Labels.LATEST }
                late.take(5).forEach { taskRow(it.task, Labels.late(it.ms)) }
            }
        }

        if (zoom == Zoom.WEEK || zoom == Zoom.MONTH) {
            // The chart's table view, too: every completion, day by day, each with its time to done.
            section(Labels.doneIn(page.windowLabel))
            page.bars.reversed().filter { it.tasks.isNotEmpty() }.forEach { day ->
                h2 { id = "d-${day.first}"; +"${page.barLabel(day)} · ${day.tasks.size}" }
                sectionsByTopFolder(day.tasks, byId).forEach { (folder, tasks) ->
                    div(classes = "folder-name") { +(folder?.title ?: Labels.NO_FOLDER) }
                    tasks.forEach { taskRow(it, ages[it.id]?.let(::shortAge)) }
                }
            }
        } else {
            section(Labels.BY_MONTH)
            page.months.forEach { (month, tasks) ->
                a(href = url(Zoom.MONTH, page.monthEnd(month)), classes = "month-row") {
                    span(classes = "month-name") { +month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)) }
                    div(classes = "mini") {
                        sectionsByTopFolder(tasks, byId).forEach { (folder, group) -> div { style = "background: ${colorOf(folder)}; flex: ${group.size}" } }
                    }
                    span(classes = "num") { +tasks.size.toString() }
                }
            }
        }
        if (page.done.isEmpty()) p(classes = "empty") { +Labels.nothingDoneIn(page.windowLabel) }
    }
}

// Oldest to newest, left to right: the count above each bar (Week and Month), the axis label below,
// and the dates with a per-folder breakdown on hover. Each bar links where a click should go:
// zoomed in, or down to that day in the list. Mirrors the phone's ReviewChart.
private fun FlowContent.reviewChart(page: ReviewPage, byId: Map<Long, Task>, colorOf: (Task?) -> String, href: (ReviewBar) -> String) {
    val max = page.bars.maxOfOrNull { it.tasks.size }?.coerceAtLeast(1) ?: return
    val counts = page.zoom == Zoom.WEEK || page.zoom == Zoom.MONTH
    div(classes = if (page.bars.size > 31) "chart dense" else "chart") {
        attributes["role"] = "img"
        attributes["aria-label"] = "Tasks completed, ${page.windowLabel}"
        page.bars.forEachIndexed { i, bar ->
            val segments = sectionsByTopFolder(bar.tasks, byId)
            a(href = href(bar), classes = "day") {
                attributes["title"] = "${page.barLabel(bar)}: ${bar.tasks.size} done" + segments.joinToString("") { (f, t) -> "\n${f?.title ?: Labels.NO_FOLDER}: ${t.size}" }
                div(classes = "plot") {
                    // The count sits on its bar; the tallest bar leaves room for it (the 16px).
                    if (counts && bar.tasks.isNotEmpty()) span(classes = "day-count") { +bar.tasks.size.toString() }
                    // Segments stack bottom-up, sized by count.
                    div(classes = "bar") {
                        style = "height: calc((100% - ${if (counts) 16 else 0}px) * ${bar.tasks.size.toDouble() / max})"
                        segments.forEach { (folder, tasks) -> div { style = "background: ${colorOf(folder)}; flex: ${tasks.size}" } }
                    }
                }
                span(classes = "weekday") { +page.axisLabel(i) }
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
        wifiSsid = p["ssid"]?.trim()?.takeIf { it.isNotEmpty() },
        calendarIds = if (type == ContextType.EVENTS) p.getAll("calendar").orEmpty() else emptyList(),
        titleWords = p["words"]?.trim()?.takeIf { it.isNotEmpty() && type == ContextType.EVENTS },
        busyOnly = type != ContextType.EVENTS || p["busyOnly"] != null,
        offWhile = p.getAll("offWhile").orEmpty().mapNotNull { it.toLongOrNull() }
    )
    return ContextForm(context, windows)
}

// The phone's settings that belong to every device: when the day rolls over.
private fun HTML.settingsPage(service: TaskService) = shellPage(service, "Raspberry · Settings", "/settings") {
    div(classes = "contexts") {
        h1 { +Labels.SETTINGS }
        val rolloverHour = service.rolloverHour()
        val reminderHour = service.reminderHour()
        form(action = "/settings", method = FormMethod.post, classes = "settings") {
            label {
                +"Day rolls over at "
                select {
                    name = "rolloverHour"
                    (0..23).forEach { h -> option { value = "$h"; selected = h == rolloverHour; +clockTime(h * 60) } }
                }
            }
            p(classes = "hint") { +"“${Labels.TODAY_ONLY}” tasks are deleted at this time, and a snooze to tomorrow wakes then. The phone uses it too." }
            label {
                +"Reminders on a date with no time go off at "
                select {
                    name = "reminderHour"
                    (0..23).forEach { h -> option { value = "$h"; selected = h == reminderHour; +clockTime(h * 60) } }
                }
            }
            p(classes = "hint") { +"For a reminder when a task starts, before it's due, or on a day you pick, when that date has no time. Rung on the phone." }
            label {
                checkBoxInput(name = "digest") { checked = service.digestOn() }
                +" Morning digest in Discord"
            }
            p(classes = "hint") { +Labels.DIGEST_CAPTION }
            noScript { button(type = ButtonType.submit) { +Labels.SAVE } }
        }
        calendarSettings(service)
    }
}

// Every calendar the Google account sees, each shown or hidden.
private fun FlowContent.calendarSettings(service: TaskService) {
    h2 { id = "calendars"; +Labels.CALENDARS }
    val calendars = service.calendar().calendars
    if (calendars.isEmpty()) return p(classes = "hint") { +Labels.NO_CALENDAR }
    p(classes = "hint") { +Labels.SHOW_CALENDARS }
    val hidden = service.hiddenCalendars()
    calendars.forEach { cal ->
        form(action = "/settings/calendar", method = FormMethod.post, classes = "calendar-row") {
            hiddenInput(name = "id") { value = cal.id }
            hiddenInput(name = "shown") { value = (cal.id in hidden).toString() }
            span(classes = "cal-dot") { style = "background: ${cal.color ?: "var(--muted)"}" }
            span(classes = "cal-name") { +cal.name }
            button(type = ButtonType.submit, classes = if (cal.id in hidden) "pill" else "pill set") { +(if (cal.id in hidden) "Hidden" else "Shown") }
        }
    }
}

private fun HTML.contextsPage(service: TaskService, form: ContextForm) = shellPage(service, "Raspberry · Contexts", "/contexts") {
    div(classes = "contexts") {
        h1 { +"Contexts" }
        p(classes = "hint") { +"Tasks with a context are active only while it holds: on a wifi network (the phone checks), within a time window, or during matching Google Calendar events. A filter-only one just groups tasks to filter by, unless switched Off. Any context can be Off while an events one holds (@errands while @busy). On or Off holds a context that way until it's set back to Auto." }
        val contexts = service.contexts()
        val states = ContextStates(contexts, contexts.flatMap { service.timeWindows(it.id) }, service.events(), service.now())
        contexts.sortedBy { it.name.lowercase() }.forEach { c ->
            div(classes = "context-row") {
                span(classes = "context-name") { +"@${c.name}" }
                span(classes = "hint") { +listOfNotNull(describeContext(c, service.timeWindows(c.id), service.calendar().calendars, contexts), states.eventStateText(c.id)).joinToString(" · ") }
                form(action = "/contexts/${c.id}/forced", method = FormMethod.post, classes = "pills context-forced") {
                    forcedChoices(c).forEach { (value, label) ->
                        button(type = ButtonType.submit, name = "forced", classes = if (forcedShown(c) == value) "pill set" else "pill") {
                            this.value = value?.toString() ?: "auto"; +label
                        }
                    }
                }
                a(href = "/contexts?edit=${c.id}") { +"Edit" }
                form(classes = "inline-delete") {
                    attributes["hx-post"] = "/contexts/${c.id}/delete"
                    attributes["hx-confirm"] = "Delete @${c.name}? " + deleteContextQuestion(c.name)
                    button(type = ButtonType.submit, classes = "delete") { +Labels.DELETE }
                }
            }
        }

        val c = form.context
        form(action = "/contexts", method = FormMethod.post, classes = "editor context-form") {
            h2 { +(if (c.id == 0L) Labels.NEW_CONTEXT else "Edit @${c.name}") }
            form.error?.let { p(classes = "error") { +it } }
            if (c.id != 0L) hiddenInput(name = "id") { value = c.id.toString() }
            textInput(name = "name", classes = "title-input") { value = c.name; placeholder = "Name"; required = true }
            div(classes = "pills") {
                label(classes = "pill") { radioInput(name = "type") { value = ContextType.PLACE.name; checked = c.type == ContextType.PLACE }; +Labels.PLACE_CONTEXT }
                label(classes = "pill") { radioInput(name = "type") { value = ContextType.TIME.name; checked = c.type == ContextType.TIME }; +Labels.TIME_CONTEXT }
                label(classes = "pill") { radioInput(name = "type") { value = ContextType.LABEL.name; checked = c.type == ContextType.LABEL }; +Labels.LABEL_CONTEXT }
                label(classes = "pill") { radioInput(name = "type") { value = ContextType.EVENTS.name; checked = c.type == ContextType.EVENTS }; +Labels.EVENTS_CONTEXT }
            }
            div(classes = "field events-only") {
                span(classes = "field-label") { +Labels.DURING_EVENTS_ON }
                val calendars = service.calendar().calendars
                if (calendars.isEmpty()) p(classes = "hint") { +Labels.NO_CALENDAR }
                div(classes = "pills") {
                    calendars.forEach { cal ->
                        label(classes = "pill") { checkBoxInput(name = "calendar") { value = cal.id; checked = cal.id in c.calendarIds }; +cal.name }
                    }
                }
                span(classes = "field-label") { +Labels.TITLED_WITH }
                textInput(name = "words") { value = c.titleWords.orEmpty(); placeholder = "e.g. oncall for" }
                label { checkBoxInput(name = "busyOnly") { checked = c.busyOnly }; +" ${Labels.ONLY_BUSY_EVENTS}" }
            }
            // Only Events contexts turn others off (one level: see ContextStates).
            val eventsContexts = service.contexts().filter { it.type == ContextType.EVENTS && it.id != c.id }
            if (eventsContexts.isNotEmpty()) div(classes = "field") {
                span(classes = "field-label") { +Labels.OFF_WHILE }
                div(classes = "pills") {
                    eventsContexts.forEach { e ->
                        label(classes = "pill") { checkBoxInput(name = "offWhile") { value = e.id.toString(); checked = e.id in c.offWhile }; +"@${e.name}" }
                    }
                }
            }
            div(classes = "field place-only") {
                span(classes = "field-label") { +"Wifi network name (SSID)" }
                textInput(name = "ssid") { value = c.wifiSsid.orEmpty(); placeholder = "e.g. HomeNetwork" }
            }
            div(classes = "field time-only") {
                span(classes = "field-label") { +"Windows (an end before the start runs past midnight)" }
                // Existing windows plus two blank rows; save and reopen to add more.
                val rows = form.windows.map { Triple(clockTime(it.windowStartMinute), clockTime(it.windowEndMinute), it.daysMask) } +
                    List(2) { Triple("", "", ContextTimeWindow.ALL_DAYS) }
                rows.forEachIndexed { i, (start, end, mask) ->
                    div(classes = "window-row") {
                        timeInput(name = "start") { value = start }
                        +"–"
                        timeInput(name = "end") { value = end }
                        Labels.WEEKDAYS.forEach { (bit, label) ->
                            label(classes = "pill") { checkBoxInput(name = "days$i") { value = bit.toString(); checked = mask and (1 shl bit) != 0 }; +label }
                        }
                    }
                }
            }
            div(classes = "actions") {
                if (c.id != 0L) a(href = "/contexts", classes = "cancel") { +Labels.CANCEL }
                button(type = ButtonType.submit, classes = "primary") { +(if (c.id == 0L) Labels.CREATE else Labels.SAVE) }
            }
        }
    }
}

// A dropdown can't hold a real tree, so subfolders are indented under their parent, in the tree's order.
internal const val INDENT = "\u00A0\u00A0\u00A0"
