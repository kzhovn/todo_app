package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.widget.TodoWidgetPresenter
import com.kzhovn.todoapp.data.inMode
import com.kzhovn.todoapp.data.TaskOrder
import io.ktor.http.encodeURLParameter
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.forcedChoices
import com.kzhovn.todoapp.data.forcedShown
import com.kzhovn.todoapp.repository.ContextSummary
import com.kzhovn.todoapp.repository.contextSummaries
import kotlinx.html.FormMethod
import kotlinx.html.form
import kotlinx.html.label
import kotlinx.html.hiddenInput
import kotlinx.html.details
import kotlinx.html.summary
import kotlinx.html.hr
import kotlinx.html.h1
import kotlinx.html.img
import kotlinx.html.A
import kotlinx.html.ASIDE
import kotlinx.html.ButtonType
import kotlinx.html.HTMLTag
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.walkParentChain
import com.kzhovn.todoapp.server.TaskService
import kotlinx.html.BODY
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.summary
import kotlinx.html.hr
import kotlinx.html.h1
import kotlinx.html.textInput
import kotlinx.html.title
import kotlinx.html.unsafe
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

// The page around every screen: the sidebar with quick add, the icons and static files. The lists are in WebViews.kt.

// An htmx request whose response replaces `target` (the list, by default).
internal fun HTMLTag.hx(verb: String, url: String, target: String = "#list") {
    attributes["hx-$verb"] = url
    attributes["hx-target"] = target
    attributes["hx-swap"] = "outerHTML"
}

// The Material icons the phone uses (Icons.Filled.*), inlined so both clients look alike. The top bar's
// copies are made from these by tools/tray_icons.py.
internal enum class Icon(val path: String) {
    CIRCLE("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z"), // RadioButtonUnchecked
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
    REDO("M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z"), // Redo
    BAR_CHART("M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z"),
    CONTENT_COPY("M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"),
    ALTERNATE_EMAIL("M12 1.95c-5.52 0-10 4.48-10 10s4.48 10 10 10h5v-2h-5c-4.34 0-8-3.66-8-8s3.66-8 8-8 8 3.66 8 8v1.43c0 .79-.71 1.57-1.5 1.57s-1.5-.78-1.5-1.57v-1.43c0-2.76-2.24-5-5-5s-5 2.24-5 5 2.24 5 5 5c1.38 0 2.64-.56 3.54-1.47.65.89 1.77 1.47 2.96 1.47 1.97 0 3.5-1.6 3.5-3.57v-1.43c0-5.52-4.48-10-10-10zm0 13c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3z"),
    SETTINGS("M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"),
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
    val topFolders get() = all.filter { it.type == TaskType.FOLDER && it.parentId == null }.sortedWith(TaskOrder)
    val contexts: List<ContextSummary> = (list?.contexts ?: service.contexts()).let { cs ->
        contextSummaries(cs, cs.flatMap { list?.windows(it.id) ?: service.timeWindows(it.id) }, all, service.contextIdsByTask(), list?.now ?: service.now(), service.events())
    }
}

