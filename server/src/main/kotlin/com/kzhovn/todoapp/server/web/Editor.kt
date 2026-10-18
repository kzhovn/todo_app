package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.DAY_MS
import com.kzhovn.todoapp.data.monthDay
import com.kzhovn.todoapp.data.dueText
import com.kzhovn.todoapp.data.resolvesText
import com.kzhovn.todoapp.data.isTimedWait
import com.kzhovn.todoapp.repository.stepAfter
import com.kzhovn.todoapp.data.reminderSummary
import com.kzhovn.todoapp.data.beforeDueText
import com.kzhovn.todoapp.recurrence.ordinal
import com.kzhovn.todoapp.recurrence.WEEKDAY_NAMES
import com.kzhovn.todoapp.recurrence.NTH_NAMES
import com.kzhovn.todoapp.recurrence.recurrencePresets
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import com.kzhovn.todoapp.data.Task
import kotlinx.html.summary
import kotlinx.html.details
import com.kzhovn.todoapp.data.formatDuration
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.hasTime
import com.kzhovn.todoapp.data.clockTime
import com.kzhovn.todoapp.data.localDay
import com.kzhovn.todoapp.data.zoned
import com.kzhovn.todoapp.data.chipDate
import com.kzhovn.todoapp.data.ancestors
import com.kzhovn.todoapp.data.DueChoice
import com.kzhovn.todoapp.data.SnoozeChoice
import com.kzhovn.todoapp.data.folderTree
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.CHECK_IN_PRESETS
import com.kzhovn.todoapp.data.MAX_CHECK_IN_DAYS
import com.kzhovn.todoapp.data.nextCheckIn
import com.kzhovn.todoapp.data.checkInEvery
import com.kzhovn.todoapp.data.checkInText
import com.kzhovn.todoapp.data.stillWaiting
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.RecurrenceUnit
import com.kzhovn.todoapp.recurrence.toTaskFields
import com.kzhovn.todoapp.repository.InheritedField
import com.kzhovn.todoapp.server.TaskService
import com.kzhovn.todoapp.sync.SyncJson
import com.kzhovn.todoapp.sync.SyncRow
import com.kzhovn.todoapp.sync.TASKS
import com.kzhovn.todoapp.sync.contextIds
import com.kzhovn.todoapp.sync.dependsOn
import com.kzhovn.todoapp.sync.taskFields
import com.kzhovn.todoapp.sync.toTask
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
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.numberInput
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.radioInput
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.textArea
import kotlinx.html.textInput
import kotlinx.html.timeInput
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// The task editor: one plain form for the task's own fields, plus htmx actions for its subtasks and
// dependents, which (like the phone's) take effect immediately.

internal class EditState(val task: Task, val contextIds: Set<Long>, val dependsOn: Set<Long>) {
    // Travels in the form as the task was when the page loaded, so a save applies only what the
    // user changed and doesn't undo edits synced from the phone in the meantime.
    fun encode() = taskFields(task, contextIds, dependsOn).toString()
}

internal fun decodeState(id: Long, json: String?): EditState? = runCatching {
    val row = SyncRow(TASKS, id, SyncJson.parseToJsonElement(json!!).jsonObject)
    EditState(row.toTask(), row.contextIds(), row.dependsOn())
}.getOrNull()

internal fun TaskService.editState(id: Long): EditState? =
    get(id)?.let { EditState(it, contextIdsByTask()[id].orEmpty(), dependsOn(id)) }

internal data class EditorView(
    val mode: ListMode,
    val list: ListData, // shown beside the editor; also where Save, Cancel and Delete return to
    val shown: EditState,
    val recurrence: RecurrenceSelection,
    val base: String,
    val error: String? = null,
    // Subtasks with their own value for changed inherited fields: "update them too?"
    val ask: Pair<Int, Set<InheritedField>>? = null,
    val needFirstStep: Boolean = false,
    val firstStep: String = "",
    val newSubtasks: String = "", // a new task's subtasks, one per line, created with it
    val newDep: String = "", // a new task this one depends on
    val newDependent: String = "", // a new task that depends on this one
    val pin: Boolean = false, // a new task's pin, applied when it's saved
    val focusAdd: Boolean = false // ?add=1: straight to adding a subtask (a project quick add just made)
)

