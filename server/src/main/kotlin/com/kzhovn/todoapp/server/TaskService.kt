package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.isUnder
import com.kzhovn.todoapp.data.CalendarData
import com.kzhovn.todoapp.data.CalendarEvent
import com.kzhovn.todoapp.data.linkedToEvent
import com.kzhovn.todoapp.data.planFollowEvents
import com.kzhovn.todoapp.data.planLinkEvent
import com.kzhovn.todoapp.data.planRelinkEvent
import com.kzhovn.todoapp.widget.TodoWidgetPresenter
import com.kzhovn.todoapp.quickadd.chipItems
import com.kzhovn.todoapp.quickadd.withChips
import com.kzhovn.todoapp.repository.clearInherited
import com.kzhovn.todoapp.repository.openDescendants
import com.kzhovn.todoapp.repository.planCompleteAndPromote
import com.kzhovn.todoapp.quickadd.QuickAddParser
import com.kzhovn.todoapp.data.completed
import com.kzhovn.todoapp.data.CurrentTask
import com.kzhovn.todoapp.data.defaultFolder
import com.kzhovn.todoapp.data.dueOutsideMode
import com.kzhovn.todoapp.data.DueDates
import com.kzhovn.todoapp.repository.intoTemplates
import com.kzhovn.todoapp.repository.TemplateChange
import com.kzhovn.todoapp.repository.planSaveAsTemplate
import com.kzhovn.todoapp.repository.planUpdateTemplate
import com.kzhovn.todoapp.repository.planUseTemplate
import com.kzhovn.todoapp.repository.RunningWait
import com.kzhovn.todoapp.data.isTimedWait
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.withMoreWait
import com.kzhovn.todoapp.repository.templateChanges
import com.kzhovn.todoapp.repository.templateRoots
import com.kzhovn.todoapp.repository.templatesKeptFrom
import com.kzhovn.todoapp.data.CompleteQuestion
import com.kzhovn.todoapp.data.completeQuestion
import com.kzhovn.todoapp.repository.planCompleteChoosing
import com.kzhovn.todoapp.repository.planAddSection
import com.kzhovn.todoapp.repository.planCheckAll
import com.kzhovn.todoapp.data.inMode
import com.kzhovn.todoapp.data.modeFolder
import com.kzhovn.todoapp.quickadd.QuickAdd
import com.kzhovn.todoapp.quickadd.planQuickAdd
import com.kzhovn.todoapp.data.pinnedTask
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.isLinkable
import com.kzhovn.todoapp.data.findFolder
import com.kzhovn.todoapp.repository.blockerFor
import com.kzhovn.todoapp.repository.applyTo
import com.kzhovn.todoapp.data.planMoveNextTo
import com.kzhovn.todoapp.data.planMoveItem
import com.kzhovn.todoapp.data.splitItems
import com.kzhovn.todoapp.repository.urgentFirst
import com.kzhovn.todoapp.repository.computeActiveIgnoringContexts
import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.DEFAULT_ROLLOVER_HOUR
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.canDependOn
import com.kzhovn.todoapp.data.canMoveUnder
import com.kzhovn.todoapp.data.safeDependencies
import com.kzhovn.todoapp.data.safeParent
import com.kzhovn.todoapp.repository.TaskChanges
import com.kzhovn.todoapp.repository.planClearChecked
import com.kzhovn.todoapp.repository.planAddItems
import com.kzhovn.todoapp.repository.planComplete
import com.kzhovn.todoapp.repository.planPromoteChildren
import com.kzhovn.todoapp.repository.planPurgeExpired
import com.kzhovn.todoapp.repository.planUncheckAll
import com.kzhovn.todoapp.repository.planCreate
import com.kzhovn.todoapp.repository.planQuickAddWrite
import com.kzhovn.todoapp.repository.planCompleteChecklist
import com.kzhovn.todoapp.repository.planCompleteWithDescendants
import com.kzhovn.todoapp.repository.planResolveWaiting
import com.kzhovn.todoapp.repository.planUncomplete
import com.kzhovn.todoapp.data.stillWaiting
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.newId
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import com.kzhovn.todoapp.data.folderColorAssignments
import com.kzhovn.todoapp.data.stalledProjects
import com.kzhovn.todoapp.repository.BulkEdit
import com.kzhovn.todoapp.repository.InheritedField
import com.kzhovn.todoapp.repository.computeActiveTasks
import com.kzhovn.todoapp.repository.asIfOn
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.sync.CONTEXTS
import com.kzhovn.todoapp.sync.DELETED_AT
import com.kzhovn.todoapp.sync.SyncRow
import com.kzhovn.todoapp.sync.TASKS
import com.kzhovn.todoapp.sync.contextFields
import com.kzhovn.todoapp.sync.contextIds
import com.kzhovn.todoapp.sync.dependsOn
import com.kzhovn.todoapp.sync.taskFields
import com.kzhovn.todoapp.sync.timeWindows
import com.kzhovn.todoapp.sync.toContext
import com.kzhovn.todoapp.sync.toTask
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The server-side mirror of TaskRepository's write paths, over synced rows instead of Room. Pure
// rules (recurrence, active/doing, inheritance) come from :core so both sides agree.
class TaskService(private val store: Store, private val clock: () -> Long = System::currentTimeMillis) {