// A context in the sidebar: it filters the list on the page (Doing, off a list page), and again clears it.
// ⋯ holds its Auto/On/Off and its editor. Its dot: green while it holds, grey while it doesn't, a ring for a
// filter-only one.
private fun FlowContent.contextLink(summary: ContextSummary, list: ListData?, back: String) = div(classes = "nav-context") {
    val (c, _, holds) = summary
    val on = list?.search?.context == c.id
    val search = (list?.search ?: ListSearch()).copy(context = c.id.takeUnless { on })
    val href = list?.takeIf { it.mode in LISTS }?.let { it.pathOf(it.mode, it.folder, search) } ?: ListPlace(ListMode.DOING, null, null, search).path
    a(href = href, classes = if (on) "current" else null) {
        span(classes = "cdot" + when { !holds -> " off"; c.type == ContextType.LABEL -> " label"; else -> "" }) {}
        +c.name
        span(classes = "n") { +(if (on) "✕" else summary.countText) }
    }
    details(classes = "pp context-menu") {
        summary { attributes["aria-label"] = c.name; +"⋯" }
        div(classes = "pop") {
            form(action = "/contexts/${c.id}/forced?back=${back.encodeURLParameter()}", method = FormMethod.post, classes = "pills context-forced") {
                forcedChoices(c).forEach { (value, label) ->
                    button(type = ButtonType.submit, name = "forced", classes = if (forcedShown(c) == value) "pill set" else "pill") { this.value = value?.toString() ?: "auto"; +label }
                }
            }
            a(href = "/contexts?edit=${c.id}") { +Labels.editContext(c.name) }
            a(href = "/contexts") { +Labels.MANAGE_CONTEXTS }
        }
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

// Every page: the sidebar (quick add, the lists, folders, the other screens, Settings), then the page's own
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
                typeChip(TaskType.TASK, TaskType.TASK)
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
            // What to highlight: a list page by its list (not its search), else the page's path.
            val here = list?.let { ListPlace(it.mode, it.folder?.takeIf { _ -> it.mode == ListMode.ALL }, null).path } ?: current
            // The context filter goes along between lists, until cleared.
            val context = list?.search?.context
            fun carried(path: String) = context?.let { path + (if ('?' in path) "&" else "?") + "context=$it" } ?: path
            nav(classes = "nav") {
                LISTS.forEach { m ->
                    a(href = carried(m.path), classes = if (m.path == here) "current" else null) { +m.label; span(classes = "n") { id = "n-${m.name.lowercase()}"; +nav.counts.getValue(m).toString() } }
                }
            }
            if (nav.folders.isNotEmpty()) {
                div(classes = "nav-label") { +(nav.mode?.let { "In ${it.title}" } ?: "Folders") }
                nav(classes = "nav folders") {
                    nav.folders.forEach { (folder, open) ->
                        val path = "/all?folder=${folder.id}"
                        div(classes = "nav-folder") {
                            a(href = carried(path), classes = if (path == here) "current" else null) {
                                span(classes = "fdot") { nav.colors[folder.id]?.let { style = "background: $it" } }
                                +folder.title
                                span(classes = "n") { id = "n-${folder.id}"; +open.toString() }
                            }
                            button(classes = "zoom") { modeButton(folder.id); attributes["title"] = "${folder.title} mode"; icon(Icon.OPEN_IN_FULL, "") }
                        }
                    }
                }
            }
            // Then the other screens, in three groups: focusing, views over the tasks, what tasks are made with.
            fun FlowContent.link(path: String, label: String, icon: Icon) =
                a(href = path, classes = if (path == current.substringBefore('?')) "current" else null) { icon(icon, ""); +label }
            hr(classes = "nav-hr")
            nav(classes = "nav side-group") {
                link("/focus", Labels.FOCUS, Icon.CENTER_FOCUS)
                folderModeRow(nav)
            }
            hr(classes = "nav-hr")
            nav(classes = "nav side-group") {
                link("/calendar", Labels.CALENDAR, Icon.CALENDAR)
                link("/review", Labels.REVIEW, Icon.BAR_CHART)
            }
            hr(classes = "nav-hr")
            nav(classes = "nav side-group") {
                link("/templates", Labels.TEMPLATES, Icon.CONTENT_COPY)
                // Opens into the contexts, each filtering the list; with none yet, the page to add one.
                if (nav.contexts.isEmpty()) link("/contexts", Labels.CONTEXTS, Icon.ALTERNATE_EMAIL)
                else details(classes = "nav-group") {
                    id = "nav-contexts"
                    attributes["open"] = ""
                    summary { icon(Icon.ALTERNATE_EMAIL, ""); +Labels.CONTEXTS; span(classes = "n") { +"▾" } }
                    div(classes = "context-nav") { nav.contexts.forEach { contextLink(it, list, back = list?.path ?: current) } }
                }
            }
            div(classes = "side-foot") {
                a(href = "/syntax", classes = if (current == "/syntax") "current" else null) { +Labels.SYNTAX }
                a(href = "/settings", classes = "gear" + if (current == "/settings") " current" else "") {
                    attributes["aria-label"] = Labels.SETTINGS; attributes["title"] = Labels.SETTINGS
                    icon(Icon.SETTINGS, "")
                }
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

// Folder mode beside Focus: on, the mode (✕ leaves it); off, a menu of the top folders to enter one.
private fun FlowContent.folderModeRow(nav: SideNav) {
    val mode = nav.mode
    if (mode != null) div(classes = "nav-mode") {
        span(classes = "nav-mode-name") { icon(Icon.OPEN_IN_FULL, "", nav.colors[mode.id]); +"${mode.title} mode" }
        button(classes = "mode-leave") { modeButton(null); attributes["aria-label"] = "Leave ${mode.title} mode"; +"✕" }
    } else details(classes = "pp nav-mode-pick") {
        summary { icon(Icon.OPEN_IN_FULL, ""); +Labels.FOLDER_MODE; span(classes = "n") { +"▾" } }
        div(classes = "pop") {
            nav.topFolders.forEach { folder ->
                button(classes = "menu-row") {
                    modeButton(folder.id)
                    span(classes = "fdot") { nav.colors[folder.id]?.let { style = "background: $it" } }
                    +folder.title
                }
            }
        }
    }
}

private val KEYS = listOf(
    "n" to "jump to quick add",
    "/" to "search the list",
    "g, then d / a / t" to "go to Doing / Active / All",
    "Esc" to "leave a text box, close the task panel, or leave focus",
    "?" to "every shortcut, including the All tree's",
)

// Quick add's syntax, Discord's, and the keys: the page the sidebar's "Quick add syntax" opens.
fun HTML.syntaxPage(service: TaskService) = shellPage(service, "Raspberry · ${Labels.SYNTAX}", "/syntax") {
    div(classes = "contexts key") {
        h1 { +Labels.SYNTAX }
        fun rows(rows: List<Pair<String, String>>) = rows.forEach { (syntax, meaning) -> div { span(classes = "mono") { +syntax }; +" $meaning" } }
        rows(Labels.QUICK_ADD_SYNTAX)
        div(classes = "key-head") { +"Keys (not while typing)" }
        rows(KEYS)
        div(classes = "key-head") { +Labels.DISCORD_SYNTAX_HEAD }
        rows(Labels.DISCORD_SYNTAX)
    }
}