// A preset's form values, which app.js copies into the builder's fields.
private fun presetFields(r: RecurrenceSelection): String = buildJsonObject {
    put("repeat", r.preset.name); put("n", r.n.toString()); put("unit", r.unit.name)
    putJsonArray("wd") { (0..6).filter { r.weekdaysMask and (1 shl it) != 0 }.forEach { add(it.toString()) } }
    put("monthly", if (r.monthlyNth == null) "day" else "nth"); put("ends", "never")
}.toString()

// "Next: Sat Oct 3 · Sat Nov 7 · Sat Dec 5"; re-rendered (hx) on every change in the popover. It
// also carries the pill's text (data-summary), read by app.js.
internal fun FlowContent.repeatPreview(r: RecurrenceSelection, anchor: Long, now: Long) = div(classes = "rep-preview") {
    attributes["hx-get"] = "/repeat-preview"
    attributes["hx-trigger"] = "change from:closest .pop, input changed delay:300ms from:closest .pop"
    attributes["hx-include"] = "closest form"
    attributes["hx-swap"] = "outerHTML"
    attributes["hx-sync"] = "this:replace" // a newer change wins over an in-flight one
    attributes["data-summary"] = Labels.repeat(r).orEmpty()
    val (type, rule) = r.toTaskFields()
    val next = if (type == null || rule == null) emptyList() else RecurrenceEngine.preview(type, rule, anchor, now)
    span(classes = "menu-label") { +Labels.repeatPreviewLabel(afterCompletion = r.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS) }
    +Labels.repeatPreview(next)
}

// A date and time input's values ("2026-10-07", "17:30"); no time: an empty one (see hasTime).
private fun dateAndTime(millis: Long): Pair<String, String> = zoned(millis).let {
    it.toLocalDate().toString() to if (hasTime(millis)) it.toLocalTime().withSecond(0).withNano(0).toString() else ""
}

// A note, collapsed (5 lines; 2 on a phone's screen) with links that open; clicking it edits the whole
// note (app.js). With no note it's only shown while the title card is being edited (CSS).
private fun FlowContent.notesField(notes: String?) = div(classes = "notes") {
    if (!notes.isNullOrBlank()) div(classes = "notes-preview") { linkified(notes) }
    textArea(classes = "notes-input") { name = "notes"; rows = "1"; placeholder = "Notes"; +notes.orEmpty() }
}

internal fun HTML.editorPage(service: TaskService, v: EditorView) = shellPage(
    service, "Raspberry · ${v.shown.task.title.ifBlank { "New ${v.shown.task.type.name.lowercase()}" }}", v.list.path, v.list,
    detail = { editorPanel(service, v) }
) { listColumn(v.list) }

