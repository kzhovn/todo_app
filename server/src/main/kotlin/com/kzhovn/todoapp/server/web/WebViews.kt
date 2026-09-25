package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.deadline
import com.kzhovn.todoapp.data.hasTime
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.data.walkParentChain
import com.kzhovn.todoapp.server.TaskService
import kotlinx.html.BODY
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.summary
import kotlinx.html.textInput
import kotlinx.html.title
import kotlinx.html.unsafe
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class ListMode(val label: String) { DOING("Doing"), ACTIVE("Active"), ALL("All") }

// The app's folder palette (LedgerFolderPalette), assigned the same way: by folder creation order.
private val FOLDER_PALETTE = listOf("#8A4C2E", "#6B8A2E", "#2E8A4D", "#2E6B8A", "#4D2E8A", "#8A2E6B")

// Everything a list needs, computed once per request from the live task set.
class ListData(service: TaskService, val mode: ListMode, val now: Long = service.now()) {
    val all: List<Task> = service.tasks()
    private val byId = all.associateBy { it.id }
    private val contextIds = service.contextIdsByTask()
    private val contextNames = service.contexts().associate { it.id to it.name }
    private val folderColors = all.filter { it.type == TaskType.FOLDER }.sortedBy { it.id }
        .mapIndexed { i, f -> f.id to FOLDER_PALETTE[i % FOLDER_PALETTE.size] }.toMap()
    private val children = all.groupBy { it.parentId }
    val tasks: List<Task> = when (mode) {
        ListMode.DOING -> service.doing()
        ListMode.ACTIVE -> service.active()
        ListMode.ALL -> emptyList() // the tree is rendered from `children`
    }

    fun effectiveDue(task: Task) = resolveEffective(task, byId, contextIds).effectiveDueDate
    fun contextName(task: Task) = resolveEffective(task, byId, contextIds).effectiveContextIds.firstOrNull()?.let(contextNames::get)
    fun isSubtask(task: Task) = byId[task.parentId]?.type.let { it == TaskType.TASK || it == TaskType.PROJECT }
    fun folderColor(task: Task): String? = task.parentId?.let { parent -> walkParentChain(parent, byId) { folderColors[it] } }
    fun ownColor(folder: Task) = folderColors[folder.id]
    fun subtaskCounts(task: Task): Pair<Int, Int>? =
        children[task.id].orEmpty().filter { it.type != TaskType.FOLDER }.takeIf { it.isNotEmpty() }?.let { kids -> kids.count { it.isComplete } to kids.size }
    fun openChildren(parentId: Long?) = children[parentId].orEmpty().filter { !it.isComplete }.sortedWith(TaskOrder)
}

// The Material icons the phone uses (Icons.Filled.*), inlined so both clients look alike.
internal enum class Icon(val path: String) {
    FOLDER("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"),
    PROJECT("M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z"), // AccountTree
    SUBTASK("M19 15l-6 6-1.42-1.42L15.17 16H4V4h2v10h9.17l-3.59-3.59L13 9l6 6z"), // SubdirectoryArrowRight
    STAR("M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"),
    STAR_BORDER("M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z"),
    REPEAT("M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"),
    CHECK("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z")
}

internal fun FlowContent.icon(icon: Icon, classes: String, color: String? = null) = span(classes = "icon $classes") {
    color?.let { style = "color: $it" }
    unsafe { +"<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path fill=\"currentColor\" d=\"${icon.path}\"/></svg>" }
}

fun HTML.page(title: String, content: BODY.() -> Unit) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title(title)
        link(rel = "stylesheet", href = "/static/app.css")
        script(src = "/static/htmx/htmx.min.js") {}
        script(src = "/static/app.js") { defer = true }
    }
    body { content() }
}

// deleted: a task just deleted from the editor, offered back with an Undo toast.
fun HTML.listPage(data: ListData, deleted: Task? = null) = shellPage("Raspberry · ${data.mode.label}", data.mode, toast = deleted?.let { { deletedToastContents(it, data.mode) } }) {
    div { listContents(data) }
}

fun HTML.shellPage(title: String, mode: ListMode, toast: (DIV.() -> Unit)? = null, content: MAIN.() -> Unit) = page(title) {
    div(classes = "shell") {
        aside(classes = "sidebar") {
            h1 { +"Raspberry" }
            nav {
                ListMode.entries.forEach { m ->
                    a(href = "/${m.name.lowercase()}", classes = if (m == mode) "current" else null) { +m.label }
                }
            }
            // Same parser as the app's quick add; new tasks default to the Personal folder.
            form(classes = "quickadd") {
                attributes["hx-post"] = "/quickadd"
                attributes["hx-target"] = "#list"
                attributes["hx-swap"] = "outerHTML"
                hiddenInput(name = "mode") { value = mode.name }
                textInput(name = "text") { id = "quickadd"; placeholder = "Add a task… (n)"; attributes["autocomplete"] = "off" }
            }
            syntaxKey()
        }
        main { content() }
    }
    div { id = "toast"; toast?.invoke(this) }
}

private fun FlowContent.syntaxKey() = div(classes = "key") {
    listOf(
        "-d fri · due 3pm" to "due (and time)",
        "-s tomorrow · start mon 9am" to "start",
        "ends with ?" to "maybe",
        "n · g d / g a / g t · ?" to "keys"
    ).forEach { (syntax, meaning) -> div { span(classes = "mono") { +syntax }; +" $meaning" } }
}

