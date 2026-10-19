package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.ancestors
import kotlinx.html.ButtonType
import com.kzhovn.todoapp.data.eventWhen
import kotlinx.html.summary
import kotlinx.html.details
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.subtaskCounts
import com.kzhovn.todoapp.data.LinkKind
import com.kzhovn.todoapp.data.checklistGroups
import com.kzhovn.todoapp.data.moveOutFolderId
import com.kzhovn.todoapp.server.TaskService
import com.kzhovn.todoapp.sync.dependsOn
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.radioInput
import kotlinx.html.span
import kotlinx.html.textInput
import kotlinx.serialization.json.add

internal val LINK_KINDS = mapOf("subtask" to LinkKind.SUBTASK, "prerequisite" to LinkKind.PREREQUISITE, "dependent" to LinkKind.DEPENDENT)
internal val KIND_NOUNS = mapOf("subtask" to "subtask", "prerequisite" to "prerequisite", "dependent" to "dependent task")

// A prerequisite's (amber) or dependent's (green) tinted tag.
private fun FlowContent.kindTag(kind: String) =
    span(classes = "kind-tag ${if (kind == Labels.DEPENDENT) "dependent" else "prerequisite"}") { +kind }

// Related tasks: subtasks, prerequisites (what this depends on) and dependents (what depends on
// it), each added or unlinked at once via htmx. Filled into a div by the caller so the fragment's
// root is #related, which htmx swaps.
// focusAddItem: after adding items, the new field is focused again, for typing a list in one go.
// `folded`: the checklist sections folded in this browser (the All tree's folds cookie).
// `addingTo`: the section whose + is open (its add field under its heading).
fun DIV.relatedSection(service: TaskService, id: Long, mode: ListMode, focusAddItem: Boolean = false, addKind: String = "subtask", folded: Set<Long> = emptySet(), addingTo: Long? = null) {
    this.id = "related"
    classes = setOf("related")
    val all = service.tasksAround(id)
    val byId = all.associateBy { it.id }
    val task = byId[id] ?: return
    val m = mode.name
    fun kotlinx.html.HTMLTag.htmx(url: String) = hx("post", url, "#related")
    // A subtask's checkbox goes in front of its title, as every checkbox does; other rows keep its space.
    fun FlowContent.row(kind: String, t: Task, leading: FlowContent.() -> Unit = { span(classes = "check-space") {} }, trailing: FlowContent.() -> Unit) = div(classes = "rel-row") {
        // An event: its calendar's dot and its time, and it opens its sheet, not the editor.
        if (t.type == TaskType.EVENT) {
            val event = t.eventId?.let(service::event)
            span(classes = "check-space") { event?.let { calDot(calendarColor(service, it.calendarId)) } }
            a(href = event?.let { eventHref(it.id, m) } ?: "#", classes = if (t.isComplete) "done" else null) {
                +t.title
                span(classes = "tail") { +" · ${event?.let { eventWhen(it, service.now()) } ?: Labels.EVENT}" }
                kindTag(kind)
            }
        } else {
            leading()
            a(href = "/tasks/${t.id}?mode=$m", classes = if (t.isComplete) "done" else null) { +t.title; kindTag(kind) }
        }
        trailing()
    }
    fun FlowContent.unlink(url: String) = button(classes = "unlink") { htmx(url); attributes["aria-label"] = "Remove"; +"✕" }

    // Its mark goes in front of its title, as in the lists (taskMark); ticking it toggles it here.
    val counts = subtaskCounts(all)
    fun FlowContent.check(t: Task) = taskMark(t, counts[t.id], m) {
        if (t.isComplete) classes = classes + "done"
        htmx("/tasks/$id/subtasks/${t.id}/toggle?mode=$m")
        attributes["aria-label"] = if (t.isComplete) "Mark not done" else "Complete"
        if (t.isComplete) icon(Icon.CHECK, "")
    }
    // A subtask's or an item's row: dragged by its handle (app.js) or moved with Alt+Up/Down, ticked, opened
    // (to edit it), a maybe marked "?"; `note` tags one that's also a prerequisite or dependent.
    fun FlowContent.childRow(child: Task, note: String? = null, inSection: Boolean = false, trailing: FlowContent.() -> Unit) = div(classes = if (inSection) "rel-row sub in-section" else "rel-row sub") {
        attributes["data-sub"] = child.id.toString()
        attributes["data-task"] = id.toString()
        span(classes = "drag-handle") { attributes["draggable"] = "true"; attributes["title"] = "Drag to reorder"; +"⠿" }
        check(child)
        a(href = "/tasks/${child.id}?mode=$m", classes = if (child.isComplete) "done" else if (child.isMaybe) "is-maybe" else null) {
            +child.title
            if (child.isMaybe && !child.isComplete) span(classes = "maybe-mark") { +"?" }
            note?.let { kindTag(it) }
        }
        trailing()
    }

    val isChecklist = task.type == TaskType.CHECKLIST
    if (isChecklist) {
        // Items: as subtasks are, open ones first; loose ones, then each section (Clothes, Winter) with its
        // heading (fold, count, ⋯) and its own add field. One list for app.js's drag, so an item drags into
        // another section (planMoveItem). ✕ deletes an item, the ⋯ menu a section, either with Undo.
        div(classes = "field-label section") { +Labels.ITEMS }
        val groups = checklistGroups(id, all)
        val items = groups.flatMap { it.second }
        fun FlowContent.addForm(placeholder: String, into: Task?, focus: Boolean) = form(classes = if (into != null) "inline add-item in-section" else "inline add-item") {
            htmx("/tasks/$id/items?mode=$m" + (into?.let { "&in=${it.id}" } ?: ""))
            textInput(name = "text") { this.placeholder = placeholder; attributes["autocomplete"] = "off"; if (focus) autoFocus = true }
        }
        if (groups.any { it.first != null || it.second.isNotEmpty() }) div(classes = "items") { groups.forEach { (section, sectionItems) ->
            val isFolded = section != null && section.id in folded
            section?.let { sec ->
                div(classes = if (isFolded) "rel-row sub section-head folded" else "rel-row sub section-head") {
                    attributes["data-sub"] = sec.id.toString()
                    attributes["data-task"] = id.toString()
                    button(classes = "fold") { htmx("/tasks/$id/sections/${sec.id}/fold?mode=$m"); attributes["aria-label"] = if (isFolded) "Expand" else "Collapse"; +(if (isFolded) "▸" else "▾") }
                    span(classes = "section-title") { +sec.title }
                    counts[sec.id]?.let { (done, total) -> span(classes = "tail") { +" $done/$total" } }
                    button(classes = "section-add") { htmx("/tasks/$id/sections/${sec.id}/adding?mode=$m"); attributes["aria-label"] = Labels.addTo(sec.title); attributes["title"] = Labels.addTo(sec.title); +"+" }
                    // The rest opens on right-click (app.js), as a row's menu does.
                    details(classes = "pp section-menu") {
                        summary(classes = "unlink") { attributes["aria-label"] = "Section menu"; +"⋯" }
                        div(classes = "pop") {
                            form(classes = "menu-item rename") {
                                htmx("/tasks/$id/sections/${sec.id}/rename?mode=$m")
                                textInput(name = "text") { value = sec.title; attributes["aria-label"] = Labels.RENAME }
                            }
                            button(classes = "menu-item") { htmx("/tasks/$id/sections/${sec.id}/check?mode=$m"); +Labels.checkAllIn(sec.title) }
                            button(classes = "menu-item") { htmx("/tasks/$id/sections/${sec.id}/uncheck?mode=$m"); +Labels.uncheckAllIn(sec.title) }
                            button(classes = "menu-item danger") { htmx("/tasks/$id/subtasks/${sec.id}/delete?mode=$m"); +Labels.DELETE_SECTION }
                        }
                    }
                }
            }
            if (section != null && section.id == addingTo) addForm(Labels.addTo(section.title), section, focus = true)
            if (!isFolded) {
                sectionItems.forEach { item ->
                    childRow(item, inSection = section != null) {
                        button(classes = "unlink") { htmx("/tasks/$id/subtasks/${item.id}/delete?mode=$m"); attributes["aria-label"] = Labels.DELETE; +"✕" }
                    }
                }
            }
        } }
        // "+ Add item" and "+ Add section" side by side.
        div(classes = "add-row") {
            addForm("+ ${Labels.ADD_ITEM}", null, focusAddItem)
            form(classes = "inline add-item add-section") {
                htmx("/tasks/$id/sections?mode=$m")
                textInput(name = "text") { placeholder = Labels.ADD_SECTION; attributes["autocomplete"] = "off" }
            }
        }
        if (items.any { it.isComplete }) div(classes = "adders") {
            button(classes = "add-link") { htmx("/tasks/$id/items/uncheck?mode=$m"); +Labels.UNCHECK_ALL }
            button(classes = "add-link") {
                htmx("/tasks/$id/items/clear?mode=$m")
                attributes["hx-confirm"] = Labels.clearChecked(items.count { it.isComplete })
                +Labels.CLEAR_CHECKED
            }
        }
    }
    div(classes = "field-label section") { +Labels.RELATED }
    val edges = service.dependencyEdges()
    // A folder can't be completed, so it neither depends on tasks nor has any depending on it.
    val prerequisites = if (task.type == TaskType.FOLDER) emptyList() else service.dependsOn(id).mapNotNull(byId::get).sortedBy { it.title.lowercase() }
    val dependents = if (task.type == TaskType.FOLDER) emptyList() else edges.filter { it.dependsOnTaskId == id }.mapNotNull { byId[it.taskId] }
    val subtasks = all.filter { it.parentId == id && !isChecklist }.sortedWith(TaskOrder)
    val outFolder = moveOutFolderId(task, byId)?.let { byId[it]?.title }
    // Subtasks first, together on a faint tile; then prerequisites and dependents, tagged after their titles
    // (as a subtask that's also one is).
    if (subtasks.isNotEmpty()) div(classes = "sub-tile") { subtasks.forEach { sub ->
        val note = when (sub) { in prerequisites -> Labels.PREREQUISITE; in dependents -> Labels.DEPENDENT; else -> null }
        childRow(sub, note) {
            // ✕: move it out, unlink it (it stays a subtask), both, or delete it.
            details(classes = "pp unlink-menu") {
                summary(classes = "unlink") { attributes["aria-label"] = "Remove"; +"✕" }
                div(classes = "pop") {
                    button(classes = "menu-item") { htmx("/tasks/$id/subtasks/${sub.id}/moveout?mode=$m"); +Labels.moveOutTo(outFolder) }
                    if (sub in prerequisites) button(classes = "menu-item") { htmx("/tasks/$id/prerequisite/${sub.id}/remove?mode=$m"); +Labels.NOT_PREREQUISITE }
                    if (sub in dependents) button(classes = "menu-item") { htmx("/tasks/$id/dependent/${sub.id}/remove?mode=$m"); +Labels.NOT_DEPENDENT }
                    if (sub in prerequisites || sub in dependents) button(classes = "menu-item") { htmx("/tasks/$id/subtasks/${sub.id}/moveout?mode=$m&unlink=1"); +Labels.MOVE_OUT_AND_UNLINK }
                    button(classes = "menu-item danger") {
                        htmx("/tasks/$id/subtasks/${sub.id}/delete?mode=$m")
                        attributes["hx-confirm"] = "Delete “${sub.title}”?"
                        +Labels.DELETE_TASK.removeSuffix("…")
                    }
                }
            }
        }
        // One level further, indented; anything deeper is only the "1/3" count on the row it's under.
        all.filter { it.parentId == sub.id }.sortedWith(TaskOrder).forEach { child ->
            div(classes = "rel-row nested") {
                check(child)
                a(href = "/tasks/${child.id}?mode=$m", classes = if (child.isComplete) "done" else null) {
                    +child.title
                    if (child.type != TaskType.CHECKLIST) counts[child.id]?.let { (done, total) -> span(classes = "tail") { +" $done/$total" } }
                }
            }
        }
    } }
    (prerequisites - subtasks.toSet()).forEach { p -> row(Labels.PREREQUISITE, p, leading = { check(p) }) { unlink("/tasks/$id/prerequisite/${p.id}/remove?mode=$m") } }
    (dependents - subtasks.toSet()).forEach { d -> row(Labels.DEPENDENT, d, leading = { check(d) }) { unlink("/tasks/$id/dependent/${d.id}/remove?mode=$m") } }

    // One field adds any kind: type a new task (Enter), or pick a suggestion (↓, or a click). The
    // kind switch says what to add; a checklist only links, a folder only takes subtasks.
    val kinds = buildList {
        if (!isChecklist) add("subtask" to Labels.SUBTASK)
        if (task.type != TaskType.FOLDER) { add("prerequisite" to Labels.PREREQUISITE); add("dependent" to Labels.DEPENDENT) }
    }
    if (kinds.isNotEmpty()) addRelatedForm("/tasks/$id/related?mode=$m", "/tasks/$id/related/suggest", "#related", kinds, addKind, focusAddItem)
}