// The editor, in the detail panel beside the list. ✕ (or Esc) closes it, back to the list alone.
private fun FlowContent.editorPanel(service: TaskService, v: EditorView) {
    a(href = v.list.path, classes = "detail-close") { attributes["aria-label"] = "Close"; +"✕" }
    val t = v.shown.task
    val isNew = t.id == 0L
    val all = service.tasksAround(t.id)
    val byId = all.associateBy { it.id }
    val formId = "editor-form"
    // An open task saves itself a moment after each change (see /autosave); the form still submits
    // normally on Save, so this lives on its own element.
    if (!isNew) div {
        attributes["hx-post"] = "/tasks/${t.id}/autosave?mode=${v.list.q}"
        attributes["hx-trigger"] = "change delay:700ms from:.editor, change delay:700ms from:.editor-foot, input changed delay:1200ms from:.title-input, input changed delay:1200ms from:.notes-input"
        attributes["hx-include"] = "#$formId"
        attributes["hx-swap"] = "none"
        attributes["hx-sync"] = "this:replace"
    }
    form(action = "/tasks/${if (isNew) "new" else t.id}?mode=${v.list.q}", method = FormMethod.post, classes = "editor") {
        id = formId
        // Enter in a field "clicks" the form's first submit button. Without this one that's Delete (tied to
        // the form from the footer below), so Enter deleted the task; now it saves.
        button(type = ButtonType.submit, classes = "default-submit") { attributes["tabindex"] = "-1"; attributes["aria-hidden"] = "true"; +Labels.SAVE }
        hiddenInput(name = "base") { id = "base"; value = v.base }
        v.error?.let { p(classes = "error") { +it } }
        if (t.isTemplate && !isNew) templateBanner(t)
        v.ask?.let { (count, fields) ->
            div(classes = "ask") {
                p { +Labels.subtasksOverride(count, fields.joinToString(" and ") { it.label }) }
                button(type = ButtonType.submit, classes = "primary") { name = "inherit"; value = "update"; +Labels.UPDATE_SUBTASKS }
                button(type = ButtonType.submit) { name = "inherit"; value = "only"; +Labels.ONLY_THIS_TASK }
            }
        }

        // The title wraps (up to four lines), with the star inside the box by the first line. A starred
        // task is never a maybe (Maybe is under Properties); app.js unticks the other when one is ticked.
        // The note sits under it, in the same card.
        // Where it lives, outermost first; each step opens that folder or task.
        if (!isNew) ancestors(t, byId).takeIf { it.isNotEmpty() }?.let { path ->
            div(classes = "crumbs") { path.forEach { step -> a(href = "/tasks/${step.id}?mode=${v.list.q}") { +step.title }; span(classes = "sep") { +"›" } } }
        }
        div(classes = "title-card") {
        div(classes = "title-box") {
            // An existing task pins at once, like the phone's; a new one when it's saved.
            if (isNew) label(classes = "pin-toggle") { attributes["title"] = Labels.PIN; checkBoxInput(name = "pin") { checked = v.pin }; icon(Icon.PUSH_PIN, "") }
            else if (t.type.isDoable && !t.isComplete) {
                pinControl(t, service.pinned()?.id == t.id)
            }
            textArea(classes = "title-input") { name = "title"; rows = "1"; placeholder = Labels.TITLE; required = true; +t.title }
            label(classes = "flag-toggle star-toggle task-only not-waiting") {
                attributes["title"] = Labels.STAR
                checkBoxInput(name = "starred") { checked = t.isStarred }
                icon(Icon.STAR, "")
            }
        }
        notesField(t.notes)
        }

        // One joined bar, the chosen type filled: a pick-one set, unlike the pills below.
        div(classes = "type-seg") {
            Labels.TYPES.forEach { (type, label) ->
                label { radioInput(name = "type") { value = type.name; checked = t.type == type }; +label }
            }
        }

        // A waiting item's timing instead (CSS shows one or the other by the type picked): how often it comes
        // up for a check-in, or the day it resolves itself.
        div(classes = "field-label section waiting-only") { +Labels.WAITING }
        div(classes = "pills waiting-only") {
            val every = t.checkInEvery()
            // Presets fill the line below (app.js's .rep-preset handler) and close; changing the line moves the
            // next check-in to that far from today (app.js, as withCheckInEvery).
            popPill(Labels.CHECK_IN, Icon.REPEAT, Labels.checkIn(every), "checkin", "") {
                div(classes = "rep") {
                    val today = localDay(service.now())
                    div(classes = "rep-presets") {
                        CHECK_IN_PRESETS.forEach { days ->
                            val (n, unit) = if (days % 7 == 0) days / 7 to 7 else days to 1
                            button(type = ButtonType.button, classes = if (days == every) "rep-preset on" else "rep-preset") {
                                attributes["data-days"] = days.toString()
                                attributes["data-set"] = buildJsonObject { put("checkInN", "$n"); put("checkInUnit", "$unit"); put("checkInDate", "${today.plusDays(days.toLong())}") }.toString()
                                +Labels.checkIn(days)
                                span(classes = "rep-when") { +monthDay(service.now() + days * DAY_MS) }
                            }
                        }
                    }
                    div(classes = "rep-line") {
                        val weeks = every % 7 == 0
                        +"Every"
                        numberInput(name = "checkInN", classes = "rep-n") { value = (if (weeks) every / 7 else every).toString(); min = "1"; max = MAX_CHECK_IN_DAYS.toString() }
                        label(classes = "pill") { radioInput(name = "checkInUnit") { value = "1"; checked = !weeks }; +"days" }
                        label(classes = "pill") { radioInput(name = "checkInUnit") { value = "7"; checked = weeks }; +"weeks" }
                    }
                    label(classes = "rep-line") {
                        +Labels.NEXT_CHECK_IN
                        dateInput(name = "checkInDate") { value = localDay(nextCheckIn(t, service.now())).toString() }
                    }
                }
            }
            datePill(Labels.RESOLVES_ON, "resolves", Icon.CALENDAR, t.dueDate.takeIf { t.type == TaskType.WAITING }, "")
            // A timed wait (a workflow's 50 minutes): it resolves itself that long after it starts. Comes before the
            // task's own Timer pill, which parseForm relies on to tell the two "duration" fields apart.
            timerPill(t, "")
            if (t.isTimedWait && t.dueDate != null) togglePill("waitRemind", t.remindAt != null, Icon.BELL, Labels.REMIND_WHEN_DONE)
        }
        if (t.type == TaskType.WAITING && !isNew) p(classes = "hint waiting-only") {
            val now = service.now()
            val resolves = resolvesText(t)
            span { +(resolves ?: checkInText(t, now)) }
            // Restarts the clock: app.js moves the next check-in an interval from today, saved like any edit.
            if (resolves == null) button(type = ButtonType.button, classes = "still-waiting") {
                val next = stillWaiting(t, now)
                attributes["data-date"] = localDay(nextCheckIn(next, now)).toString()
                attributes["data-hint"] = checkInText(next, now)
                +Labels.STILL_WAITING
            }
        }
        // Everything about when, as pills that open a small popover (app.js keeps their text current).
        div(classes = "field-label section not-waiting") { +Labels.TIMING }
        div(classes = "pills when not-waiting") {
            // Quick choices under the fields: the snooze menu's for Start, days for Due (the phone's long press).
            val now = service.now()
            fun at(ms: Long) = dateAndTime(ms)
            datePill(Labels.START, "start", Icon.CALENDAR, t.startDate, "", SnoozeChoice.entries.map { it.label to at(it.at(now, service.rolloverHour())) })
            datePill(Labels.DUE, "due", Icon.FLAG, t.dueDate, "task-only", DueChoice.entries.map { it.label to at(it.at(now)) })
            // Set here, rung by the phone: when it starts, before it's due, and/or at a time of its own. A
            // date with no time rings at the reminder hour in Settings.
            popPill(Labels.REMIND, Icon.BELL, reminderSummary(t, service.reminderHour()), "remind", "task-only reminder") {
                label { checkBoxInput(name = "remindStart") { checked = t.remindAtStart }; +" When it starts" }
                div(classes = "pop-label") { +"Before it's due" }
                select {
                    name = "reminder"
                    Labels.REMINDERS.forEach { (m, label) -> option { value = m?.toString().orEmpty(); selected = t.reminderOffsetMinutes == m; m?.let { attributes["data-short"] = beforeDueText(it) }; +label } }
                }
                div(classes = "pop-label") { +"At a time" }
                val (atDate, atTime) = t.remindAt?.let(::dateAndTime) ?: ("" to "")
                dateInput(name = "remindAtDate") { value = atDate }
                timeInput(name = "remindAtTime") { value = atTime }
                p(classes = "hint") { +"Rings on your phone. A date with no time rings at ${clockTime(service.reminderHour() * 60)} (Settings)." }
            }
            val r = v.recurrence
            // Presets on top (one click); "After completion…" and "Custom…" open the builder below.
            // The preview and the pill's text come from /repeat-preview, so the wording is Labels.repeat's.
            val anchor = t.startDate ?: t.dueDate ?: service.now()
            val presets = recurrencePresets(anchor)
            val building = r.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS || (r.preset == RecurrencePreset.CALENDAR && presets.none { it.second == r })
            popPill(Labels.REPEAT, Icon.REPEAT, Labels.repeat(r) ?: t.recurrenceType?.let { "Custom" }, "repeat", "repeat task-only") {
                div(classes = if (building) "rep building" else "rep") {
                    div(classes = "rep-presets") {
                        (listOf(Labels.DONT_REPEAT to RecurrenceSelection(RecurrencePreset.NONE)) + presets).forEach { (label, preset) ->
                            button(type = ButtonType.button, classes = if (preset == r) "rep-preset on" else "rep-preset") { attributes["data-set"] = presetFields(preset); +label }
                        }
                        button(type = ButtonType.button, classes = "rep-preset rep-open") {
                            attributes["data-set"] = presetFields(if (r.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS) r else RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 3))
                            +"${Labels.AFTER_COMPLETION}…"
                        }
                        button(type = ButtonType.button, classes = "rep-preset rep-open") {
                            attributes["data-set"] = presetFields(if (r.preset == RecurrencePreset.CALENDAR) r else RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.WEEK))
                            +"Custom…"
                        }
                    }
                    div(classes = "rep-builder") {
                        div(classes = "seg") {
                            label(classes = "rep-none") { radioInput(name = "repeat") { value = RecurrencePreset.NONE.name; checked = r.preset == RecurrencePreset.NONE } }
                            label { radioInput(name = "repeat") { value = RecurrencePreset.CALENDAR.name; checked = r.preset == RecurrencePreset.CALENDAR }; +Labels.ON_A_SCHEDULE }
                            label { radioInput(name = "repeat") { value = RecurrencePreset.AFTER_COMPLETION_N_DAYS.name; checked = r.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS }; +Labels.AFTER_COMPLETION }
                        }
                        div(classes = "rep-line") {
                            span(classes = "rep-sched") { +"Every" }
                            numberInput(name = "n", classes = "rep-n") { value = r.n.toString(); min = "1"; max = "999" }
                            listOf(RecurrenceUnit.DAY to "days", RecurrenceUnit.WEEK to "weeks", RecurrenceUnit.MONTH to "months").forEach { (unit, name) ->
                                label(classes = "pill") { radioInput(name = "unit") { value = unit.name; checked = r.unit == unit }; +name }
                            }
                            span(classes = "rep-after") { +Labels.AFTER_COMPLETION_SUFFIX }
                        }
                        div(classes = "rep-wd") {
                            Labels.WEEKDAYS.forEach { (bit, label) ->
                                label(classes = "pill") { checkBoxInput(name = "wd") { value = bit.toString(); checked = r.weekdaysMask and (1 shl bit) != 0 }; +label }
                            }
                        }
                        div(classes = "rep-month") {
                            val day = localDay(anchor).dayOfMonth
                            label { radioInput(name = "monthly") { value = "day"; checked = r.monthlyNth == null }; +"On the ${ordinal(day)}" }
                            label {
                                radioInput(name = "monthly") { value = "nth"; checked = r.monthlyNth != null }; +"On the "
                                select { name = "nth"; NTH_NAMES.forEach { (n, name) -> option { value = n.toString(); selected = (r.monthlyNth ?: 1) == n; +name } } }
                                select { name = "mwd"; WEEKDAY_NAMES.forEachIndexed { i, name -> option { value = i.toString(); selected = r.monthlyWeekday == i; +name } } }
                            }
                        }
                        div(classes = "rep-ends") {
                            span(classes = "menu-label") { +"Ends" }
                            label { radioInput(name = "ends") { value = "never"; checked = r.until == null && r.count == null }; +"Never" }
                            label {
                                radioInput(name = "ends") { value = "until"; checked = r.until != null }; +"On "
                                dateInput(name = "until") { value = r.until?.let { localDay(it).toString() }.orEmpty() }
                            }
                            label {
                                radioInput(name = "ends") { value = "count"; checked = r.count != null }; +"After "
                                numberInput(name = "count", classes = "rep-n") { value = (r.count ?: 10).toString(); min = "1"; max = "999" }; +" times"
                            }
                        }
                        repeatPreview(r, anchor, service.now())
                    }
                }
                if (t.recurrenceType != null && r.preset == RecurrencePreset.NONE) {
                    p(classes = "hint") { +"Custom rule ${t.recurrenceRule}; kept unless you change it here." }
                }
            }
            // A timed task: its play button counts this down (app.js on the web, TaskTimer on the phone).
            timerPill(t, "task-only reminder")
            togglePill("today", t.expiresAt != null, Icon.SNOWFLAKE, Labels.TODAY_ONLY, "task-only")
        }
        // Not set here, but shown as its due date: a task waiting on this one is due sooner.
        if (!isNew && !t.isComplete) service.dueDates().of(t)?.takeIf { it.forTask != null }?.let { due ->
            p(classes = "hint due-for not-waiting") { +Labels.dueFromWaiting(dueText(due.at, service.now()), due.forTask!!.title) }
        }

        div(classes = "field-label section") { +Labels.PROPERTIES }
        div(classes = "pills") {
            val parentLabel = byId[t.parentId]?.let { p -> if (p.type == TaskType.FOLDER) folderPath(p, byId) else "Subtask of “${p.title}”" }
            // A set folder shows in its own colour, like the app's chip; app.js follows the pick.
            val colors = folderColorsHex(all)
            // A tree like the phone's folder picker: each folder in its colour, subfolders indented.
            // Under a project or checklist (or another task), its icon, not a folder's.
            val parentIcon = when (byId[t.parentId]?.type) { null, TaskType.FOLDER -> Icon.FOLDER; TaskType.CHECKLIST -> Icon.CHECKLIST; else -> Icon.PROJECT }
            popPill(Labels.FOLDER, parentIcon, parentLabel, "tree", "tinted", tint = t.parentId?.let(colors::get)) {
                div(classes = "folder-tree") {
                    fun FlowContent.treeRow(value: String, title: String, depth: Int, picked: Boolean, label: String? = null, color: String? = null) =
                        label(classes = "tree-row") {
                            attributes["style"] = "padding-left: ${8 + depth * 18}px; --tint: ${color ?: "var(--muted)"}"
                            radioInput(name = "parent") { this.value = value; checked = picked; label?.let { attributes["data-label"] = it }; color?.let { attributes["data-color"] = it } }
                            icon(Icon.FOLDER, "")
                            span { +title }
                        }
                    treeRow("", Labels.NO_FOLDER, 0, t.parentId == null)
                    byId[t.parentId]?.takeIf { it.type != TaskType.FOLDER }?.let { parent ->
                        treeRow(parent.id.toString(), "Subtask of “${parent.title}”", 0, true, label = "Subtask of “${parent.title}”")
                    }
                    folderTree(all.filter { it.type == TaskType.FOLDER }, excluding = t.id.takeIf { it != 0L }, allById = byId).forEach { (f, depth) ->
                        treeRow(f.id.toString(), f.title, depth, f.id == t.parentId, label = folderPath(f, byId), color = colors[f.id])
                    }
                }
            }
            // Folders keep contexts too: their tasks inherit them.
            service.contexts().sortedBy { it.name.lowercase() }.forEach { c ->
                label(classes = "pill") { checkBoxInput(name = "ctx") { value = c.id.toString(); checked = c.id in v.shown.contextIds }; +"@${c.name}" }
            }
            a(href = "/contexts", classes = "pill manage") { +Labels.MANAGE_CONTEXTS }
            // A step of an in-order parent can leave the next one free to come up beside it (Workflows.kt).
            if (byId[t.parentId]?.sequential == true) {
                hiddenInput(name = "alongsideShown") { value = "1" }
                togglePill("alongsideNext", t.alongsideNext, Icon.REDO, Labels.ALONGSIDE_NEXT)
            }
        }
        if (t.alongsideNext && byId[t.parentId]?.sequential == true) p(classes = "hint") {
            +(stepAfter(t, service.tasks())?.let { Labels.comesUpWith(it.title) } ?: Labels.NOTHING_AFTER)
        }
        // High (!), normal or maybe (?), picked like the type. A maybe is hidden from Active and Doing.
        div(classes = "field-label section task-only not-waiting") { +Labels.PRIORITY }
        div(classes = "type-seg task-only not-waiting") {
            Labels.PRIORITIES.forEach { (high, maybe, text) ->
                label {
                    radioInput(name = "priority") { value = if (high) "high" else if (maybe) "maybe" else "normal"; checked = t.isHighPriority == high && t.isMaybe == maybe }
                    +text
                }
            }
        }

        // A new task has no id for the related-task actions yet, so its subtasks, prerequisite and
        // dependent are typed here and created on save.
        if (isNew) {
            div(classes = "field-label section") { +Labels.RELATED }
            field(Labels.SUBTASK, "") {
                textArea(classes = "new-subtasks") { name = "newSubtasks"; rows = "3"; placeholder = "One per line"; +v.newSubtasks }
            }
            field(Labels.PREREQUISITE, "task-only") {
                textInput(name = "newDep", classes = "new-dep") { value = v.newDep; placeholder = "A new task this depends on"; attributes["autocomplete"] = "off" }
            }
            field(Labels.DEPENDENT, "task-only") {
                textInput(name = "newDependent", classes = "new-dep") { value = v.newDependent; placeholder = "A new task that depends on this"; attributes["autocomplete"] = "off" }
            }
        }

        if (v.needFirstStep || v.firstStep.isNotEmpty()) {
            field("First step of this project", "") { textInput(name = "firstStep") { value = v.firstStep; placeholder = Labels.SUBTASK } }
        }
    }
    if (!isNew && !t.isTemplate) t.fromTemplateId?.let(service::get)?.takeIf { it.isTemplate }?.let { fromTemplateLine(t.id, it) }
    if (!isNew) div { relatedSection(service, t.id, v.mode, focusAddItem = v.focusAdd, folded = v.list.collapsed) }
    // Outside the form (so Related tasks can sit above them), tied to it by the form attribute.
    div(classes = "editor-foot") {
        // Subtask options (none for a checklist, via CSS).
        div(classes = "subtask-options not-waiting") {
            div(classes = "pills") {
                // Only the first incomplete child counts as active.
                togglePill("sequential", t.sequential, Icon.LIST_NUMBERED, Labels.SEQUENTIAL, form = formId)
                // Normally a task waits on its open subtasks; this keeps it in Doing/Active anyway.
                togglePill("activeWithSubtasks", t.activeWithSubtasks, Icon.BOLT, Labels.ACTIVE_WITH_SUBTASKS, "task-type-only", form = formId)
            }
        }
        div(classes = "actions") {
            if (!isNew) button(type = ButtonType.submit, classes = "delete") {
                attributes["form"] = formId
                attributes["formaction"] = "/tasks/${t.id}/delete?mode=${v.list.q}"
                attributes["formnovalidate"] = ""
                +Labels.DELETE
            }
            // A copy kept as a template; this one carries on as it is (answered with a toast).
            if (!isNew && !t.isTemplate) button(type = ButtonType.button, classes = "save-template") {
                attributes["hx-post"] = "/tasks/${t.id}/save-template"
                attributes["hx-swap"] = "none"
                +Labels.SAVE_AS_TEMPLATE
            }
            // Checking the last item completes nothing; a checklist is completed here, on purpose (a template never is).
            if (!isNew && t.type == TaskType.CHECKLIST && !t.isComplete && !t.isTemplate) {
                val unchecked = service.tasks().count { it.parentId == t.id && !it.isComplete }
                fun completeButton(move: Boolean, label: String) = button(type = ButtonType.submit, classes = "complete-list") {
                    attributes["form"] = formId
                    attributes["formaction"] = "/tasks/${t.id}/complete-list?mode=${v.list.q}&move=${if (move) 1 else 0}"
                    attributes["formnovalidate"] = ""
                    +label
                }
                // With items unchecked, the same question as the phone's, in a popover.
                if (unchecked == 0) completeButton(false, Labels.COMPLETE_LIST)
                else details(classes = "pp complete-ask") {
                    summary(classes = "complete-list") { +Labels.COMPLETE_LIST }
                    div(classes = "pop") {
                        p(classes = "hint") { +Labels.uncheckedItems(unchecked) }
                        completeButton(true, Labels.MOVE_TO_NEW_LIST)
                        completeButton(false, Labels.COMPLETE_THEM_TOO)
                    }
                }
            }
            span(classes = "hint") { id = "save-status" }
            a(href = v.list.path, classes = "cancel") { +Labels.CANCEL }
            // A waiting item is resolved here (it saves itself as it's edited).
            if (!isNew && t.type == TaskType.WAITING && !t.isComplete) button(type = ButtonType.submit, classes = "primary resolve") {
                attributes["form"] = formId; name = "resolve"; value = "1"; +Labels.RESOLVED
            }
            else button(type = ButtonType.submit, classes = "primary") { attributes["form"] = formId; +Labels.SAVE }
        }
    }
}