// Filled into a div by the caller, so a fragment response can have this div as its root (htmx
// swaps #list for the returned #list). It re-renders itself every minute while the tab is visible
// and when the tab regains focus, so changes from the phone or Discord show up without reloading.
fun DIV.listContents(data: ListData) {
    classes = setOf("list")
    id = "list"
    attributes["hx-get"] = "/list/${data.mode.name.lowercase()}"
    attributes["hx-trigger"] = "every 60s[document.visibilityState==='visible'], visibilitychange[document.visibilityState==='visible'] from:document"
    attributes["hx-swap"] = "outerHTML"
    if (data.mode == ListMode.ALL) {
        tree(data, parentId = null, depth = 0)
    } else if (data.tasks.isEmpty()) {
        p(classes = "empty") { +"Nothing here" }
    } else {
        data.tasks.forEach { taskRow(data, it, depth = 0) }
    }
}

private fun FlowContent.tree(data: ListData, parentId: Long?, depth: Int) {
    data.openChildren(parentId).forEach { item ->
        if (item.type == TaskType.FOLDER) {
            div(classes = "folder") {
                style = "padding-left: ${depth * 18 + 10}px"
                icon(Icon.FOLDER, "folder-icon", data.ownColor(item))
                a(href = "/tasks/${item.id}?mode=ALL", classes = "edit") { +item.title }
            }
        } else {
            taskRow(data, item, depth)
        }
        tree(data, item.id, depth + 1)
    }
}

private fun FlowContent.taskRow(data: ListData, task: Task, depth: Int) {
    val mode = data.mode.name
    div(classes = "row") {
        if (task.isBackburner(data.now)) classes = classes + "dim"
        style = "padding-left: ${depth * 18}px; border-left-color: ${data.folderColor(task) ?: "var(--border)"}"
        if (task.type == TaskType.PROJECT) {
            span(classes = "project") { attributes["title"] = "Project: completes when its steps are done"; icon(Icon.PROJECT, "") }
        } else {
            val due = data.effectiveDue(task)
            button(classes = "check") {
                if (due != null && !task.isComplete && deadline(due) <= data.now) classes = classes + "overdue"
                attributes["hx-post"] = "/tasks/${task.id}/complete?mode=$mode"
                attributes["hx-target"] = "#list"
                attributes["hx-swap"] = "outerHTML"
                attributes["aria-label"] = "Complete"
            }
        }
        div(classes = "main") {
            div(classes = "title") {
                // In the All tree indentation already shows nesting; flat lists need the marker.
                if (data.mode != ListMode.ALL && data.isSubtask(task)) icon(Icon.SUBTASK, "sub")
                a(href = "/tasks/${task.id}?mode=$mode", classes = "edit") { +task.title }
                if (task.recurrenceType != null) span(classes = "badge") { attributes["title"] = "Recurring"; icon(Icon.REPEAT, "") }
            }
            val due = data.effectiveDue(task)
            val counts = data.subtaskCounts(task)
            val context = data.contextName(task)
            if (due != null || counts != null || context != null) {
                div(classes = "meta") {
                    due?.let { dueChip(it, data.now, overdue = deadline(it) <= data.now) }
                    counts?.let { (done, total) -> span { +"$done/$total" } }
                    context?.let { span { +"@$it" } }
                }
            }
        }
        details(classes = "more") {
            summary { attributes["aria-label"] = "Snooze"; +"⋯" }
            div(classes = "menu") {
                listOf("1 hour" to "hour", "Tomorrow" to "tomorrow", "1 week" to "week").forEach { (label, until) ->
                    button {
                        attributes["hx-post"] = "/tasks/${task.id}/snooze?until=$until&mode=$mode"
                        attributes["hx-target"] = "#list"
                        attributes["hx-swap"] = "outerHTML"
                        +"Snooze $label"
                    }
                }
            }
        }
        if (task.isMaybe) {
            span(classes = "maybe") { attributes["title"] = "Maybe"; +"?" }
        } else {
            button(classes = if (task.isStarred) "star on" else "star") {
                attributes["hx-post"] = "/tasks/${task.id}/star?mode=$mode"
                attributes["hx-target"] = "#list"
                attributes["hx-swap"] = "outerHTML"
                attributes["aria-label"] = "Star"
                icon(if (task.isStarred) Icon.STAR else Icon.STAR_BORDER, "")
            }
        }
    }
}

private fun FlowContent.dueChip(due: Long, now: Long, overdue: Boolean) {
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val day = Calendar.getInstance().apply { timeInMillis = due }
    val isToday = today.get(Calendar.YEAR) == day.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
    val time = if (hasTime(due)) " " + SimpleDateFormat("h:mm a", Locale.US).format(Date(due)) else ""
    span(classes = "due" + when { overdue -> " overdue"; isToday -> " today"; else -> "" }) {
        +((if (isToday) "Today" else SimpleDateFormat("MMM d", Locale.US).format(Date(due))) + time)
    }
}

fun DIV.deletedToastContents(task: Task, mode: ListMode) {
    classes = setOf("show")
    span { +"Deleted “${task.title}”" }
    button {
        attributes["hx-post"] = "/tasks/${task.id}/restore?mode=${mode.name}"
        attributes["hx-target"] = "#list"
        attributes["hx-swap"] = "outerHTML"
        +"Undo"
    }
}

// Shown out-of-band after completing, with a one-click undo.
fun DIV.undoToastContents(task: Task, mode: ListMode) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    classes = setOf("show")
    span { +"Completed “${task.title}”" }
    button {
        attributes["hx-post"] = "/tasks/${task.id}/uncomplete?mode=${mode.name}"
        attributes["hx-target"] = "#list"
        attributes["hx-swap"] = "outerHTML"
        +"Undo"
    }
}
