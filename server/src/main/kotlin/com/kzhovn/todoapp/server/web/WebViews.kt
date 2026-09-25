package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.deadline
import com.kzhovn.todoapp.data.hasTime
import com.kzhovn.todoapp.data.folderColorsArgb
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.data.sectionsByTopFolder
import com.kzhovn.todoapp.data.stalledProjects
import com.kzhovn.todoapp.data.subtaskParentTitle
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


// Everything a list needs, computed once per request from the live task set. `collapsed`: the All
// tree's folded nodes; `later`: stalled projects put off for now. Both are per browser.
class ListData(
    service: TaskService,
    val mode: ListMode,
    val collapsed: Set<Long> = emptySet(),
    later: Set<Long> = emptySet(),
    val folded: Set<Long> = emptySet(), // Active's folded folder sections; 0 = no folder
    val now: Long = service.now()
) {
    val all: List<Task> = service.tasks()
    val byId = all.associateBy { it.id }
    private val contextIds = service.contextIdsByTask()
    private val contextNames = service.contexts().associate { it.id to it.name }
    // The same colour families as the app (folderColorsArgb in :core).
    private val folderColors = folderColorsArgb(all).mapValues { "#%06X".format(it.value and 0xFFFFFF) }
    private val children = all.groupBy { it.parentId }
    val tasks: List<Task> = when (mode) {
        ListMode.DOING -> service.doing()
        ListMode.ACTIVE -> service.active()
        ListMode.ALL -> emptyList() // the tree is rendered from `children`
    }

    fun effectiveDue(task: Task) = resolveEffective(task, byId, contextIds).effectiveDueDate
    fun contextName(task: Task) = resolveEffective(task, byId, contextIds).effectiveContextIds.firstOrNull()?.let(contextNames::get)
    fun parentTitle(task: Task) = subtaskParentTitle(task, byId)
    fun folderColor(task: Task): String? = task.parentId?.let { parent -> walkParentChain(parent, byId) { folderColors[it] } }
    fun ownColor(folder: Task) = folderColors[folder.id]
    fun subtaskCounts(task: Task): Pair<Int, Int>? =
        children[task.id].orEmpty().filter { it.type != TaskType.FOLDER }.takeIf { it.isNotEmpty() }?.let { kids -> kids.count { it.isComplete } to kids.size }
    fun openChildren(parentId: Long?) = children[parentId].orEmpty().filter { !it.isComplete }.sortedWith(TaskOrder)

    // One open project whose steps are all done, to ask about (the phone asks the same, one at a time).
    val stalled: Task? = stalledProjects(all).firstOrNull { it.id !in later }
}