// An on/off choice: a pill holding a small switch, the choice's icon and its label. Set apart from the
// picker pills (which open a popover) by its switch and outline. `form`: for one outside the editor's form.
private fun FlowContent.togglePill(name: String, on: Boolean, icon: Icon, label: String, classes: String = "", form: String? = null) =
    label(classes = "pill toggle $classes".trim()) {
        checkBoxInput(name = name) { checked = on; form?.let { attributes["form"] = it } }
        span(classes = "sw")
        icon(icon, "pill-icon")
        +label
    }

internal fun FlowContent.field(label: String, classes: String, content: FlowContent.() -> Unit) = div(classes = "field $classes") {
    span(classes = "field-label") { +label }
    content()
}

// A pill that opens a popover of controls. `value` is its text when set (else the label shows);
// `kind` tells app.js how to re-derive that text as the controls change.
private fun FlowContent.timerPill(t: Task, classes: String) = popPill(Labels.TIMER, Icon.TIMER, t.durationMinutes?.let(::formatDuration), "timer", classes) {
    div(classes = "pills") {
        Labels.TIMER_PRESETS.forEach { m -> button(type = ButtonType.button, classes = "pill preset") { attributes["data-minutes"] = m.toString(); +formatDuration(m) } }
    }
    numberInput(name = "duration", classes = "duration-input") { value = t.durationMinutes?.toString().orEmpty(); min = "1"; placeholder = "minutes" }
    span(classes = "hint inline-hint") { +"minutes" }
}