    fun now(): Long = clock()

    fun rolloverHour(): Int = store.getValue(Store.ROLLOVER_HOUR_KEY)?.toIntOrNull() ?: DEFAULT_ROLLOVER_HOUR

    // From the web's settings; the phone picks it up on its next sync.
    fun setRolloverHour(hour: Int) = store.setRolloverHour(hour.coerceIn(0, 23), clock())

    fun get(id: Long): Task? = store.get(TASKS, id)?.takeUnless { it.isDeleted }?.toTask()?.takeUnless { it.isExpired(clock()) }

    // Resolves waiting items whose date has come, and deletes "Today only" tasks whose day is over (reads
    // already hide them; this makes it permanent, so the phone drops them too even if it was offline).
    fun purgeExpired() = store.transaction {
        val now = clock()
        apply(planResolveWaiting(tasks(), contextIdsByTask(), now))
        // Expired tasks are hidden from tasks(), so this reads every live row.
        apply(planPurgeExpired(decoded().entries.map { it.task }, now))
    }

    fun deletedTask(id: Long): Task? = store.get(TASKS, id)?.takeIf { it.isDeleted }?.toTask()

    // The tasks the lists, Discord and the top bar see: no templates (see Templates.kt), nothing expired.
    fun tasks(): List<Task> = everything().filter { !it.isTemplate }

    // What an editor shows around a task: a template's own tasks only exist in everything().
    fun tasksAround(id: Long?): List<Task> = if (id?.let(::get)?.isTemplate == true) everything() else tasks()

    // Templates (see Templates.kt in :core): the list of them, saving one, using one (returns the new list), and
    // what a list made from one has changed, to send back.
    fun templates(): List<Task> = templateRoots(everything())
    fun saveAsTemplate(id: Long): Task? = planSaveAsTemplate(everything(), contextIdsByTask(), dependencyEdges(), id)?.let { (change, newId) -> apply(change); get(newId) }
    fun useTemplate(id: Long): Task? = planUseTemplate(everything(), contextIdsByTask(), dependencyEdges(), id, clock())?.let { (change, newId) -> apply(change); get(newId) }
    fun templateChanges(listId: Long): List<TemplateChange> = templateChanges(everything(), listId)
    fun updateTemplate(listId: Long, chosen: Set<String>, oneOff: Set<String>) = apply(planUpdateTemplate(everything(), contextIdsByTask(), listId, chosen, oneOff, clock()))

    // Templates included: what changes are planned against, and the template screens read.
    fun everything(): List<Task> = liveEntries().map { it.task }

    // Folder mode, shared by every device (see Store.setMode). A set here always wins: it's the newest.
    fun modeFolder(): Task? = modeFolder(store.modeFolderId(), tasks().associateBy { it.id })
    fun modeFolderId(): Long? = modeFolder()?.id
    fun setMode(folderId: Long?) = store.setMode(folderId?.takeIf { get(it)?.type == TaskType.FOLDER }, maxOf(clock(), store.modeSetAt() + 1))

    // When a date-only reminder goes off, from either Settings page.
    fun reminderHour(): Int = store.reminderHour()
    fun setReminderHour(hour: Int) { if (hour != reminderHour()) store.setReminderHour(hour, maxOf(clock(), store.reminderHourSetAt() + 1)) }

    // Discord's morning digest, on or off from either Settings page.
    fun digestOn(): Boolean = store.digestOn()
    fun setDigestOn(on: Boolean) { if (on != digestOn()) store.setDigestOn(on, maxOf(clock(), store.digestSetAt() + 1)) }

    fun defaultFolderId(): Long? = defaultFolder(tasks(), store.modeFolderId())?.id

