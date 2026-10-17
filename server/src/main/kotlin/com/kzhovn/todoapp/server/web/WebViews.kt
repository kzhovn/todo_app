package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.linkSegments
import com.kzhovn.todoapp.data.laterText
import com.kzhovn.todoapp.data.waitingBlocks
import com.kzhovn.todoapp.data.timeLeft
import com.kzhovn.todoapp.data.isTimedWait
import com.kzhovn.todoapp.repository.RunningWait
import com.kzhovn.todoapp.data.nearestFolder
import com.kzhovn.todoapp.data.ShownDue
import com.kzhovn.todoapp.data.completeQuestion
import com.kzhovn.todoapp.repository.dimmedInTree
import com.kzhovn.todoapp.widget.TodoWidgetPresenter
import com.kzhovn.todoapp.data.inMode
import com.kzhovn.todoapp.data.TaskOrder
import kotlinx.html.h2
import kotlinx.html.img
import kotlinx.html.A
import kotlinx.html.ASIDE
import kotlinx.html.BUTTON
import kotlinx.html.ButtonType
import com.kzhovn.todoapp.data.OutlinerNode
import com.kzhovn.todoapp.data.buildOutlinerTree
import kotlinx.html.HTMLTag
import com.kzhovn.todoapp.data.subtaskCounts
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.waitingFor
import com.kzhovn.todoapp.data.checkInEvery
import com.kzhovn.todoapp.data.SnoozeChoice
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.dueText
import com.kzhovn.todoapp.data.folderColorsArgb
import com.kzhovn.todoapp.data.formatDuration
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
import com.kzhovn.todoapp.data.AgendaDay
import com.kzhovn.todoapp.data.DateKind
import com.kzhovn.todoapp.data.agenda
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

// The calendar (CalendarPage.kt) is a list too, so its agenda's rows act and re-render as the others do; it
// sits under More, not with the three lists.
enum class ListMode(val label: String) { DOING(Labels.DOING), ACTIVE(Labels.ACTIVE), ALL(Labels.ALL), CALENDAR(Labels.CALENDAR) }
val LISTS = ListMode.entries - ListMode.CALENDAR

// The calendar's month on screen, the day picked in it (null: the agenda runs on), and the kinds of date shown.
data class CalendarView(val month: YearMonth, val day: LocalDate?, val kinds: Set<DateKind>)

// Where a list is: its mode, a folder's view of the All tree, the calendar's month and day.
class ListPlace(val mode: ListMode, val folder: Long?, val calendar: CalendarView?) {
    // In a URL's query ("mode=ALL&folder=12" goes in as `?mode=$q`), so an action on a row re-renders the same
    // view; `path` is the page itself.
    val q: String get() = mode.name + extra("&")
    val path: String get() = mode.path + extra("?")
    private fun extra(first: String) = listOfNotNull(folder?.let { "folder=$it" }, calendar?.let { "month=${it.month}" }, calendar?.day?.let { "day=$it" })
        .joinToString("&").let { if (it.isEmpty()) "" else first + it }
}


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
    val selected: Long? = null,
    val calendar: CalendarView? = null
) {
    private val place = ListPlace(mode, folder, calendar)
    val q: String get() = place.q
    val path: String get() = place.path
    val title: String get() = folder?.let { byId[it]?.title } ?: mode.label

    val all: List<Task> = service.tasks()
    val byId = all.associateBy { it.id }
    // Folder mode: the folder every device is zoomed into (null: none). Doing and Active follow it
    // (TaskService), and the All tree is rooted at it.
    val modeFolder: Task? = service.modeFolder()
    // Doing in a mode: what's due today or overdue outside it, in one line under the list.
    val dueOutside: List<Task> = if (mode == ListMode.DOING && modeFolder != null) service.dueOutsideMode() else emptyList()
    // Doing's quiet Waiting section: waiting items due a check-in, and how many open tasks each blocks.
    val waiting: List<Task> = if (mode == ListMode.DOING) service.waitingToCheck() else emptyList()
    val waitingBlocks: Map<Long, Int> = if (waiting.isEmpty()) emptyMap() else waitingBlocks(waiting, service.dependencyEdges(), byId)
    // Doing's Running line: waits counting down, each with the step that comes after it.
    val running: List<RunningWait> = if (mode == ListMode.DOING) service.runningWaits() else emptyList()
    val folderColors = folderColorsHex(all)
    // New ▾'s "From template…" (see Templates.kt).
    val templates: List<Task> by lazy { service.templates() }
    private val counts = subtaskCounts(all)
    // Worked out once per request: the list and the sidebar's counts both need them.
    val active: List<Task> by lazy { service.active() }
    // The All tree dims what isn't workable for a reason other than contexts (dimmedInTree).
    val workable: Set<Long> by lazy { service.activeIgnoringContexts().map { it.id }.toSet() }
    val doing: List<Task> by lazy { service.doing(active) }
    val tasks: List<Task> = when (mode) {
        ListMode.DOING -> doing
        ListMode.ACTIVE -> active
        ListMode.ALL, ListMode.CALENDAR -> emptyList() // the tree is rendered from `roots`, the calendar from `agenda`
    }
    val agenda: List<AgendaDay> by lazy {
        calendar?.let { agenda(all, it.month, it.day, it.kinds, now, service.reminderHour(), modeFolder?.id) }.orEmpty()
    }

    private val dues = service.dueDates()
    fun effectiveDue(task: Task) = dues.of(task)
    fun parentTitle(task: Task) = subtaskParentTitle(task, byId)
    fun folderColor(task: Task): String? = nearestFolder(task, byId)?.let { folderColors[it.id] }
    fun ownColor(folder: Task) = folderColors[folder.id]
    fun subtaskCounts(task: Task): Pair<Int, Int>? = counts[task.id]
    // The tree's top level for this view: a folder's contents, the mode's, or everything. As the phone's:
    // completed tasks and projects drop out (their open subtasks move up); completed checklists stay, struck through.
    val roots: List<OutlinerNode> by lazy { buildOutlinerTree(all, hideCompleted = true, rootId = folder ?: modeFolder?.id) }

    // One open project whose steps are all done, to ask about (the phone asks the same, one at a time).
    val stalled: Task? = stalledProjects(all).firstOrNull { it.id !in later }
}

