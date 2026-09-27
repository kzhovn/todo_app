package com.kzhovn.todoapp.server.web

import kotlinx.html.HTMLTag
import com.kzhovn.todoapp.data.subtaskCounts
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.dueText
import com.kzhovn.todoapp.data.folderColorsArgb
import com.kzhovn.todoapp.data.formatDuration
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
import kotlinx.html.b
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

enum class ListMode(val label: String) { DOING(Labels.DOING), ACTIVE(Labels.ACTIVE), ALL(Labels.ALL) }


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
    private val folderColors = folderColorsHex(all)
    private val counts = subtaskCounts(all)
    private val children = all.groupBy { it.parentId }
    val tasks: List<Task> = when (mode) {
        ListMode.DOING -> service.doing()
        ListMode.ACTIVE -> service.active()
        ListMode.ALL -> emptyList() // the tree is rendered from `children`
    }

    fun effectiveDue(task: Task) = resolveEffective(task, byId, contextIds).effectiveDueDate
    fun parentTitle(task: Task) = subtaskParentTitle(task, byId)
    fun folderColor(task: Task): String? = task.parentId?.let { parent -> walkParentChain(parent, byId) { folderColors[it] } }
    fun ownColor(folder: Task) = folderColors[folder.id]
    fun subtaskCounts(task: Task): Pair<Int, Int>? = counts[task.id]
    fun openChildren(parentId: Long?) = children[parentId].orEmpty().filter { !it.isComplete }.sortedWith(TaskOrder)

    // One open project whose steps are all done, to ask about (the phone asks the same, one at a time).
    val stalled: Task? = stalledProjects(all).firstOrNull { it.id !in later }
}

// The same colour families as the app (folderColorsArgb in :core), as CSS colours.
internal fun folderColorsHex(tasks: Collection<Task>): Map<Long, String> = folderColorsArgb(tasks).mapValues { "#%06X".format(it.value and 0xFFFFFF) }

// An htmx request whose response replaces `target` (the list, by default).
internal fun HTMLTag.hx(verb: String, url: String, target: String = "#list") {
    attributes["hx-$verb"] = url
    attributes["hx-target"] = target
    attributes["hx-swap"] = "outerHTML"
}

// The Material icons the phone uses (Icons.Filled.*), inlined so both clients look alike.
internal enum class Icon(val path: String) {
    FOLDER("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"),
    PROJECT("M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z"), // AccountTree
    STAR("M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"),
    REPEAT("M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"),
    CHECK("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"),
    PLAY("M8 5v14l11-7z"),
    CALENDAR("M19 4h-1V2h-2v2H8V2H6v2H5c-1.11 0-1.99.9-1.99 2L3 20c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 16H5V10h14v10zm0-12H5V6h14v2z"), // Event
    FLAG("M14.4 6L14 4H5v17h2v-7h5.6l.4 2h7V6z"),
    BELL("M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"), // Notifications
    TIMER("M15 1H9v2h6V1zm-4 13h2V8h-2v6zm8.03-6.61l1.42-1.42c-.43-.51-.9-.99-1.41-1.41l-1.42 1.42C16.07 4.74 14.12 4 12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9 9-4.03 9-9c0-2.12-.74-4.07-1.97-5.61zM12 20c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"),
    SNOWFLAKE("M22 11h-4.17l3.24-3.24-1.41-1.42L15 11h-2V9l4.66-4.66-1.42-1.41L13 6.17V2h-2v4.17L7.76 2.93 6.34 4.34 11 9v2H9L4.34 6.34 2.93 7.76 6.17 11H2v2h4.17l-3.24 3.24 1.41 1.42L9 13h2v2l-4.66 4.66 1.42 1.41L11 17.83V22h2v-4.17l3.24 3.24 1.42-1.41L13 15v-2h2l4.66 4.66 1.41-1.42L17.83 13H22z"), // AcUnit
    PAUSE("M6 19h4V5H6v14zm8-14v14h4V5h-4z"),
    CHEVRON_RIGHT("M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"),
    EXPAND_MORE("M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z"),
    SCHEDULE("M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z"),
    BEDTIME("M12.34 2.02C6.59 1.82 2 6.42 2 12c0 5.52 4.48 10 10 10 3.71 0 6.93-2.02 8.66-5.02-7.51-.25-12.09-8.43-8.32-14.96z"),
    LIST_NUMBERED("M2 17h2v.5H3v1h1v.5H2v1h3v-4H2v1zm1-9h1V4H2v1h1v3zm-1 3h1.8L2 13.1v.9h3v-1H3.2L5 10.9V10H2v1zm5-6v2h14V5H7zm0 14h14v-2H7v2zm0-6h14v-2H7v2z"), // FormatListNumbered
    BOLT("M11 21h-1l1-7H7.5c-.58 0-.57-.32-.38-.66.19-.34.05-.08.07-.12C8.48 10.94 10.42 7.54 13 3h1l-1 7h3.5c.49 0 .56.33.47.51l-.07.15C12.96 17.55 11 21 11 21z"),
    DATE_RANGE("M9 11H7v2h2v-2zm4 0h-2v2h2v-2zm4 0h-2v2h2v-2zm2-7h-1V2h-2v2H8V2H6v2H5c-1.11 0-1.99.9-1.99 2L3 20c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 16H5V9h14v11z")
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
        // The phone app's raspberry icon, cropped as the launcher shows it.
        link(rel = "icon", href = "/static/icon-32.png", type = "image/png") { attributes["sizes"] = "32x32" }
        link(rel = "icon", href = "/static/icon-192.png", type = "image/png") { attributes["sizes"] = "192x192" }
        link(rel = "apple-touch-icon", href = "/static/apple-touch-icon.png")
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
    // The running timed task's countdown bar (app.js fills it in).
    div { id = "timer"; attributes["hidden"] = "" }
}

