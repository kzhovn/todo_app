package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.widget.TodoWidgetPresenter
import com.kzhovn.todoapp.data.isUnder
import com.kzhovn.todoapp.data.isChecklistItem
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.TaskOrder
import kotlinx.html.h2
import kotlinx.html.img
import kotlinx.html.A
import kotlinx.html.ASIDE
import kotlinx.html.ButtonType
import com.kzhovn.todoapp.data.OutlinerNode
import com.kzhovn.todoapp.data.buildOutlinerTree
import kotlinx.html.HTMLTag
import com.kzhovn.todoapp.data.subtaskCounts
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
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
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.body
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
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
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.summary
import kotlinx.html.textInput
import kotlinx.html.title
import kotlinx.html.unsafe

enum class ListMode(val label: String) { DOING(Labels.DOING), ACTIVE(Labels.ACTIVE), ALL(Labels.ALL) }


// Everything a list needs, computed once per request from the live task set. `collapsed`: the All
// tree's folded nodes; `later`: stalled projects put off for now. Both are per browser. `folder`: the
// All tree shows just this folder's contents (a folder in the sidebar). `selected`: the task open in
// the detail panel, highlighted in the list.
class ListData(
    service: TaskService,
    val mode: ListMode,
    val collapsed: Set<Long> = emptySet(),
    later: Set<Long> = emptySet(),
    val folded: Set<Long> = emptySet(), // Active's folded folder sections; 0 = no folder
    val now: Long = service.now(),
    val folder: Long? = null,
    val selected: Long? = null
) {
    // This list in a URL's query ("mode=ALL&folder=12" goes in as `?mode=$q`), so an action on a row
    // re-renders the same view; `path` is the page itself.
    val q: String get() = mode.name + (folder?.let { "&folder=$it" } ?: "")
    val path: String get() = mode.path + (folder?.let { "?folder=$it" } ?: "")
    val title: String get() = folder?.let { byId[it]?.title } ?: mode.label

    val all: List<Task> = service.tasks()
    val byId = all.associateBy { it.id }
    private val contextIds = service.contextIdsByTask()
    private val folderColors = folderColorsHex(all)
    private val counts = subtaskCounts(all)
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
    // As the phone's: completed tasks drop out (their open subtasks move up); completed checklists
    // and projects stay, struck through.
    val tree: List<OutlinerNode> by lazy { buildOutlinerTree(all, hideCompleted = true) }

    // The tree's top level for this view: a folder's contents, or everything.
    val roots: List<OutlinerNode> by lazy {
        fun find(nodes: List<OutlinerNode>): OutlinerNode? = nodes.firstNotNullOfOrNull { if (it.task.id == folder) it else find(it.children) }
        if (folder == null) tree else find(tree)?.children.orEmpty()
    }

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

// The Material icons the phone uses (Icons.Filled.*), inlined so both clients look alike. The top bar's
// copies are made from these by tools/tray_icons.py.
internal enum class Icon(val path: String) {
    FOLDER("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"),
    PROJECT("M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z"), // AccountTree
    STAR("M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"),
    REPEAT("M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"),
    NOTES("M3 18h12v-2H3v2zM3 6v2h18V6H3zm0 7h18v-2H3v2z"), // Notes
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
    QUESTION_MARK("M11.07 12.85c.77-1.39 2.25-2.21 3.11-3.44.91-1.29.4-3.7-2.18-3.7-1.69 0-2.52 1.28-2.87 2.34L6.54 6.96C7.25 4.83 9.18 3 11.99 3c2.35 0 3.96 1.07 4.78 2.41.7 1.15 1.11 3.3.03 4.9-1.2 1.77-2.35 2.31-2.97 3.45-.25.46-.35.76-.35 2.24h-2.89c-.01-.78-.13-2.05.48-3.15zM14 20c0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2 2 .9 2 2z"),
    BOLT("M11 21h-1l1-7H7.5c-.58 0-.57-.32-.38-.66.19-.34.05-.08.07-.12C8.48 10.94 10.42 7.54 13 3h1l-1 7h3.5c.49 0 .56.33.47.51l-.07.15C12.96 17.55 11 21 11 21z"),
    CENTER_FOCUS("M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm-7 7H3v4c0 1.1.9 2 2 2h4v-2H5v-4zM5 5h4V3H5c-1.1 0-2 .9-2 2v4h2V5zm14-2h-4v2h4v4h2V5c0-1.1-.9-2-2-2zm0 16h-4v2h4c1.1 0 2-.9 2-2v-4h-2v4z"), // CenterFocusStrong
    PUSH_PIN("M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z"),
    ADD("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"),
    SEARCH("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"),
    REFRESH("M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"),
    CHECKLIST("M22 7h-9v2h9V7zm0 8h-9v2h9v-2zM5.54 11L2 7.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 11zm0 8L2 15.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 19z"),
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
fun HTML.listPage(service: TaskService, data: ListData, deleted: Task? = null) =
    shellPage(service, "Raspberry · ${data.title}", data.path, data, toast = deleted?.let { { deletedToastContents(it, data.q) } }) { listColumn(data) }

// The list column: a header (the list's name, bulk selection, New) over the list itself.
fun FlowContent.listColumn(data: ListData) {
    div(classes = "list-head") {
        h2 { +data.title }
        if (data.mode != ListMode.ALL) span(classes = "list-count") { id = "list-count"; +data.tasks.size.toString() }
        // Bulk edit: app.js turns on selection, where clicking task rows picks them instead.
        div(classes = "list-tools") {
            attributes["data-mode"] = data.mode.name
            span(classes = "selection-count") {}
            button(classes = "select-toggle") { +"Select" }
            button(classes = "bulk-edit") { +"Edit selected" }
        }
        details(classes = "pp new-menu") {
            summary(classes = "pill") { +"New ▾" }
            div(classes = "pop") {
                listOf(TaskType.TASK, TaskType.PROJECT, TaskType.CHECKLIST, TaskType.FOLDER).forEach { type ->
                    a(href = "/tasks/new?type=${type.name}&mode=${data.q}") { +Labels.TYPES.first { it.first == type }.second }
                }
            }
        }
    }
    div { listContents(data) }
}

val ListMode.path get() = "/${name.lowercase()}"

// The sidebar's counts, and its folders (top level, in tree order) with their open tasks.
private class SideNav(service: TaskService) {
    val all = service.tasks()
    private val byId = all.associateBy { it.id }
    val counts = mapOf(ListMode.DOING to service.doing().size, ListMode.ACTIVE to service.active().size, ListMode.ALL to TodoWidgetPresenter.allOpen(all, byId).size)
    val colors = folderColorsHex(all)
    val folders = all.filter { it.type == TaskType.FOLDER && it.parentId == null }.sortedWith(TaskOrder).map { folder ->
        folder to all.count { it.type.isDoable && !it.isComplete && !isChecklistItem(it, byId) && isUnder(it, folder.id, byId) }
    }
}

// After a list action, the counts elsewhere on the page (the list's own, the sidebar's), swapped in
// beside the list by htmx.
fun countsOob(service: TaskService, data: ListData): String {
    val nav = SideNav(service)
    fun count(id: String, classes: String, n: Int) = createHTML().span(classes = classes) { this.id = id; attributes["hx-swap-oob"] = "true"; +n.toString() }
    return buildString {
        if (data.mode != ListMode.ALL) append(count("list-count", "list-count", data.tasks.size))
        ListMode.entries.forEach { append(count("n-${it.name.lowercase()}", "n", nav.counts.getValue(it))) }
        nav.folders.forEach { (folder, open) -> append(count("n-${folder.id}", "n", open)) }
    }
}

// Every page: the sidebar (quick add, the lists, folders, the other screens), then the page's own
// column, then the detail panel (the task editor; empty, and hidden, elsewhere). `current`: the path
// to highlight. `list`: the list on this page, which quick add re-renders; on pages without one,
// quick add just confirms with a toast.
fun HTML.shellPage(
    service: TaskService,
    title: String,
    current: String,
    list: ListData? = null,
    toast: (DIV.() -> Unit)? = null,
    detail: (ASIDE.() -> Unit)? = null,
    content: DIV.() -> Unit
) {
    service.focusSession()?.let { return focusPage(service, it) }
    shell(service, title, current, list, toast, detail, content)
}

private fun HTML.shell(
    service: TaskService,
    title: String,
    current: String,
    list: ListData?,
    toast: (DIV.() -> Unit)?,
    detail: (ASIDE.() -> Unit)?,
    content: DIV.() -> Unit
) = page(title) {
    val nav = SideNav(service)
    div(classes = "shell") {
        aside(classes = "sidebar") {
            a(href = "/doing", classes = "brand") { img(src = "/static/icon-32.png", alt = "") ; +"Raspberry" }
            // Same parser as the app's quick add; new tasks go in Personal, or in the folder on screen.
            form(classes = "quickadd") {
                attributes["hx-post"] = "/quickadd" + (list?.folder?.let { "?folder=$it" } ?: "")
                if (list != null) {
                    attributes["hx-target"] = "#list"
                    attributes["hx-swap"] = "outerHTML"
                    hiddenInput(name = "mode") { value = list.mode.name }
                } else {
                    attributes["hx-swap"] = "none"
                }
                textInput(name = "text") { id = "quickadd"; placeholder = "Add a task… (n)"; attributes["autocomplete"] = "off" }
            }
            // The current task (pinned, or its timer running), shared with every device.
            service.pinned()?.let { now ->
                div(classes = "now") {
                    span(classes = "now-label") { +"Now" }
                    a(href = "/tasks/${now.id}?mode=DOING", classes = "now-title") { openInPanel(); +now.title }
                    span(classes = "now-timer") {}
                    button(classes = "now-focus") { hx("post", "/focus/start?task=${now.id}", "this"); attributes["title"] = "Focus"; icon(Icon.CENTER_FOCUS, "") }
                }
            }
            nav(classes = "nav") {
                ListMode.entries.forEach { m ->
                    a(href = m.path, classes = if (m.path == current) "current" else null) { +m.label; span(classes = "n") { id = "n-${m.name.lowercase()}"; +nav.counts.getValue(m).toString() } }
                }
            }
            if (nav.folders.isNotEmpty()) {
                div(classes = "nav-label") { +"Folders" }
                nav(classes = "nav folders") {
                    nav.folders.forEach { (folder, open) ->
                        val path = "/all?folder=${folder.id}"
                        a(href = path, classes = if (path == current) "current" else null) {
                            span(classes = "fdot") { nav.colors[folder.id]?.let { style = "background: $it" } }
                            +folder.title
                            span(classes = "n") { id = "n-${folder.id}"; +open.toString() }
                        }
                    }
                }
            }
            div(classes = "nav-label") { +"More" }
            nav(classes = "nav") {
                listOf("/focus" to "Focus", "/search" to "Search", "/review" to "Review", "/contexts" to "Contexts", "/settings" to "Settings")
                    .forEach { (path, label) -> a(href = path, classes = if (path == current) "current" else null) { +label } }
            }
            details(classes = "shortcuts") {
                summary { +"Shortcuts and syntax" }
                syntaxKey()
            }
        }
        div(classes = "workspace") {
            div(classes = "page") { content() }
            aside(classes = "detail") { id = "detail"; detail?.invoke(this) }
        }
    }
    div { id = "toast"; toast?.invoke(this) }
    // The running timed task's countdown bar (app.js fills it in).
    div { id = "timer"; attributes["hidden"] = "" }
}

private val KEYS = listOf(
    "n" to "jump to quick add",
    "g, then d / a / t" to "go to Doing / Active / All",
    "Esc" to "leave a text box, close the task panel, or leave focus",
    "?" to "every shortcut, including the All tree's",
)

private fun FlowContent.syntaxKey() = div(classes = "key") {
    div(classes = "key-head") { +"Quick add" }
    Labels.QUICK_ADD_SYNTAX.forEach { (syntax, meaning) -> div { span(classes = "mono") { +syntax }; +" $meaning" } }
    div(classes = "key-head") { +"Keys (not while typing)" }
    KEYS.forEach { (keys, meaning) -> div { span(classes = "mono") { +keys }; +" $meaning" } }
}

// Filled into a div by the caller, so a fragment response can have this div as its root (htmx
// swaps #list for the returned #list). It re-renders itself every minute while the tab is visible
// and when the tab regains focus, so changes from the phone or Discord show up without reloading.
fun DIV.listContents(data: ListData) {
    classes = if (data.mode == ListMode.ALL) setOf("list", "outline") else setOf("list")
    id = "list"
    attributes["hx-get"] = "/list/${data.mode.name.lowercase()}" + (data.folder?.let { "?folder=$it" } ?: "")
    attributes["hx-trigger"] = "every 60s, visibilitychange from:document, refresh from:body"
    attributes["hx-swap"] = "outerHTML"
    data.stalled?.let { stalledPrompt(it, data.mode, data.q) }
    if (data.mode == ListMode.ALL) {
        if (data.roots.isEmpty()) p(classes = "empty") { +"Nothing here" }
        tree(data, data.roots, parentId = null, depth = 0)
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
// parentId: the row this one sits under on screen (for ← in app.js), not necessarily its own parent.
private fun FlowContent.tree(data: ListData, nodes: List<OutlinerNode>, parentId: Long?, depth: Int) {
    nodes.forEach { node ->
        val item = node.task
        val hasChildren = node.children.isNotEmpty()
        // Checklists start folded; the cookie holds the ones flipped, so for a checklist it means unfolded.
        val collapsed = hasChildren && ((item.id in data.collapsed) != (item.type == TaskType.CHECKLIST))
        val outline: DIV.() -> Unit = {
            classes = classes + "node"
            attributes["tabindex"] = "0"
            attributes["draggable"] = "true"
            attributes["data-id"] = item.id.toString()
            attributes["data-parent"] = parentId?.toString().orEmpty()
            attributes["data-depth"] = depth.toString()
            attributes["data-kind"] = item.type.name.lowercase()
            if (hasChildren) attributes["data-collapsed"] = collapsed.toString()
            chevron(item.id, hasChildren, collapsed, data.folder)
        }
        if (item.type == TaskType.FOLDER) {
            div(classes = if (item.id == data.selected) "folder current" else "folder") {
                style = "padding-left: ${depth * 18}px"
                outline()
                icon(Icon.FOLDER, "folder-icon", data.ownColor(item))
                a(href = "/tasks/${item.id}?mode=${data.q}", classes = "edit") { openInPanel(); +item.title }
            }
        } else {
            taskRow(data, item, depth, outline)
        }
        if (!collapsed) tree(data, node.children, item.id, depth + 1)
    }
}

private fun FlowContent.chevron(id: Long, hasChildren: Boolean, collapsed: Boolean, folder: Long?) {
    if (!hasChildren) return span(classes = "chevron") {}
    button(classes = "chevron") {
        hx("get", "/list/all?toggle=$id" + (folder?.let { "&folder=$it" } ?: ""))
        attributes["tabindex"] = "-1"
        attributes["aria-label"] = if (collapsed) "Expand" else "Collapse"
        icon(if (collapsed) Icon.CHEVRON_RIGHT else Icon.EXPAND_MORE, "")
    }
}

private fun FlowContent.taskRow(data: ListData, task: Task, depth: Int, outlineNode: (DIV.() -> Unit)? = null) {
    val mode = data.q
    div(classes = "row") {
        attributes["data-task-id"] = task.id.toString()
        if (task.id == data.selected) classes = classes + "current"
        attributes["data-type"] = task.type.name
        if (task.isBackburner(data.now)) classes = classes + "dim"
        if (task.isComplete) classes = classes + "done"
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
                if (data.mode != ListMode.ALL) data.parentTitle(task)?.let { a(href = "/tasks/${task.parentId}?mode=$mode", classes = "parent") { openInPanel(); +"$it: " } }
                a(href = "/tasks/${task.id}?mode=$mode", classes = "edit") { openInPanel(); +task.title }
                // On the title's line, wrapping along with it.
                due?.let { dueTail(it, status!!, data.now) }
                if (task.type != TaskType.CHECKLIST) data.subtaskCounts(task)?.let { (done, total) -> span(classes = "tail") { +" · $done/$total" } }
                if (task.recurrenceType != null) span(classes = "badge") { attributes["title"] = "Recurring"; icon(Icon.REPEAT, "") }
                if (!task.notes.isNullOrBlank()) span(classes = "has-notes") { attributes["title"] = "Has notes"; icon(Icon.NOTES, "") }
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
                // Pinned on every device: the phone's notification and the desktop's top bar.
                button(classes = "menu-row") {
                    hx("post", "/tasks/${task.id}/pin?mode=$mode")
                    icon(Icon.PUSH_PIN, "")
                    +Labels.PIN
                }
                // Every device goes into focus on it.
                button(classes = "menu-row") {
                    hx("post", "/focus/start?task=${task.id}", "this")
                    icon(Icon.CENTER_FOCUS, "")
                    +"Focus"
                }
                // The phone's swipe right: a subtask under it (a checklist's are items).
                form(classes = "inline menu-add") {
                    hx("post", "/tasks/${task.id}/add-subtask?mode=$mode")
                    textInput(name = "text") {
                        placeholder = if (task.type == TaskType.CHECKLIST) "+ ${Labels.ADD_ITEM}" else "+ ${Labels.SUBTASK}"
                        attributes["autocomplete"] = "off"
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

// A note's text with its links opening in a new tab (trailing punctuation isn't part of the link).
internal fun FlowContent.linkified(text: String) {
    var at = 0
    Regex("https?://\\S+").findAll(text).forEach { m ->
        val url = m.value.trimEnd('.', ',', ')', ';', ':', '!', '?')
        +text.substring(at, m.range.first)
        a(href = url) { target = "_blank"; rel = "noopener"; +url }
        at = m.range.first + url.length
    }
    +text.substring(at)
}

// Opens a link's page in the detail panel, keeping the list as it is (the URL still changes, so reload
// and back work). The page itself renders the list and the panel, for opening it directly.
internal fun A.openInPanel() {
    attributes["hx-get"] = href.orEmpty()
    attributes["hx-select"] = "#detail"
    attributes["hx-target"] = "#detail"
    attributes["hx-swap"] = "outerHTML"
    attributes["hx-push-url"] = "true"
}

fun DIV.deletedToastContents(task: Task, q: String) {
    classes = setOf("show")
    span { +"Deleted “${task.title}”" }
    button {
        hx("post", "/tasks/${task.id}/restore?mode=$q")
        +"Undo"
    }
}

// The phone's "all subtasks done" prompt: complete the project, add its next step, or not now.
private fun FlowContent.stalledPrompt(project: Task, mode: ListMode, q: String) = div(classes = "stalled") {
    val m = q
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

// The editor's pin, like the phone's: applies at once (not on Save) and toggles in place.
internal fun FlowContent.pinToggle(taskId: Long, pinned: Boolean) = button(type = ButtonType.button, classes = if (pinned) "pin-toggle on" else "pin-toggle") {
    hx("post", "/tasks/$taskId/pin-toggle", "this")
    attributes["title"] = if (pinned) Labels.UNPIN else Labels.PIN
    icon(Icon.PUSH_PIN, "")
}

fun DIV.pinnedToastContents(task: Task) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    classes = setOf("show")
    span { +"Pinned “${task.title}”" }
}

// Confirms a quick add made from a page without a list to show it in.
fun DIV.addedToastContents(task: Task) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    classes = setOf("show")
    span { +"Added “${task.title}”" }
}

// Shown out-of-band after completing, with a one-click undo.
fun DIV.undoToastContents(task: Task, q: String) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    classes = setOf("show")
    span { +"Completed “${task.title}”" }
    button {
        hx("post", "/tasks/${task.id}/uncomplete?mode=$q")
        +"Undo"
    }
}