// The same colour families as the app (folderColorsArgb in :core), as CSS colours.
internal fun folderColorsHex(tasks: Collection<Task>): Map<Long, String> = folderColorsArgb(tasks).mapValues { "#%06X".format(it.value and 0xFFFFFF) }

// What leads a task's row, by type: a project's icon (ticked like a checkbox; done, it looks like one), a
// waiting item's hourglass in a checkbox's ring (ticked like one: Resolved), a checklist's "3/8"
// (ticked item by item, inside it; opens it), or a folder's icon (search only), or a task's checkbox, which
// `check` sets up (what ticking it does differs by list).
internal fun FlowContent.taskMark(task: Task, counts: Pair<Int, Int>?, mode: String, check: BUTTON.() -> Unit) = when (task.type) {
    TaskType.PROJECT -> button(classes = "project") { check(); attributes["title"] = Labels.PROJECT; if (!task.isComplete) icon(Icon.PROJECT, "") }
    TaskType.WAITING -> button(classes = "project waiting-mark") { check(); attributes["title"] = Labels.RESOLVED; icon(Icon.HOURGLASS, "") }
    TaskType.FOLDER -> span(classes = "project") { icon(Icon.FOLDER, "") } // search results only
    TaskType.SECTION -> span(classes = "project") {} // a checklist's section: nothing to tick
    TaskType.CHECKLIST -> {
        val (done, total) = counts ?: (0 to 0)
        a(href = "/tasks/${task.id}?mode=$mode", classes = "count") { attributes["title"] = Labels.CHECKLIST; +"$done/$total" }
    }
    else -> button(classes = "check") { check() }
}

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
    HOURGLASS("M6 2v6h.01L6 8.01 10 12l-4 4 .01.01H6V22h12v-5.99h-.01L18 16l-4-4 4-3.99-.01-.01H18V2H6zm10 14.5V20H8v-3.5l4-4 4 4zm-4-5-4-4V4h8v3.5l-4 4z"), // HourglassEmpty (outlined)
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
    OPEN_IN_FULL("M21 11V3h-8l3.29 3.29-10 10L3 13v8h8l-3.29-3.29 10-10z"),
    SEARCH("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"),
    REFRESH("M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"),
    CHECKLIST("M22 7h-9v2h9V7zm0 8h-9v2h9v-2zM5.54 11L2 7.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 11zm0 8L2 15.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 19z"),
    DATE_RANGE("M9 11H7v2h2v-2zm4 0h-2v2h2v-2zm4 0h-2v2h2v-2zm2-7h-1V2h-2v2H8V2H6v2H5c-1.11 0-1.99.9-1.99 2L3 20c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 16H5V9h14v11z")
}