private fun FlowContent.syntaxKey() = div(classes = "key") {
    (Labels.QUICK_ADD_SYNTAX + ("n · g d / g a / g t · ?" to "keys")).forEach { (syntax, meaning) -> div { span(classes = "mono") { +syntax }; +" $meaning" } }
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
                    hx("get", "/list/active?fold=$sectionId")
                    attributes["aria-expanded"] = (!folded).toString()
                    // A divider: "Work · 5" centered between two rules in the folder's colour.
                    folder?.let { data.ownColor(it) }?.let { style = "--rule: $it" }
                    icon(if (folded) Icon.CHEVRON_RIGHT else Icon.EXPAND_MORE, "chevron")
                    span(classes = "rule")
                    span(classes = "section-title") { b { +(folder?.title ?: Labels.NO_FOLDER) }; +" · ${tasks.size}" }
                    span(classes = "rule")
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
        // Checklists start folded; the cookie holds the ones flipped, so for a checklist it means unfolded.
        val collapsed = hasChildren && ((item.id in data.collapsed) != (item.type == TaskType.CHECKLIST))
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
        hx("get", "/list/all?toggle=$id")
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
        val due = data.effectiveDue(task)?.takeUnless { task.isComplete }
        val status = due?.let { dueStatus(it, data.now) }
        if (task.type == TaskType.PROJECT) {
            span(classes = "project") { attributes["title"] = "Project: completes when its steps are done"; icon(Icon.PROJECT, "") }
        } else if (task.type == TaskType.CHECKLIST) {
            // Ticked item by item, inside it; the row shows how far along it is and opens it.
            val (done, total) = data.subtaskCounts(task) ?: (0 to 0)
            a(href = "/tasks/${task.id}?mode=$mode", classes = "count") { attributes["title"] = Labels.CHECKLIST; +"$done/$total" }
        } else {
            button(classes = "check") {
                // Due today: an orange ring; overdue: rust. Each with a pale fill of its own colour.
                when (status) { DueStatus.OVERDUE -> classes = classes + "overdue"; DueStatus.TODAY -> classes = classes + "today"; else -> {} }
                hx("post", "/tasks/${task.id}/complete?mode=$mode")
                attributes["aria-label"] = "Complete"
            }
        }
        div(classes = "main") {
            div(classes = "title") {
                // In the All tree indentation already shows nesting; flat lists say "Parent: subtask".
                // The parent part opens the parent's editor, as the title opens the subtask's.
                if (data.mode != ListMode.ALL) data.parentTitle(task)?.let { a(href = "/tasks/${task.parentId}?mode=$mode", classes = "parent") { +"$it: " } }
                a(href = "/tasks/${task.id}?mode=$mode", classes = "edit") { +task.title }
                // On the title's line, wrapping along with it.
                due?.let { dueTail(it, status!!, data.now) }
                if (task.type != TaskType.CHECKLIST) data.subtaskCounts(task)?.let { (done, total) -> span(classes = "tail") { +" · $done/$total" } }
                if (task.recurrenceType != null) span(classes = "badge") { attributes["title"] = "Recurring"; icon(Icon.REPEAT, "") }
            }
        }
        // A timed task's play button, "▶ 1h"; app.js runs the countdown (one at a time, per browser)
        // and shows the time left on it.
        task.durationMinutes?.let { minutes ->
            button(classes = "play") {
                attributes["data-task-id"] = task.id.toString()
                attributes["data-minutes"] = minutes.toString()
                attributes["data-title"] = task.title
                attributes["aria-label"] = "Start timer"
                icon(Icon.PLAY, "play-icon")
                icon(Icon.PAUSE, "pause-icon")
                span(classes = "play-time") { +formatDuration(minutes) }
            }
        }
        details(classes = "more") {
            summary { attributes["aria-label"] = "Snooze"; +"⋯" }
            // "Snooze" over three equal tiles, like the app's row menu.
            div(classes = "menu") {
                div(classes = "menu-label") { +Labels.SNOOZE }
                div(classes = "tiles") {
                    listOf(Triple(Labels.SNOOZE_HOUR, "hour", Icon.SCHEDULE), Triple(Labels.SNOOZE_TOMORROW, "tomorrow", Icon.BEDTIME), Triple(Labels.SNOOZE_WEEK, "week", Icon.DATE_RANGE))
                        .forEach { (label, until, tileIcon) ->
                            button(classes = "tile") {
                                hx("post", "/tasks/${task.id}/snooze?until=$until&mode=$mode")
                                icon(tileIcon, "")
                                span { +label }
                            }
                        }
                }
            }
        }
        if (task.isMaybe) {
            span(classes = "maybe") { attributes["title"] = "Maybe"; +"?" }
        } else {
            button(classes = if (task.isStarred) "star on" else "star") {
                hx("post", "/tasks/${task.id}/star?mode=$mode")
                attributes["aria-label"] = "Star"
                // Starred: a gold outline over a pale gold fill (CSS), like the due checkboxes.
                icon(Icon.STAR, "")
            }
        }
    }
}