    // Google Calendar (GoogleCalendar.kt): what was last read. Events contexts go by any calendar; everything
    // else sees only the shown ones (Settings → Calendars).
    fun calendar(): CalendarData = store.calendar()
    fun events(): List<CalendarEvent> = calendar().events
    fun shownEvents(): List<CalendarEvent> = calendar().shown(hiddenCalendars())
    fun event(id: String): CalendarEvent? = events().firstOrNull { it.id == id }
    fun hiddenCalendars(): Set<String> = store.hiddenCalendars().toSet()
    fun setCalendarShown(id: String, shown: Boolean) {
        val hidden = if (shown) hiddenCalendars() - id else hiddenCalendars() + id
        if (hidden != hiddenCalendars()) store.setHiddenCalendars(hidden.sorted(), maxOf(clock(), store.hiddenCalendarsSetAt() + 1))
    }

    // A fresh read: kept (with a new version only when something changed, so phones fetch it again only then), and
    // the Event items follow it (planFollowEvents).
    fun setCalendar(data: CalendarData) = store.transaction {
        val old = calendar()
        val changed = old.calendars != data.calendars || old.events != data.events
        store.setCalendar(data.copy(version = if (changed) maxOf(clock(), old.version + 1) else old.version))
        apply(planFollowEvents(tasks(), dependencyEdges(), data, clock()))
    }

    // Links a task to an event, before or after it (planLinkEvent).
    fun linkEvent(taskId: Long, eventId: String, before: Boolean) {
        val event = event(eventId) ?: return
        if (get(taskId) == null) return
        apply(planLinkEvent(tasks(), taskId, event, before, clock()))
    }

    // A cancelled event's choices (Doing's Cancelled line): its tasks kept, unlinked; linked to another event; or
    // deleted, with it (restore brings them back: one delete stamp).
    fun keepEventTasks(itemId: Long) = eventItem(itemId)?.let { delete(it.id) }
    fun relinkEvent(itemId: Long, eventId: String) {
        val item = eventItem(itemId) ?: return
        val event = event(eventId) ?: return
        apply(planRelinkEvent(tasks(), dependencyEdges(), item.id, event, clock()))
    }
    // Returns the deletes' shared stamp, for restoreDeletedAt.
    fun deleteEventTasks(itemId: Long): Long? = store.transaction {
        val item = eventItem(itemId) ?: return@transaction null
        val now = clock()
        (linkedToEvent(item.id, tasks(), dependencyEdges()).all.flatMap { subtreeIds(it.id) } + item.id).toSet()
            .forEach { if (get(it) != null) tombstone(TASKS, it, now) }
        now
    }
    fun restoreDeletedAt(deletedAt: Long) = store.transaction {
        store.all(TASKS).filter { it.deletedAt == deletedAt }.forEach { store.write(TASKS, it.id, JsonObject(mapOf(DELETED_AT to JsonNull)), clock()) }
    }
    private fun eventItem(id: Long): Task? = get(id)?.takeIf { it.type == TaskType.EVENT }

    // Active and Doing follow the mode unless given a folder; null means everything (the digest's).
    // `asIfOn`: a context the list is filtered by, whose tasks show whether it holds or not.
    fun active(folderId: Long? = modeFolderId(), asIfOn: Long? = null): List<Task> {
        val all = tasks()
        val byId = all.associateBy { it.id }
        val contextRows = store.all(CONTEXTS).filterNot { it.isDeleted }
        val active = computeActiveTasks(
            all = all,
            contextsByTaskId = contextIdsByTask(),
            contexts = asIfOn(contextRows.map { it.toContext() }, asIfOn),
            timeWindows = contextRows.flatMap { it.timeWindows() },
            dependencies = dependencyEdges(),
            now = clock(),
            events = events()
        )
        return urgentFirst(active.filter { inMode(it, folderId, byId) }, clock(), dueDates())
    }

    // Doing's quiet Waiting section: waiting items due a check-in (waitingToCheck), in the mode's folder.
    fun waitingToCheck(folderId: Long? = modeFolderId()): List<Task> {
        val all = tasks()
        val byId = all.associateBy { it.id }
        return com.kzhovn.todoapp.data.waitingToCheck(all, clock()).filter { inMode(it, folderId, byId) }
    }

    fun stillWaiting(id: Long) { get(id)?.takeIf { it.type == TaskType.WAITING }?.let { t -> update(id) { stillWaiting(t, clock()) } } }