// Each icon points into the page's one copy of the paths (iconSprite), which keeps a long list's markup short.
// Fill and stroke are inherited into the copy, so CSS colours an icon through its svg, not its path.
internal fun FlowContent.icon(icon: Icon, classes: String, color: String? = null) = span(classes = "icon $classes") {
    color?.let { style = "color: $it" }
    unsafe { +"<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><use href=\"#i-${icon.name}\"/></svg>" }
}

// Every page carries it, so a list or panel swapped in by htmx finds its icons.
private val iconSprite = Icon.entries.joinToString("", "<svg class=\"sprite\" aria-hidden=\"true\">", "</svg>") {
    "<symbol id=\"i-${it.name}\" viewBox=\"0 0 24 24\"><path d=\"${it.path}\"/></symbol>"
}

// A static file's URL, with a hash of the file: browsers keep static files for good (webRoutes), so a changed
// file has to be a new URL.
private val assetVersions = ConcurrentHashMap<String, String>()
internal fun asset(path: String): String = "/static/$path?v=" + assetVersions.getOrPut(path) {
    val bytes = webjars[path] ?: Icon::class.java.classLoader.getResource("static/$path")!!.readBytes()
    CRC32().apply { update(bytes) }.value.toString(36)
}

fun HTML.page(title: String, content: BODY.() -> Unit) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title(title)
        link(rel = "stylesheet", href = asset("app.css"))
        // The phone app's raspberry icon, cropped as the launcher shows it.
        link(rel = "icon", href = asset("icon-32.png"), type = "image/png") { attributes["sizes"] = "32x32" }
        link(rel = "icon", href = asset("icon-192.png"), type = "image/png") { attributes["sizes"] = "192x192" }
        link(rel = "apple-touch-icon", href = asset("apple-touch-icon.png"))
        script(src = asset(HTMX)) {}
        script(src = asset("app.js")) { defer = true }
    }
    body {
        unsafe { +iconSprite }
        content()
    }
}

// deleted: a task just deleted from the editor, offered back with an Undo toast.
fun HTML.listPage(service: TaskService, data: ListData, deleted: Task? = null) =
    shellPage(service, "Raspberry · ${data.title}", data.path, data, toast = deleted?.let { { deletedToastContents(it, data.q) } }) { listColumn(data) }

// The list column: a header (the list's name, bulk selection, New) over the list itself.
fun FlowContent.listColumn(data: ListData) {
    if (data.mode == ListMode.CALENDAR) return calendarColumn(data)
    div(classes = "list-head") {
        h2 {
            +data.title
            if (data.folder == null) data.modeFolder?.let { span(classes = "mode-name") { +it.title } }
        }
        if (data.mode != ListMode.ALL) span(classes = "list-count") { id = "list-count"; +data.tasks.size.toString() }
        // A folder's view: zoom the whole app into it (z).
        data.folder?.takeIf { it != data.modeFolder?.id }?.let { folder ->
            button(classes = "zoom-in") { modeButton(folder, key = true); icon(Icon.OPEN_IN_FULL, ""); +"Zoom in" }
        }
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
                fromTemplateItems(data.templates)
            }
        }
    }
    div { listContents(data) }
}

val ListMode.path get() = "/${name.lowercase()}"

// The sidebar's counts, and its folders (top level, in tree order) with their open tasks.
// In folder mode: the counts are the mode's, and the folders are the mode folder's own.
// `list`: the page's list, whose tasks, Active and Doing it reuses rather than working them out again.
private class SideNav(service: TaskService, list: ListData?) {
    val all = list?.all ?: service.tasks()
    private val byId = list?.byId ?: all.associateBy { it.id }
    val mode = if (list != null) list.modeFolder else service.modeFolder()
    private val active = list?.active ?: service.active()
    private val open = TodoWidgetPresenter.allOpen(all, byId)
    val counts = mapOf(
        ListMode.DOING to (list?.doing ?: service.doing(active)).size, ListMode.ACTIVE to active.size,
        ListMode.ALL to open.count { inMode(it, mode?.id, byId) }
    )
    val colors = list?.folderColors ?: folderColorsHex(all)
    val folders = all.filter { it.type == TaskType.FOLDER && it.parentId == mode?.id }.sortedWith(TaskOrder).let { shown ->
        // Each open task counted once, under whichever of these folders it's in.
        val ids = shown.map { it.id }.toSet()
        val inFolder = open.groupingBy { t -> t.parentId?.let { walkParentChain(it, byId) { id -> id.takeIf { it in ids } } } }.eachCount()
        shown.map { it to (inFolder[it.id] ?: 0) }
    }
}

