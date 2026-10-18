package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.linkSegments
import com.kzhovn.todoapp.data.laterText
import com.kzhovn.todoapp.data.waitingBlocks
import com.kzhovn.todoapp.data.timeLeft
import com.kzhovn.todoapp.data.isTimedWait
import com.kzhovn.todoapp.data.ShownDue
import com.kzhovn.todoapp.repository.dimmedInTree
import com.kzhovn.todoapp.quickadd.typedType
import io.ktor.http.Parameters
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.notesMatch
import com.kzhovn.todoapp.data.folderTree
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.form
import kotlinx.html.input
import kotlinx.html.checkBoxInput
import kotlinx.html.dateInput
import kotlinx.html.select
import kotlinx.html.option
import com.kzhovn.todoapp.repository.contextHolds
import kotlinx.html.radioInput
import kotlinx.html.label
import kotlinx.html.hiddenInput
import kotlinx.html.details
import kotlinx.html.summary
import kotlinx.html.h2
import kotlinx.html.A
import kotlinx.html.BUTTON
import kotlinx.html.ButtonType
import com.kzhovn.todoapp.data.OutlinerNode
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
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.a
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
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.summary
import kotlinx.html.textInput
import kotlinx.html.title
import com.kzhovn.todoapp.data.agenda

internal fun typeIcon(type: TaskType) = when (type) {
    TaskType.PROJECT -> Icon.PROJECT
    TaskType.CHECKLIST -> Icon.CHECKLIST
    TaskType.FOLDER -> Icon.FOLDER
    TaskType.WAITING -> Icon.HOURGLASS
    else -> Icon.CIRCLE
}

// Quick add's Type: what was picked on it, until the text's own type (typedType) changes from what it was then (`over`).
internal fun quickAddType(params: Parameters, now: Long): TaskType {
    fun type(name: String) = Labels.TYPES.firstOrNull { it.first.name == params[name] }?.first
    val typed = typedType(params["text"].orEmpty(), now)
    return type("type")?.takeIf { type("over") == typed } ?: typed
}

// Quick add's Type chip and its menu, re-rendered as the text changes or a type is picked.
internal fun FlowContent.typeChip(shown: TaskType, typed: TaskType) = details(classes = "pp qa-type") {
    attributes["hx-get"] = "/quickadd/type"
    attributes["hx-trigger"] = "change, input changed delay:150ms from:#quickadd"
    attributes["hx-include"] = "closest form"
    attributes["hx-swap"] = "outerHTML"
    attributes["hx-select"] = ".qa-type"
    attributes["hx-target"] = "this" // not the form's list
    summary(classes = if (shown == TaskType.TASK) "pill" else "pill set") {
        attributes["title"] = Labels.TYPE
        icon(typeIcon(shown), "pill-icon")
        if (shown != TaskType.TASK) span { +Labels.typeName(shown) }
    }
    hiddenInput(name = "over") { value = typed.name }
    div(classes = "pop") {
        Labels.TYPES.forEach { (type, name) ->
            label(classes = "qa-opt") {
                radioInput(name = "type") { value = type.name; checked = type == shown }
                icon(typeIcon(type), "pill-icon")
                +name
            }
        }
    }
}

// The same colour families as the app (folderColorsArgb in :core), as CSS colours.
internal fun folderColorsHex(tasks: Collection<Task>): Map<Long, String> = folderColorsArgb(tasks).mapValues { "#%06X".format(it.value and 0xFFFFFF) }

// What leads a task's row, by type: a project's icon (ticked like a checkbox; done, it looks like one), a
// waiting item's hourglass in a checkbox's ring (ticked like one: Resolved), a checklist's "3/8"
// (ticked item by item, inside it; opens it), or a task's checkbox, which
// `check` sets up (what ticking it does differs by list).
internal fun FlowContent.taskMark(task: Task, counts: Pair<Int, Int>?, mode: String, check: BUTTON.() -> Unit) = when (task.type) {
    TaskType.PROJECT -> button(classes = "project") { check(); attributes["title"] = Labels.PROJECT; if (!task.isComplete) icon(Icon.PROJECT, "") }
    TaskType.WAITING -> button(classes = "project waiting-mark") { check(); attributes["title"] = Labels.RESOLVED; icon(Icon.HOURGLASS, "") }
    TaskType.SECTION -> span(classes = "project") {} // a checklist's section: nothing to tick
    TaskType.CHECKLIST -> {
        val (done, total) = counts ?: (0 to 0)
        a(href = "/tasks/${task.id}?mode=$mode", classes = "count") { attributes["title"] = Labels.CHECKLIST; +"$done/$total" }
    }
    else -> button(classes = "check") { check() }
}

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
    if (data.mode != ListMode.CALENDAR) listSearch(data)
    div { listContents(data) }
}