    // Doing's Running line: waits counting down (see Workflows.kt), in the mode's folder.
    fun runningWaits(folderId: Long? = modeFolderId()): List<RunningWait> {
        val all = tasks()
        val byId = all.associateBy { it.id }
        return com.kzhovn.todoapp.repository.runningWaits(all, clock()).filter { inMode(it.wait, folderId, byId) }
    }

    fun toggleAlongside(id: Long) = update(id) { it.copy(alongsideNext = !it.alongsideNext) }

    // A running wait's "+10 min".
    fun moreWait(id: Long) { get(id)?.takeIf { it.isTimedWait && !it.isComplete }?.let { t -> update(id) { t.withMoreWait(Labels.MORE_WAIT_MINUTES, clock()) } } }

    // Review's "Waiting now": Active with contexts ignored, so a task held back only by a context counts.
    // In the mode's folder, unless `everywhere`.
    fun waiting(everywhere: Boolean = false): List<Task> {
        val byId = tasks().associateBy { it.id }
        val mode = if (everywhere) null else modeFolderId()
        return activeIgnoringContexts().filter { inMode(it, mode, byId) }
    }

    // Doing as if every context were on: the nudges' view, so a task that drops out of Doing overnight
    // (a work-hours context, being away from home) still counts as sitting there.
    fun doingIgnoringContexts(): List<Task> = filterDoing(activeIgnoringContexts(), clock(), dueDates())

    fun activeIgnoringContexts(): List<Task> = computeActiveIgnoringContexts(tasks(), dependencyEdges(), clock())

    fun doing(folderId: Long? = modeFolderId()): List<Task> = doing(active(folderId))

    // Doing out of an Active list already worked out (a page needs both).
    fun doing(active: List<Task>): List<Task> = filterDoing(active, clock(), dueDates())

    // Doing's line under the list in a mode: what's due today or overdue outside it.
    fun dueOutsideMode(): List<Task> = dueOutsideMode(doing(null), modeFolderId(), tasks().associateBy { it.id }, clock(), dueDates())

    fun dueDates() = DueDates(tasks(), dependencyEdges())

    fun effectiveDueDate(task: Task): Long? = dueDates().at(task)

    // Open tasks anywhere under the folder, for `.list <folder>`.
    fun openInFolder(folderId: Long): List<Task> {
        val all = tasks()
        val byId = all.associateBy { it.id }
        return TodoWidgetPresenter.allOpen(all, byId).filter { isUnder(it, folderId, byId) }
    }

    fun folders(): List<Task> = tasks().filter { it.type == TaskType.FOLDER }

    // Stores colour slots for folders without one (see folderColorAssignments), like the app does.
    fun ensureFolderColors() = folderColorAssignments(tasks()).forEach { t -> update(t.id) { it.copy(colorIndex = t.colorIndex) } }

    fun findFolder(name: String): Task? = findFolder(tasks(), name)

    // See planCreate (shared with the app). Always a new id.
    fun create(task: Task, contextIds: Set<Long> = emptySet()): Task = store.transaction {
        val change = planCreate(everything(), task.copy(id = 0), contextIds, clock())
        apply(change)
        change.creates.single().first
    }

    fun update(id: Long, change: (Task) -> Task) {
        val row = store.get(TASKS, id)?.takeUnless { it.isDeleted } ?: return
        writeTask(change(row.toTask()).withRules(clock()), row.contextIds(), row.dependsOn())
    }

    // The current task: the pin, with its timer and focus session (see CurrentTask), shared by every device.
    private fun current(change: (List<Task>) -> List<Task>) = store.transaction {
        change(tasks()).forEach { t -> update(t.id) { t } }
    }

    fun pin(id: Long) = current { CurrentTask.pin(it, id, clock()) }
    fun unpin() = current { CurrentTask.unpin(it) }
    fun startTimer(id: Long, minutes: Int) = current { CurrentTask.startTimer(it, id, minutes, clock()) }
    fun pauseTimer() = current { CurrentTask.pauseTimer(it, clock()) }
    fun resumeTimer() = current { CurrentTask.resumeTimer(it, clock()) }
    fun addTime(minutes: Int) = current { CurrentTask.addTime(it, minutes, clock()) }
    fun focus(id: Long) = current { CurrentTask.focus(it, id, clock()) }

    // A timed task's play button: its own length.
    fun startTimer(id: Long) { get(id)?.durationMinutes?.let { startTimer(id, it) } }

    fun pinned(): Task? = pinnedTask(tasks())

    fun focusSession(): Task? = CurrentTask.focusSession(tasks())