// The Material icons the phone uses (Icons.Filled.*), inlined so both clients look alike.
internal enum class Icon(val path: String) {
    FOLDER("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"),
    PROJECT("M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z"), // AccountTree
    STAR("M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"),
    STAR_BORDER("M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z"),
    REPEAT("M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"),
    CHECK("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"),
    CHEVRON_RIGHT("M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"),
    EXPAND_MORE("M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z")
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
fun HTML.listPage(data: ListData, deleted: Task? = null) = shellPage("Raspberry · ${data.mode.label}", data.mode.path, data.mode, toast = deleted?.let { { deletedToastContents(it, data.mode) } }) {
    // Bulk edit: app.js turns on selection, where clicking task rows picks them instead.
    div(classes = "list-tools") {
        attributes["data-mode"] = data.mode.name
        span(classes = "selection-count") {}
        button(classes = "select-toggle") { +"Select" }
        button(classes = "bulk-edit") { +"Edit selected" }
    }
    div { listContents(data) }
}

val ListMode.path get() = "/${name.lowercase()}"

// current: the nav path to highlight. listMode: the list on this page, which quick add re-renders;
// on pages without one, quick add just confirms with a toast.
fun HTML.shellPage(title: String, current: String, listMode: ListMode? = null, toast: (DIV.() -> Unit)? = null, content: MAIN.() -> Unit) = page(title) {
    div(classes = "shell") {
        aside(classes = "sidebar") {
            h1 { +"Raspberry" }
            // The phone's menu: the lists themselves are tabs above the content, as in the app.
            nav {
                listOf("/search" to "Search", "/review" to "Review", "/contexts" to "Contexts")
                    .forEach { (path, label) -> a(href = path, classes = if (path == current) "current" else null) { +label } }
            }
            // Same parser as the app's quick add; new tasks default to the Personal folder.
            form(classes = "quickadd") {
                attributes["hx-post"] = "/quickadd"
                if (listMode != null) {
                    attributes["hx-target"] = "#list"
                    attributes["hx-swap"] = "outerHTML"
                    hiddenInput(name = "mode") { value = listMode.name }
                } else {
                    attributes["hx-swap"] = "none"
                }
                textInput(name = "text") { id = "quickadd"; placeholder = "Add a task… (n)"; attributes["autocomplete"] = "off" }
            }
            div(classes = "new-links") {
                +"New "
                listOf("TASK" to "task", "PROJECT" to "project", "FOLDER" to "folder").forEach { (type, label) ->
                    a(href = "/tasks/new?type=$type&mode=${(listMode ?: ListMode.DOING).name}") { +label }
                }
            }
            syntaxKey()
        }
        main {
            nav(classes = "tabs") {
                ListMode.entries.forEach { m -> a(href = m.path, classes = if (m.path == current) "current" else null) { +m.label } }
            }
            content()
        }
    }
    div { id = "toast"; toast?.invoke(this) }
}

private fun FlowContent.syntaxKey() = div(classes = "key") {
    listOf(
        "-d fri · due 3pm" to "due (and time)",
        "-s tomorrow · start mon 9am" to "start",
        "today, tomorrow, mon–sun, next fri, 2026-10-01" to "dates",
        "5pm, 9:30am, 14:00" to "times",
        "ends with ?" to "maybe",
        "n · g d / g a / g t · ?" to "keys"
    ).forEach { (syntax, meaning) -> div { span(classes = "mono") { +syntax }; +" $meaning" } }
}

// Filled into a div by the caller, so a fragment response can have this div as its root (htmx
// swaps #list for the returned #list). It re-renders itself every minute while the tab is visible
// and when the tab regains focus, so changes from the phone or Discord show up without reloading.
fun DIV.listContents(data: ListData) {
    classes = if (data.mode == ListMode.ALL) setOf("list", "outline") else setOf("list")
    id = "list"
    attributes["hx-get"] = "/list/${data.mode.name.lowercase()}"
    attributes["hx-trigger"] = "every 60s[document.visibilityState==='visible'], visibilitychange[document.visibilityState==='visible'] from:document"
    attributes["hx-swap"] = "outerHTML"
    data.stalled?.let { stalledPrompt(it, data.mode) }
    if (data.mode == ListMode.ALL) {
        tree(data, parentId = null, depth = 0)
    } else if (data.tasks.isEmpty()) {
        p(classes = "empty") { +"Nothing here" }
    } else if (data.mode == ListMode.ACTIVE) {
        // Foldable sections by top-level folder, like the phone's Active.
        sectionsByTopFolder(data.tasks, data.byId).forEach { (folder, tasks) ->
            val sectionId = folder?.id ?: 0L
            val folded = sectionId in data.folded
            div(classes = "section") {
                button(classes = "section-toggle") {
                    attributes["hx-get"] = "/list/active?fold=$sectionId"
                    attributes["hx-target"] = "#list"
                    attributes["hx-swap"] = "outerHTML"
                    attributes["aria-expanded"] = (!folded).toString()
                    icon(if (folded) Icon.CHEVRON_RIGHT else Icon.EXPAND_MORE, "chevron")
                    if (folder != null) icon(Icon.FOLDER, "folder-icon", data.ownColor(folder))
                    span(classes = "section-title") { +(folder?.title ?: "No folder") }
                    span(classes = "section-count") { +tasks.size.toString() }
                }
            }
            if (!folded) tasks.forEach { taskRow(data, it, depth = 0) }
        }
    } else {
        data.tasks.forEach { taskRow(data, it, depth = 0) }
    }
}

// The All tree, as flat rows in outline order (app.js's keys and drag work off data-*).
private fun FlowContent.tree(data: ListData, parentId: Long?, depth: Int) {
    data.openChildren(parentId).forEach { item ->
        val hasChildren = data.openChildren(item.id).isNotEmpty()
        val collapsed = hasChildren && item.id in data.collapsed
        val node: DIV.() -> Unit = {
            classes = classes + "node"
            attributes["tabindex"] = "0"
            attributes["draggable"] = "true"
            attributes["data-id"] = item.id.toString()
            attributes["data-parent"] = item.parentId?.toString().orEmpty()
            attributes["data-depth"] = depth.toString()
            attributes["data-kind"] = item.type.name.lowercase()
            if (hasChildren) attributes["data-collapsed"] = collapsed.toString()
            chevron(item.id, hasChildren, collapsed)
        }
        if (item.type == TaskType.FOLDER) {
            div(classes = "folder") {
                style = "padding-left: ${depth * 18}px"
                node()
                icon(Icon.FOLDER, "folder-icon", data.ownColor(item))
                a(href = "/tasks/${item.id}?mode=ALL", classes = "edit") { +item.title }
            }
        } else {
            taskRow(data, item, depth, node)
        }
        if (!collapsed) tree(data, item.id, depth + 1)
    }
}

private fun FlowContent.chevron(id: Long, hasChildren: Boolean, collapsed: Boolean) {
    if (!hasChildren) return span(classes = "chevron") {}
    button(classes = "chevron") {
        attributes["hx-get"] = "/list/all?toggle=$id"
        attributes["hx-target"] = "#list"
        attributes["hx-swap"] = "outerHTML"
        attributes["tabindex"] = "-1"
        attributes["aria-label"] = if (collapsed) "Expand" else "Collapse"
        icon(if (collapsed) Icon.CHEVRON_RIGHT else Icon.EXPAND_MORE, "")
    }
}

private fun FlowContent.taskRow(data: ListData, task: Task, depth: Int, outlineNode: (DIV.() -> Unit)? = null) {
    val mode = data.mode.name
    div(classes = "row") {
        attributes["data-task-id"] = task.id.toString()
        attributes["data-type"] = task.type.name
        if (task.isBackburner(data.now)) classes = classes + "dim"
        style = "padding-left: ${depth * 18}px; border-left-color: ${data.folderColor(task) ?: "var(--border)"}"
        outlineNode?.invoke(this)
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
                // In the All tree indentation already shows nesting; flat lists say "Parent: subtask".
                // The parent part opens the parent's editor, as the title opens the subtask's.
                if (data.mode != ListMode.ALL) data.parentTitle(task)?.let { a(href = "/tasks/${task.parentId}?mode=$mode", classes = "parent") { +"$it: " } }
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
                listOf("Snooze 1 hour" to "hour", "Snooze to tomorrow" to "tomorrow", "Snooze 1 week" to "week").forEach { (label, until) ->
                    button {
                        attributes["hx-post"] = "/tasks/${task.id}/snooze?until=$until&mode=$mode"
                        attributes["hx-target"] = "#list"
                        attributes["hx-swap"] = "outerHTML"
                        +label
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

internal fun FlowContent.dueChip(due: Long, now: Long, overdue: Boolean) {
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

// The phone's "all subtasks done" prompt: complete the project, add its next step, or not now.
private fun FlowContent.stalledPrompt(project: Task, mode: ListMode) = div(classes = "stalled") {
    val m = mode.name
    fun htmx(tag: kotlinx.html.HTMLTag, verb: String, url: String) {
        tag.attributes["hx-$verb"] = url
        tag.attributes["hx-target"] = "#list"
        tag.attributes["hx-swap"] = "outerHTML"
    }
    span { +"“${project.title}”: all subtasks done. Is the project complete?" }
    button(classes = "primary") { htmx(this, "post", "/tasks/${project.id}/complete?mode=$m"); +"Complete project" }
    form(classes = "inline") {
        htmx(this, "post", "/tasks/${project.id}/next?mode=$m")
        textInput(name = "text") { placeholder = "Add next…"; attributes["autocomplete"] = "off" }
    }
    button { htmx(this, "get", "/list/${mode.name.lowercase()}?later=${project.id}"); +"Later" }
}

// Asks what to do with a task's open subtasks before completing it. `url` is the completing
// request; the choice is added to it. `include`: a form whose fields must go along (search's).
fun DIV.askSubtasksToast(task: Task, open: Int, url: String, target: String, include: String? = null) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    classes = setOf("show", "question")
    span { +"Complete “${task.title}”? It has $open active subtask${if (open == 1) "" else "s"}." }
    val sep = if ('?' in url) '&' else '?'
    listOf("complete" to "Complete subtasks too", "promote" to "Move subtasks out").forEach { (choice, label) ->
        button {
            attributes["hx-post"] = "$url${sep}subtasks=$choice"
            attributes["hx-target"] = target
            attributes["hx-swap"] = "outerHTML"
            include?.let { attributes["hx-include"] = it }
            +label
        }
    }
    button(classes = "dismiss") { +"Cancel" }
}

// Confirms a quick add made from a page without a list to show it in.
fun DIV.addedToastContents(task: Task) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    classes = setOf("show")
    span { +"Added “${task.title}”" }
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