private fun FlowContent.popPill(label: String, icon: Icon, value: String?, kind: String, classes: String, tint: String? = null, content: FlowContent.() -> Unit) =
    details(classes = "pp $classes") {
        tint?.let { attributes["style"] = "--tint: $it" }
        attributes["data-kind"] = kind
        attributes["data-label"] = label
        summary(classes = if (value != null) "pill set" else "pill") {
            icon(icon, "pill-icon")
            span(classes = "pp-text") { +(value ?: label) }
            span(classes = "pp-clear") { attributes["title"] = "Clear"; +"✕" }
        }
        div(classes = "pop") { content() }
    }

// quick: (label, (date, time)) buttons that fill the fields and close the popover (app.js).
private fun FlowContent.datePill(label: String, prefix: String, icon: Icon, millis: Long?, classes: String, quick: List<Pair<String, Pair<String, String>>> = emptyList()) =
    popPill(label, icon, millis?.let(::chipDate), "date", classes) {
        val (date, time) = millis?.let(::dateAndTime) ?: ("" to "")
        dateInput(name = "${prefix}Date") { value = date }
        timeInput(name = "${prefix}Time") { value = time }
        if (quick.isNotEmpty()) div(classes = "date-quick") {
            quick.forEach { (text, value) ->
                button(type = ButtonType.button, classes = "pill") { attributes["data-date"] = value.first; attributes["data-time"] = value.second; +text }
            }
        }
    }

// "Work / Reports": a folder with the folders it's in, outermost first.
internal fun folderPath(folder: Task, byId: Map<Long, Task>): String =
    generateSequence(folder) { byId[it.parentId]?.takeIf { p -> p.type == TaskType.FOLDER } }.map { it.title }.toList().reversed().joinToString(" / ")