    // Quick add from the web or the desktop tray: parsed like the app's, into Personal (or the folder
    // being looked at) unless it names one. Added while looking at Doing, it starts starred so it shows
    // up right there, like the Doing widget's.
    fun quickAdd(text: String, fromDoing: Boolean, folderId: Long? = null, type: TaskType? = null): Task? {
        val folder = folderId?.takeIf { get(it)?.type == TaskType.FOLDER } ?: defaultFolderId()
        return add(planQuickAdd(text).withChips(false, null, null, false, null, clock(), rolloverHour(), type), folder, star = fromDoing)
    }

    fun planQuickAdd(text: String): QuickAdd = planQuickAdd(text, everything(), contexts(), clock(), rolloverHour())

    // With the phone quick add's chips (the top bar's): they beat what the text says, except that a
    // folder named in the text beats the Folder chip. A checklist on that chip makes the text its items.
    fun planQuickAdd(text: String, chips: QuickAddChips): QuickAdd {
        val chosen = chips.folderId?.let(::get)
        chipItems(text, chosen)?.let { return it }
        // A chip's date is one of quick add's words ("tomorrow", "next week"), read as quick add reads it.
        fun day(word: String?) = word?.takeIf { it.isNotBlank() }?.let { QuickAddParser.parse("-s $it", clock()).startDate }
        return planQuickAdd(text).withChips(chips.star, day(chips.start), day(chips.due), chips.todayOnly, chosen, clock(), rolloverHour())
    }

    // See planQuickAddWrite (shared with the app). Returns the task (the checklist, for items).
    fun add(add: QuickAdd, defaultParent: Long?, star: Boolean = false): Task? = store.transaction {
        val (change, id) = planQuickAddWrite(everything(), add, defaultParent, clock(), ::planQuickAdd, star, contextIdsByTask(), dependencyEdges())
        apply(change)
        id?.let(::get)
    }

    // Any "new task" box: quick add's whole syntax (a folder prefix, @contexts, [items], -p, notes...).
    // `under` puts it there whatever the text says (a subtask, a next step); otherwise it goes in `folder`
    // unless the text names one. Returns the new task (null for nothing, or items into a checklist).
    fun addTyped(text: String, folder: Long? = null, under: Long? = null, star: Boolean = false): Task? {
        // Under a checklist the text is its items.
        if (under != null && get(under)?.type == TaskType.CHECKLIST) return null.also { addItems(under, text) }
        val add = planQuickAdd(text)
        val task = add.task ?: return null.also { add(add, defaultParent = folder) }
        return add(add.copy(task = if (under != null) task.copy(parentId = under) else task), defaultParent = folder, star = star)
    }

    fun setStarred(id: Long, starred: Boolean) = update(id) { it.copy(isStarred = starred) }

    fun toggleStar(id: Long) = update(id) { it.toggledStar() }

    // Hidden from Active until then, like the app's snooze.
    fun snooze(id: Long, until: Long) = update(id) { it.copy(startDate = until) }

    // A repeating task's "skip this time": on to its next time, not done (RecurrenceEngine.skip).
    fun skip(id: Long) { get(id)?.let { RecurrenceEngine.skip(it, clock()) }?.let { skipped -> update(id) { skipped } } }

    fun contexts(): List<TaskContext> = store.all(CONTEXTS).filterNot { it.isDeleted }.map { it.toContext() }

    fun contextIdsByTask(): Map<Long, Set<Long>> = liveEntries().associate { it.task.id to it.contextIds }

    fun timeWindows(contextId: Long): List<ContextTimeWindow> =
        store.get(CONTEXTS, contextId)?.takeUnless { it.isDeleted }?.timeWindows().orEmpty()

    // The web's side of the phone's contexts screen. Whether a place context currently holds is the
    // phone's to say (it sees the wifi), so a save keeps whatever the phone last reported.
    fun saveContext(context: TaskContext, windows: List<ContextTimeWindow>): TaskContext {
        val existing = store.get(CONTEXTS, context.id)?.takeUnless { it.isDeleted }?.toContext()
        val place = context.type == ContextType.PLACE
        val saved = context.copy(
            id = existing?.id ?: newId(),
            wifiSsid = context.wifiSsid.takeIf { place },
            isCurrentlySatisfied = existing?.isCurrentlySatisfied ?: false,
            forced = existing?.forced
        )
        store.write(CONTEXTS, saved.id, contextFields(saved, if (context.type == ContextType.TIME) windows else emptyList()), clock())
        return saved
    }

    fun setContextForced(id: Long, forced: Boolean?) {
        val row = store.get(CONTEXTS, id)?.takeUnless { it.isDeleted } ?: return
        store.write(CONTEXTS, id, contextFields(row.toContext().copy(forced = forced), row.timeWindows()), clock())
    }