// "· due today" after a title, in the due colour (see DueStatus); the same words as the app's rows.
internal fun FlowContent.dueTail(due: Long, status: DueStatus, now: Long) = span(classes = "tail due " + status.name.lowercase()) {
    +" · ${dueText(due, now)}"
}

fun DIV.deletedToastContents(task: Task, mode: ListMode) {
    classes = setOf("show")
    span { +"Deleted “${task.title}”" }
    button {
        hx("post", "/tasks/${task.id}/restore?mode=${mode.name}")
        +"Undo"
    }
}

// The phone's "all subtasks done" prompt: complete the project, add its next step, or not now.
private fun FlowContent.stalledPrompt(project: Task, mode: ListMode) = div(classes = "stalled") {
    val m = mode.name
    span { +"${Labels.allSubtasksDone(project.title)}. ${Labels.IS_PROJECT_COMPLETE}" }
    button(classes = "primary") { hx("post", "/tasks/${project.id}/complete?mode=$m"); +Labels.COMPLETE_PROJECT }
    form(classes = "inline") {
        hx("post", "/tasks/${project.id}/next?mode=$m")
        textInput(name = "text") { placeholder = "${Labels.ADD_NEXT}…"; attributes["autocomplete"] = "off" }
    }
    button { hx("get", "/list/${mode.name.lowercase()}?later=${project.id}"); +Labels.LATER }
}

// Asks what to do with a task's open subtasks before completing it. `url` is the completing
// request; the choice is added to it. `include`: a form whose fields must go along (search's).
fun DIV.askSubtasksToast(task: Task, open: Int, url: String, target: String, include: String? = null) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    classes = setOf("show", "question")
    span { +"Complete “${task.title}”? ${Labels.activeSubtasks(open)}" }
    val sep = if ('?' in url) '&' else '?'
    listOf("complete" to Labels.COMPLETE_SUBTASKS_TOO, "promote" to Labels.MOVE_SUBTASKS_OUT).forEach { (choice, label) ->
        button {
            hx("post", "$url${sep}subtasks=$choice", target)
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
        hx("post", "/tasks/${task.id}/uncomplete?mode=${mode.name}")
        +"Undo"
    }
}