// The add field (the editor's Related, an event's sheet): type a new task (Enter), or pick one of `suggest`'s
// suggestions (↓, or a click); `kinds` say what to add.
internal fun FlowContent.addRelatedForm(post: String, suggest: String, target: String, kinds: List<Pair<String, String>>, kind: String?, focus: Boolean) =
    form(classes = "add-related") {
        hx("post", post, target)
        attributes["autocomplete"] = "off"
        div(classes = "add-field") {
            textInput(name = "text") {
                placeholder = "+"
                attributes["aria-label"] = "Add a task, or link one"
                attributes["hx-get"] = suggest
                // Not on "change": that fires when ↓ leaves the field, and the refresh would drop the suggestion just focused.
                attributes["hx-trigger"] = "input changed delay:200ms, kindchange, focus"
                attributes["hx-target"] = "next .suggest"
                attributes["hx-swap"] = "outerHTML"
                attributes["hx-include"] = "closest form"
                if (focus) autoFocus = true
            }
            div(classes = "kind-seg") {
                val selected = kinds.firstOrNull { it.first == kind }?.first ?: kinds.first().first
                kinds.forEach { (value, label) ->
                    label { radioInput(name = "kind") { this.value = value; checked = value == selected }; +label }
                }
            }
        }
        div(classes = "suggest") {}
    }

// The add field's suggestions: "↵ New <noun>" for what's typed, then existing tasks under their headings.
internal fun FlowContent.suggestTasks(q: String, newNoun: String, sections: List<Pair<String?, List<Task>>>, byId: Map<Long, Task>) {
    if (q.isNotEmpty()) button(type = ButtonType.submit, classes = "sugg new") { +"↵ New $newNoun “$q”" }
    sections.forEach { (label, matches) ->
        label?.let { div(classes = "sugg-head") { +it } }
        matches.forEach { t ->
            button(type = ButtonType.submit, classes = "sugg") {
                name = "existing"; value = t.id.toString()
                span { +t.title }
                span(classes = "where") { +ancestors(t, byId).joinToString(" › ") { it.title } }
            }
        }
    }
}