    // Like the phone's: the context goes, and so does every task's assignment to it.
    fun deleteContext(id: Long) = store.transaction {
        val now = clock()
        liveEntries().filter { id in it.contextIds }.forEach { e -> writeTask(e.task, e.contextIds - id, e.dependsOn, now) }
        tombstone(CONTEXTS, id, now)
    }


    fun dependsOn(id: Long): Set<Long> = store.get(TASKS, id)?.dependsOn().orEmpty()

    fun dependencyEdges(): List<TaskDependency> = liveEntries().flatMap { e -> e.dependsOn.map { TaskDependency(e.task.id, it) } }

    // The editor's save: the whole task plus its context and dependency sets, with links checked
    // (safeParent, safeDependencies) rather than trusted from the form.
    fun edit(task: Task, contextIds: Set<Long>, dependsOn: Set<Long>) = store.transaction {
        val byId = tasks().associateBy { it.id }
        val current = byId[task.id] ?: return@transaction
        val parentId = safeParent(task, current.parentId, byId)
        // withRules drops what the type can't carry (Task.typeRule).
        val saved = task.copy(
            parentId = parentId,
            // Lands at the end of a new sibling list, like reparent.
            position = if (parentId != current.parentId) null else task.position
        ).withRules(clock())
        val contexts = contexts().map { it.id }.toSet().let { known -> contextIds.filterTo(mutableSetOf()) { it in known } }
        writeTask(saved, contexts, safeDependencies(saved, dependsOn, byId, dependencyEdges()))
    }

    // Moves a task under newParentId (null: the top level), at the end of its children, unless that would make a loop.
    fun reparent(id: Long, newParentId: Long?) {
        val byId = tasks().associateBy { it.id }
        if (newParentId != null && (newParentId !in byId || !canMoveUnder(id, newParentId, byId))) return
        update(id) { it.copy(parentId = newParentId, position = null) }
    }

    // See planMoveNextTo (shared with the app's TaskRepository.moveNextTo).
    fun moveNextTo(id: Long, anchorId: Long, after: Boolean) = store.transaction {
        val move = planMoveItem(everything(), id, anchorId, after) ?: return@transaction
        move.positions.forEach { (taskId, position) -> update(taskId) { it.copy(parentId = move.parentId, position = position) } }
    }

    // The outliner's moves, among the open siblings the tree shows (completed ones are hidden there).
    private fun openSiblings(task: Task) = tasks().filter { it.parentId == task.parentId && !it.isComplete }.sortedWith(TaskOrder)

    // Tab: becomes the last child of the sibling above it.
    fun indent(id: Long) {
        val task = get(id) ?: return
        val siblings = openSiblings(task)
        siblings.getOrNull(siblings.indexOf(task) - 1)?.let { reparent(id, it.id) }
    }

    // Shift-Tab: becomes the sibling right after its parent.
    fun outdent(id: Long) {
        val parentId = get(id)?.parentId ?: return
        moveNextTo(id, parentId, after = true)
    }

    // Alt-Up/Down: swaps places with the sibling above/below.
    fun moveAmongSiblings(id: Long, down: Boolean) {
        val task = get(id) ?: return
        val siblings = openSiblings(task)
        siblings.getOrNull(siblings.indexOf(task) + if (down) 1 else -1)?.let { moveNextTo(id, it.id, after = down) }
    }

    // What "Update subtasks too?" asks about: see overridingSubtasks.
    fun overridingSubtasks(id: Long, changed: Set<InheritedField>): Pair<List<Task>, Set<InheritedField>> {
        if (changed.isEmpty()) return emptyList<Task>() to emptySet()
        val byId = tasks().associateBy { it.id }
        return com.kzhovn.todoapp.repository.overridingSubtasks(byId.values.filter { isUnder(it, id, byId) }, contextIdsByTask(), changed)
    }

    // Clears those fields on the given tasks (as stored now), so they inherit from their ancestors again.
    fun clearInherited(tasks: List<Task>, fields: Set<InheritedField>) = store.transaction {
        for (t in tasks) {
            val row = store.get(TASKS, t.id)?.takeUnless { it.isDeleted } ?: continue
            writeTask(row.toTask().clearInherited(fields), if (InheritedField.CONTEXTS in fields) emptySet() else row.contextIds(), row.dependsOn())
        }
    }