// The list's search box (/ jumps to it) and its filters, narrowing the list below as you type. The context filter
// is picked in the sidebar; here it's a pill that clears it.
private fun FlowContent.listSearch(data: ListData) = form(action = data.mode.path, method = FormMethod.get, classes = "list-search") {
    attributes["hx-get"] = "/list/${data.mode.name.lowercase()}"
    attributes["hx-trigger"] = "input changed delay:250ms, search, change"
    attributes["hx-target"] = "#list"
    attributes["hx-swap"] = "outerHTML"
    hiddenInput(name = "search") { value = "1" }
    data.folder?.takeIf { data.mode == ListMode.ALL }?.let { hiddenInput(name = "folder") { value = it.toString() } }
    data.search.context?.let { hiddenInput(name = "context") { value = it.toString() } }
    input(type = InputType.search, name = "q", classes = "search-q") {
        value = data.search.text; placeholder = "${Labels.SEARCH} ${data.title}… (/)"; attributes["autocomplete"] = "off"
    }
    data.contextId?.let { id ->
        val name = if (id == SearchFilters.NO_CONTEXT) Labels.NO_CONTEXT else data.contexts.firstOrNull { it.id == id }?.name
        val holds = data.contexts.firstOrNull { it.id == id }?.let { contextHolds(it, data.windows(it.id), data.now) } ?: true
        // Typed in the box, it goes with the text; picked, ✕ clears it.
        if (name != null) a(href = data.pathOf(data.mode, data.folder, data.search.copy(context = null)), classes = "pill set context-filter") {
            +(if (id == SearchFilters.NO_CONTEXT) name else Labels.contextFilter(name, holds)); if (data.search.context == id) +" ✕"
        }
    }
    val filtersSet = data.search.copy(text = "", context = null) != ListSearch() || (data.folder != null && data.mode != ListMode.ALL)
    details(classes = if (filtersSet) "pp filters set" else "pp filters") {
        summary(classes = "pill") { +"Filters ▾" }
        div(classes = "pop") {
            label { checkBoxInput(name = "starred") { checked = data.search.starred }; +" ${Labels.STARRED}" }
            // Doing and Active never hold completed tasks.
            if (data.mode == ListMode.ALL) label { checkBoxInput(name = "completed") { checked = data.search.completed }; +" ${Labels.COMPLETED}" }
            else select {
                name = "folder"
                option { value = ""; +"Any ${Labels.FOLDER.lowercase()}" }
                folderTree(data.all.filter { it.type == TaskType.FOLDER }).forEach { (f, depth) ->
                    option { value = f.id.toString(); selected = data.folder == f.id; +(INDENT.repeat(depth) + f.title) }
                }
            }
            span(classes = "date-filter") {
                +"Due "
                dateInput(name = "after") { value = data.search.after?.toString().orEmpty(); attributes["aria-label"] = Labels.DUE_AFTER }
                +"–"
                dateInput(name = "before") { value = data.search.before?.toString().orEmpty(); attributes["aria-label"] = Labels.DUE_BEFORE }
            }
        }
    }
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
    data.stalled?.let { stalledPrompt(it, data) }
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
                    hx("get", data.listUrl("fold=$sectionId"))
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
    if (data.moreInAll > 0) a(href = data.pathOf(ListMode.ALL), classes = "more-in-all") { +Labels.moreInAll(data.moreInAll) }
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
        // A search unfolds everything, to show what it found.
        val collapsed = hasChildren && !data.filtering && ((item.id in data.collapsed) != (item.type == TaskType.CHECKLIST))
        val outline: DIV.() -> Unit = {
            classes = classes + "node"
            attributes["tabindex"] = "0"
            attributes["draggable"] = "true"
            attributes["data-id"] = item.id.toString()
            attributes["data-parent"] = parentId?.toString().orEmpty()
            attributes["data-depth"] = depth.toString()
            attributes["data-kind"] = item.type.name.lowercase()
            if (hasChildren) attributes["data-collapsed"] = collapsed.toString()
            chevron(item.id, hasChildren, collapsed, data)
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

private fun FlowContent.chevron(id: Long, hasChildren: Boolean, collapsed: Boolean, data: ListData) {
    if (!hasChildren) return span(classes = "chevron") {}
    button(classes = "chevron") {
        hx("get", data.listUrl("toggle=$id"))
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
        if (data.onlyAParent(task)) classes = classes + "parent-only"
        style = "padding-left: ${depth * 18}px; border-left-color: ${data.folderColor(task) ?: "var(--border)"}"
        outlineNode?.invoke(this)
        // A timed wait's end shows as its own tail ("32m left"), not as a due date.
        val due = data.effectiveDue(task)?.takeUnless { task.isComplete || task.isTimedWait }
        val status = due?.let { dueStatus(it.at, data.now) }
        taskMark(task, data.subtaskCounts(task), mode) {
            // Due today: an orange ring; overdue: rust. Each with a pale fill of its own colour.
            when (status) { DueStatus.OVERDUE -> classes = classes + "overdue"; DueStatus.TODAY -> classes = classes + "today"; else -> {} }
            // A search for completed ones shows them in All, to reopen.
            hx("post", "/tasks/${task.id}/${if (task.isComplete) "uncomplete" else "complete"}?mode=$mode")
            attributes["aria-label"] = if (task.isComplete) "Mark not done" else "Complete"
        }
        div(classes = "main") {
            div(classes = "title") {
                // In the All tree indentation already shows nesting; flat lists say "Parent: subtask".
                // The parent part opens the parent's editor, as the title opens the subtask's.
                if (data.mode != ListMode.ALL) data.parentTitle(task)?.let { a(href = "/tasks/${task.parentId}?mode=$mode", classes = "parent") { openInPanel(); +"$it: " } }
                a(href = "/tasks/${task.id}?mode=$mode", classes = "edit") { openInPanel(); +task.title }
                if (task.isHighPriority) span(classes = "priority-high") { attributes["title"] = "High priority"; +"!" }
                if (task.alongsideNext && data.byId[task.parentId]?.sequential == true) span(classes = "alongside") { attributes["title"] = Labels.ALONGSIDE_NEXT; +Labels.ALONGSIDE_MARK }
                // On the title's line, wrapping along with it.
                due?.let { dueTail(it, status!!, data.now) }
                // A start still to come (the All tree shows snoozed and future tasks); a waiting item's own line.
                (tag ?: laterText(task, data.now))?.let { span(classes = "tail") { +" · $it" } }
                if (task.type != TaskType.CHECKLIST) data.subtaskCounts(task)?.let { (done, total) -> span(classes = "tail") { +" · $done/$total" } }
                if (task.recurrenceType != null) span(classes = "badge") { attributes["title"] = "Recurring"; icon(Icon.REPEAT, "") }
                if (!task.notes.isNullOrBlank()) span(classes = "has-notes") { attributes["title"] = Labels.HAS_NOTES; icon(Icon.NOTES, "") }
            }
            // Found by its notes: the line that matched, under the title.
            notesMatch(task, data.searchText)?.let { div(classes = "notes-line") { +it } }
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
                // A step of an in-order parent: whether the next one waits for it (Workflows.kt).
                if (data.byId[task.parentId]?.sequential == true) button(classes = "menu-row") {
                    hx("post", "/tasks/${task.id}/alongside?mode=$mode")
                    icon(Icon.REDO, "")
                    +(if (task.alongsideNext) Labels.HOLD_UP else Labels.DONT_HOLD_UP)
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

// The phone's "all subtasks done" prompt: complete the project, add its next step, or not now.
private fun FlowContent.stalledPrompt(project: Task, data: ListData) = div(classes = "stalled") {
    val m = data.q
    span { +"${Labels.allSubtasksDone(project.title)}. ${Labels.IS_PROJECT_COMPLETE}" }
    button(classes = "primary") { hx("post", "/tasks/${project.id}/complete?mode=$m"); +Labels.COMPLETE_PROJECT }
    form(classes = "inline") {
        hx("post", "/tasks/${project.id}/next?mode=$m")
        textInput(name = "text") { placeholder = "${Labels.ADD_NEXT}…"; attributes["autocomplete"] = "off" }
    }
    button { hx("get", data.listUrl("later=${project.id}")); +Labels.LATER }
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