// After a list action, the counts elsewhere on the page (the list's own, the sidebar's), swapped in
// beside the list by htmx.
fun countsOob(service: TaskService, data: ListData): String {
    val nav = SideNav(service, data)
    fun count(id: String, classes: String, n: Int) = createHTML().span(classes = classes) { this.id = id; attributes["hx-swap-oob"] = "true"; +n.toString() }
    return buildString {
        if (data.mode in LISTS - ListMode.ALL) append(count("list-count", "list-count", data.tasks.size))
        LISTS.forEach { append(count("n-${it.name.lowercase()}", "n", nav.counts.getValue(it))) }
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
    service.focusSession()?.let { return focusPage(service, it, list?.collapsed.orEmpty()) }
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
    val nav = SideNav(service, list)
    div(classes = "shell") {
        aside(classes = "sidebar") {
            a(href = "/doing", classes = "brand") { img(src = asset("icon-32.png"), alt = "") ; +"Raspberry" }
            // Folder mode, in the folder's colour; ✕ (or z, outside a folder's view) leaves it everywhere.
            nav.mode?.let { mode ->
                div(classes = "modebar") {
                    nav.colors[mode.id]?.let { style = "background: $it" }
                    icon(Icon.OPEN_IN_FULL, "")
                    span { +"${mode.title} mode" }
                    button(classes = "mode-leave") { modeButton(null, key = list?.folder == null); attributes["aria-label"] = "Leave ${mode.title} mode"; +"✕" }
                }
            }
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
                textInput(name = "text") { id = "quickadd"; placeholder = nav.mode?.let { "Add to ${it.title}… (n)" } ?: "Add a task… (n)"; attributes["autocomplete"] = "off" }
            }
            // The current task (pinned, or its timer running), shared with every device.
            service.pinned()?.let { now ->
                div(classes = "now") {
                    span(classes = "now-label") { +"Now" }
                    a(href = "/tasks/${now.id}?mode=DOING", classes = "now-title") { openInPanel(); +now.title }
                    span(classes = "now-timer") {}
                    pinControl(now, pinned = true, refresh = true)
                }
            }
            nav(classes = "nav") {
                LISTS.forEach { m ->
                    a(href = m.path, classes = if (m.path == current) "current" else null) { +m.label; span(classes = "n") { id = "n-${m.name.lowercase()}"; +nav.counts.getValue(m).toString() } }
                }
            }
            if (nav.folders.isNotEmpty()) {
                div(classes = "nav-label") { +(nav.mode?.let { "In ${it.title}" } ?: "Folders") }
                nav(classes = "nav folders") {
                    nav.folders.forEach { (folder, open) ->
                        val path = "/all?folder=${folder.id}"
                        div(classes = "nav-folder") {
                            a(href = path, classes = if (path == current) "current" else null) {
                                span(classes = "fdot") { nav.colors[folder.id]?.let { style = "background: $it" } }
                                +folder.title
                                span(classes = "n") { id = "n-${folder.id}"; +open.toString() }
                            }
                            button(classes = "zoom") { modeButton(folder.id); attributes["title"] = "${folder.title} mode"; icon(Icon.OPEN_IN_FULL, "") }
                        }
                    }
                }
            }
            div(classes = "nav-label") { +"More" }
            nav(classes = "nav") {
                listOf("/focus" to Labels.FOCUS, "/calendar" to Labels.CALENDAR, "/search" to Labels.SEARCH, "/templates" to Labels.TEMPLATES, "/review" to Labels.REVIEW, "/contexts" to Labels.CONTEXTS, "/settings" to Labels.SETTINGS)
                    .forEach { (path, label) -> a(href = path, classes = if (path == current.substringBefore('?')) "current" else null) { +label } }
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
    div(classes = "key-head") { +Labels.QUICK_ADD }
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
    attributes["hx-get"] = "/list/${data.mode.name.lowercase()}" + data.path.removePrefix(data.mode.path)
    // app.js keeps the calendar's grid on the agenda's month and day.
    data.calendar?.let { attributes["data-month"] = it.month.toString(); attributes["data-day"] = it.day?.toString().orEmpty() }
    attributes["hx-trigger"] = "every 60s, visibilitychange from:document, refresh from:body"
    attributes["hx-swap"] = "outerHTML"
    data.stalled?.let { stalledPrompt(it, data.mode, data.q) }
    runningSection(data)
    if (data.mode == ListMode.ALL) {
        if (data.roots.isEmpty()) p(classes = "empty") { +Labels.NOTHING_HERE }
        tree(data, data.roots, parentId = null, depth = 0)
    } else if (data.mode == ListMode.CALENDAR) {
        agendaContents(data)
    } else if (data.tasks.isEmpty()) {
        p(classes = "empty") { +Labels.NOTHING_HERE }
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
    // Folder mode hides the rest; this keeps it from hiding something due today.
    if (data.dueOutside.isNotEmpty()) details(classes = "due-outside") {
        summary { +"${data.dueOutside.size} due today outside ${data.modeFolder!!.title} · "; span(classes = "show") { +"show" } }
        data.dueOutside.forEach { taskRow(data, it, depth = 0) }
    }
    // Waiting items due a check-in, quietly under a hairline; gone entirely when there are none.
    if (data.waiting.isNotEmpty()) div(classes = "waiting-section") {
        div(classes = "waiting-label") { +Labels.WAITING }
        val m = data.q
        data.waiting.forEach { t ->
            div(classes = "waiting-row") {
                attributes["data-task-id"] = t.id.toString()
                taskMark(t, null, m) { hx("post", "/tasks/${t.id}/complete?mode=$m") }
                a(href = "/tasks/${t.id}?mode=$m", classes = "edit") { openInPanel(); +t.title }
                span(classes = "meta") { +listOfNotNull(waitingFor(t, data.now), data.waitingBlocks[t.id]?.let { "blocks $it" }).joinToString(" · ") }
                deferPill(t, m)
                waitingMenu(t)
            }
        }
    }
}

// Doing's Running line, one row per wait counting down (the list re-renders each minute): ✓ ends it early (the
// machine finished), +10 min puts it off.
private fun FlowContent.runningSection(data: ListData) {
    if (data.running.isEmpty()) return
    val m = data.q
    div(classes = "running-section") {
        data.running.forEach { (wait, next) ->
            div(classes = "running-row") {
                attributes["data-task-id"] = wait.id.toString()
                span(classes = "running-label") { +Labels.RUNNING }
                a(href = "/tasks/${wait.id}?mode=$m", classes = "edit") { openInPanel(); +wait.title }
                next?.let { span(classes = "then") { +" · ${Labels.thenStep(it.title)}" } }
                span(classes = "left") { +timeLeft(wait.dueDate!!, data.now) }
                button(classes = "running-done") { hx("post", "/tasks/${wait.id}/complete?mode=$m"); attributes["title"] = Labels.RESOLVED; icon(Icon.CHECK, "") }
                button(classes = "defer") { hx("post", "/tasks/${wait.id}/more-wait?mode=$m"); +Labels.MORE_WAIT }
            }
        }
    }
}

// A waiting row's Still waiting: "+3d", the next check-in one interval from today.
private fun FlowContent.deferPill(task: Task, mode: String) = button(classes = "defer") {
    hx("post", "/tasks/${task.id}/still-waiting?mode=$mode")
    attributes["title"] = Labels.STILL_WAITING
    +Labels.deferBy(task.checkInEvery())
}

// A waiting item's right-click menu: Follow up fills the quick add box (app.js) with a task to chase it.
private fun FlowContent.waitingMenu(task: Task) = details(classes = "more") {
    summary { +"⋯" }
    div(classes = "menu") {
        button(classes = "menu-row") { attributes["data-follow-up"] = Labels.followUp(task.title); icon(Icon.ADD, ""); +Labels.FOLLOW_UP }
    }
}

// Enters folder mode on this folder (null: leaves it) on every device, then reloads the page into it.
// key: z presses it.
internal fun HTMLTag.modeButton(folderId: Long?, key: Boolean = false) {
    attributes["hx-post"] = "/mode" + (folderId?.let { "?folder=$it" } ?: "")
    attributes["hx-swap"] = "none"
    if (key) attributes["data-key"] = "z"
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
                if (item.id != data.modeFolder?.id) button(classes = "zoom") {
                    modeButton(item.id); attributes["tabindex"] = "-1"; attributes["title"] = "${item.title} mode"; icon(Icon.OPEN_IN_FULL, "")
                }
            }
        } else if (item.type == TaskType.SECTION) {
            // A checklist's section: a plain heading over its items (renamed in the checklist's editor).
            div(classes = "section-row") {
                style = "padding-left: ${depth * 18}px"
                outline()
                span(classes = "section-title") { +item.title }
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

// `tag`: on the calendar, what put the row on its day ("starts", "reminder 5:00 PM"), said in place of a start to come.
internal fun FlowContent.taskRow(data: ListData, task: Task, depth: Int, outlineNode: (DIV.() -> Unit)? = null, tag: String? = null) {
    val mode = data.q
    div(classes = "row") {
        attributes["data-task-id"] = task.id.toString()
        if (task.id == data.selected) classes = classes + "current"
        attributes["data-type"] = task.type.name
        // The calendar shows maybes, dimmed.
        if (task.isBackburner(data.now) || (data.mode == ListMode.CALENDAR && task.isMaybe)) classes = classes + "dim"
        else if (data.mode == ListMode.ALL && dimmedInTree(task, data.workable, data.byId, data.now)) classes = classes + "later"
        if (task.isComplete) classes = classes + "done"
        style = "padding-left: ${depth * 18}px; border-left-color: ${data.folderColor(task) ?: "var(--border)"}"
        outlineNode?.invoke(this)
        // A timed wait's end shows as its own tail ("32m left"), not as a due date.
        val due = data.effectiveDue(task)?.takeUnless { task.isComplete || task.isTimedWait }
        val status = due?.let { dueStatus(it.at, data.now) }
        taskMark(task, data.subtaskCounts(task), mode) {
            // Due today: an orange ring; overdue: rust. Each with a pale fill of its own colour.
            when (status) { DueStatus.OVERDUE -> classes = classes + "overdue"; DueStatus.TODAY -> classes = classes + "today"; else -> {} }
            hx("post", "/tasks/${task.id}/complete?mode=$mode")
            attributes["aria-label"] = "Complete"
        }
        div(classes = "main") {
            div(classes = "title") {
                // In the All tree indentation already shows nesting; flat lists say "Parent: subtask".
                // The parent part opens the parent's editor, as the title opens the subtask's.
                if (data.mode != ListMode.ALL) data.parentTitle(task)?.let { a(href = "/tasks/${task.parentId}?mode=$mode", classes = "parent") { openInPanel(); +"$it: " } }
                a(href = "/tasks/${task.id}?mode=$mode", classes = "edit") { openInPanel(); +task.title }
                if (task.isHighPriority) span(classes = "priority-high") { attributes["title"] = "High priority"; +"!" }
                // On the title's line, wrapping along with it.
                due?.let { dueTail(it, status!!, data.now) }
                // A start still to come (the All tree shows snoozed and future tasks); a waiting item's own line.
                (tag ?: laterText(task, data.now))?.let { span(classes = "tail") { +" · $it" } }
                if (task.type != TaskType.CHECKLIST) data.subtaskCounts(task)?.let { (done, total) -> span(classes = "tail") { +" · $done/$total" } }
                if (task.recurrenceType != null) span(classes = "badge") { attributes["title"] = "Recurring"; icon(Icon.REPEAT, "") }
                if (!task.notes.isNullOrBlank()) span(classes = "has-notes") { attributes["title"] = Labels.HAS_NOTES; icon(Icon.NOTES, "") }
            }
        }
        // A timed task's play button, "▶ 1h"; app.js runs the countdown (one at a time, per browser)
        // and shows the time left on it. A wait's timer runs by itself (Workflows.kt).
        task.durationMinutes?.takeIf { task.type != TaskType.WAITING }?.let { minutes ->
            button(classes = "play") {
                attributes["data-task-id"] = task.id.toString()
                attributes["data-minutes"] = minutes.toString()
                attributes["data-title"] = task.title
                attributes["aria-label"] = Labels.START_TIMER
                icon(Icon.PLAY, "play-icon")
                icon(Icon.PAUSE, "pause-icon")
                span(classes = "play-time") { +formatDuration(minutes) }
            }
        }
        if (task.type == TaskType.WAITING) {
            if (task.dueDate == null && !task.isComplete && !task.isTimedWait) deferPill(task, mode)
            waitingMenu(task)
        } else details(classes = "more") {
            summary { attributes["aria-label"] = "Snooze"; +"⋯" }
            // "Snooze" over three equal tiles, like the app's row menu.
            div(classes = "menu") {
                div(classes = "menu-label") { +Labels.SNOOZE }
                div(classes = "tiles") {
                    SnoozeChoice.entries.forEach { c ->
                        button(classes = "tile") {
                            hx("post", "/tasks/${task.id}/snooze?until=${c.name.lowercase()}&mode=$mode")
                            icon(when (c) { SnoozeChoice.HOUR -> Icon.SCHEDULE; SnoozeChoice.TOMORROW -> Icon.BEDTIME; SnoozeChoice.WEEK -> Icon.DATE_RANGE }, "")
                            span { +c.label }
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
                    +Labels.FOCUS
                }
                // A copy kept as a template; this one carries on as it is.
                button(classes = "menu-row") {
                    attributes["hx-post"] = "/tasks/${task.id}/save-template"
                    attributes["hx-swap"] = "none"
                    +Labels.SAVE_AS_TEMPLATE
                }
                // A repeat: on to its next time without doing this one.
                if (task.recurrenceType != null) button(classes = "menu-row") {
                    hx("post", "/tasks/${task.id}/skip?mode=$mode")
                    icon(Icon.REPEAT, "")
                    +Labels.SKIP
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
// One taken from a task waiting on this one is in italics and names it.
internal fun FlowContent.dueTail(due: ShownDue, status: DueStatus, now: Long) = span(classes = "tail due " + status.name.lowercase() + if (due.forTask != null) " for" else "") {
    +" · ${dueText(due, now)}"
}

// A note's text with its links opening in a new tab (trailing punctuation isn't part of the link).
internal fun FlowContent.linkified(text: String) = linkSegments(text).forEach { (part, isLink) ->
    if (isLink) a(href = part) { target = "_blank"; rel = "noopener"; +part } else +part
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
    span { +Labels.deleted(task.title) }
    button {
        hx("post", "/tasks/${task.id}/restore?mode=$q")
        +Labels.UNDO
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
    val question = completeQuestion(task, open) ?: return
    classes = setOf("show", "question")
    span { +"Complete “${task.title}”? ${question.text}" }
    val sep = if ('?' in url) '&' else '?'
    listOf("complete" to question.completeAll, "promote" to question.moveOut).forEach { (choice, label) ->
        button {
            hx("post", "$url${sep}subtasks=$choice", target)
            include?.let { attributes["hx-include"] = it }
            +label
        }
    }
    button(classes = "dismiss") { +question.cancel }
}

// The pin, in the editor and the sidebar's Now box: a click pins or unpins at once (not on Save);
// right-click (or a long press) opens the rest: focus, and the timer for a timed task. app.js places
// the menu. `refresh`: reload the page after, so the Now box comes and goes.
internal fun FlowContent.pinControl(task: Task, pinned: Boolean, refresh: Boolean = false) = span(classes = "pin-control") {
    val toggle = "/tasks/${task.id}/pin-toggle" + if (refresh) "?refresh=1" else ""
    button(type = ButtonType.button, classes = if (pinned) "pin-toggle on" else "pin-toggle") {
        hx("post", toggle, "closest .pin-control")
        attributes["title"] = (if (pinned) Labels.UNPIN else Labels.PIN) + " (right-click for more)"
        icon(Icon.PUSH_PIN, "")
    }
    div(classes = "pin-menu") {
        button(type = ButtonType.button, classes = "menu-row") { hx("post", toggle, "closest .pin-control"); icon(Icon.PUSH_PIN, ""); +(if (pinned) Labels.UNPIN else Labels.PIN) }
        // Every device goes into focus on it.
        button(type = ButtonType.button, classes = "menu-row") { hx("post", "/focus/start?task=${task.id}", "this"); icon(Icon.CENTER_FOCUS, ""); +Labels.FOCUS }
        // app.js's play handler starts (or pauses) the shared timer, as a row's ▶ does.
        task.durationMinutes?.takeIf { task.type != TaskType.WAITING }?.let { minutes ->
            button(type = ButtonType.button, classes = "menu-row play") {
                attributes["data-task-id"] = task.id.toString(); attributes["data-minutes"] = minutes.toString(); attributes["data-title"] = task.title
                icon(Icon.PLAY, ""); +"Timer · ${formatDuration(minutes)}"
            }
        }
    }
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
        +Labels.UNDO
    }
}