    // Makes taskId wait for dependsOnId, unless that would create a dependency loop.
    fun addDependency(taskId: Long, dependsOnId: Long) = store.transaction {
        val e = liveEntries().firstOrNull { it.task.id == taskId } ?: return@transaction
        if (!canDependOn(taskId, dependsOnId, dependencyEdges())) return@transaction
        writeTask(e.task, e.contextIds, e.dependsOn + dependsOnId)
    }

    // Carries out a change planned in :core (see TaskChanges), as the app's TaskRepository does: what's made or
    // moved under a template becomes part of it (intoTemplates).
    private fun apply(planned: TaskChanges, now: Long = clock()) = store.transaction {
        val changes = planned.intoTemplates(everything().filter { it.isTemplate }.map { it.id }.toSet())
        val links = changes.links.groupBy({ it.taskId }, { it.dependsOnTaskId })
        changes.creates.forEach { (task, contextIds) -> store.write(TASKS, task.id, taskFields(task, contextIds, links[task.id].orEmpty()), now) }
        changes.updates.forEach { t -> update(t.id) { t } }
        // Links from tasks already there (one linked to a new event, say).
        val created = changes.creates.map { it.first.id }.toSet()
        links.filterKeys { it !in created }.forEach { (id, more) ->
            liveEntries().firstOrNull { it.task.id == id }?.let { e -> writeTask(e.task, e.contextIds, e.dependsOn + more, now) }
        }
        changes.deletes.forEach { tombstone(TASKS, it, now) }
    }

    // See planComplete (shared with the app).
    fun complete(id: Long) = apply(planComplete(everything(), contextIdsByTask(), id, clock()))

    fun activeDescendantCount(id: Long): Int = openDescendants(id, everything()).size

    fun removeDependency(taskId: Long, dependsOnId: Long) = store.transaction {
        val e = liveEntries().firstOrNull { it.task.id == taskId } ?: return@transaction
        writeTask(e.task, e.contextIds, e.dependsOn - dependsOnId)
    }

    // Checklists; mirror TaskRepository's addItems, clearChecked, uncheckAll and completeChecklist.
    // See planAddItems (shared with the app): each item is read with quick add's syntax.
    fun addItems(checklistId: Long, text: String) = apply(planAddItems(everything(), checklistId, splitItems(text), clock(), ::planQuickAdd))

    fun findChecklist(name: String): Task? = tasks().firstOrNull { it.type == TaskType.CHECKLIST && !it.isComplete && it.title.trim().equals(name.trim(), ignoreCase = true) }

    // Returns the deletion's time, which restoreCleared takes to bring them back (null: nothing was checked).
    fun clearChecked(checklistId: Long): Long? {
        val change = planClearChecked(everything(), checklistId)
        if (change.deletes.isEmpty()) return null
        return clock().also { apply(change, it) }
    }

    // Undo for clearChecked: the items that went at `deletedAt`, with what was under them.
    fun restoreCleared(checklistId: Long, deletedAt: Long) = store.transaction {
        val holders = setOf(checklistId) + tasks().filter { it.parentId == checklistId && it.type == TaskType.SECTION }.map { it.id }
        store.all(TASKS).filter { it.deletedAt == deletedAt && it.toTask().parentId in holders }.map { it.id }.forEach(::restore)
    }

    fun uncheckAll(checklistId: Long) = apply(planUncheckAll(everything(), checklistId))

    // A checklist's sections: a new one at its end (planAddSection), and "Check all in Clothes" (planCheckAll).
    fun addSection(checklistId: Long, title: String) = apply(planAddSection(everything(), checklistId, title.trim(), emptyList(), clock(), ::planQuickAdd))
    fun checkAll(containerId: Long) = apply(planCheckAll(everything(), contextIdsByTask(), containerId, clock()))

    fun completeChecklist(checklistId: Long, moveUncheckedToNewList: Boolean) =
        apply(planCompleteChecklist(everything(), contextIdsByTask(), checklistId, moveUncheckedToNewList, clock()))

    fun completeWithDescendants(id: Long) = apply(planCompleteWithDescendants(everything(), contextIdsByTask(), id, clock()))

    // See planPromoteChildren (shared with the app).
    fun completeAndPromote(id: Long) = apply(planCompleteAndPromote(everything(), contextIdsByTask(), id, clock()))

    // See planCompleteChoosing: the answer to completeQuestion.
    fun completeChoosing(id: Long, moveOut: Boolean) = apply(planCompleteChoosing(everything(), contextIdsByTask(), id, moveOut, clock()))

    // What completing it asks first, or null with no open subtasks.
    fun completeQuestion(id: Long): CompleteQuestion? = get(id)?.let { completeQuestion(it, activeDescendantCount(id)) }

    // Open projects whose steps are all done: time to complete them or add the next step.
    fun stalled(): List<Task> = stalledProjects(tasks())

    // Only tasks and checklists (isDoable; none of the bulk properties apply to folders or projects); see BulkEdit.applyTo, shared with the app.
    fun applyBulkEdit(ids: Collection<Long>, change: BulkEdit) = store.transaction {
        val byId = tasks().associateBy { it.id }
        val edges = dependencyEdges()
        val contexts = contextIdsByTask()
        for (id in ids) {
            val task = byId[id]?.takeIf { it.type.isDoable } ?: continue
            val blocker = change.blockerFor(id, edges)
            edit(change.applyTo(task, byId), contexts[id].orEmpty() + change.addContextIds - change.removeContextIds, dependsOn(id) + listOfNotNull(blocker))
        }
    }

    fun uncomplete(id: Long) = apply(planUncomplete(everything(), id))

    // Soft-deletes the whole subtree with one shared timestamp, which is how restore() knows which
    // descendants went down together (vs. ones deleted separately earlier).
    // A folder's templates move to the top level instead of going with it (templatesKeptFrom).
    fun delete(id: Long) = store.transaction {
        if (get(id) == null) return@transaction
        val now = clock()
        val ids = subtreeIds(id)
        val all = everything()
        val (moved, kept) = templatesKeptFrom(all.filter { it.id in ids }, all)
        moved.forEach { t -> update(t.id) { t } }
        liveEntries().filter { it.task.id in ids && it.task.id !in kept }.forEach { tombstone(TASKS, it.task.id, now) }
    }

    fun restore(id: Long) = store.transaction {
        val deletedAt = store.get(TASKS, id)?.deletedAt ?: return@transaction
        val all = store.all(TASKS)
        val byId = all.associateBy { it.id }
        subtreeIds(id, all).forEach { taskId ->
            if (byId.getValue(taskId).deletedAt == deletedAt) {
                store.write(TASKS, taskId, JsonObject(mapOf(DELETED_AT to JsonNull)), clock())
            }
        }
    }

    private fun tombstone(table: String, id: Long, now: Long) =
        store.write(table, id, JsonObject(mapOf(DELETED_AT to JsonPrimitive(now))), now)

    // A task with its context and prerequisite sets (all of them, as they should now be).
    private fun writeTask(task: Task, contextIds: Set<Long>, dependsOn: Set<Long>, now: Long = clock()) =
        store.write(TASKS, task.id, JsonObject(taskFields(task, contextIds, dependsOn) - DELETED_AT), now)

    // Every task decoded once, with its context and prerequisite sets: one page or tray refresh reads them
    // all several times over. Store.all returns the same list until a write, which keeps every row it didn't
    // write as the same object, so only written rows are decoded again.
    private class Entry(val row: SyncRow, val task: Task, val contextIds: Set<Long>, val dependsOn: Set<Long>)
    private class Decoded(val source: List<SyncRow>, val entries: List<Entry>, val byId: Map<Long, Entry>)

    @Volatile
    private var decodedCache: Decoded? = null

    private fun decoded(): Decoded {
        val source = store.all(TASKS)
        val previous = decodedCache
        if (previous?.source === source) return previous
        val entries = source.filterNot { it.isDeleted }.map { row ->
            previous?.byId?.get(row.id)?.takeIf { it.row === row } ?: Entry(row, row.toTask(), row.contextIds(), row.dependsOn())
        }
        return Decoded(source, entries, entries.associateBy { it.row.id }).also { decodedCache = it }
    }

    private fun liveEntries(): List<Entry> = clock().let { now -> decoded().entries.filterNot { it.task.isExpired(now) } }

    private fun subtreeIds(rootId: Long, rows: List<SyncRow> = liveEntries().map { it.row }): Set<Long> {
        val decoded = decoded().byId
        val children = rows.groupBy({ (decoded[it.id]?.task ?: it.toTask()).parentId }, { it.id })
        val result = mutableSetOf<Long>()
        val stack = ArrayDeque(listOf(rootId))
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (result.add(next)) children[next]?.let(stack::addAll)
        }
        return result
    }
}

// The phone quick add's chips, as the top bar sends them (see TaskService.planQuickAdd).
data class QuickAddChips(val star: Boolean = false, val start: String? = null, val due: String? = null, val todayOnly: Boolean = false, val folderId: Long? = null)
