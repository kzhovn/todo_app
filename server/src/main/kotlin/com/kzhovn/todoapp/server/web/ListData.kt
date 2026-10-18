package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.waitingBlocks
import com.kzhovn.todoapp.repository.RunningWait
import com.kzhovn.todoapp.data.nearestFolder
import com.kzhovn.todoapp.repository.dimmedInTree
import com.kzhovn.todoapp.data.inMode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.parseSearch
import com.kzhovn.todoapp.data.searchMatcher
import com.kzhovn.todoapp.data.pruneTree
import java.time.LocalTime
import com.kzhovn.todoapp.data.atTimeMillis
import kotlinx.html.label
import kotlinx.html.A
import com.kzhovn.todoapp.data.OutlinerNode
import com.kzhovn.todoapp.data.buildOutlinerTree
import com.kzhovn.todoapp.data.subtaskCounts
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.stalledProjects
import com.kzhovn.todoapp.data.subtaskParentTitle
import com.kzhovn.todoapp.server.TaskService
import kotlinx.html.a
import kotlinx.html.id
import kotlinx.html.title
import com.kzhovn.todoapp.data.AgendaDay
import com.kzhovn.todoapp.data.DateKind
import com.kzhovn.todoapp.data.agenda
import com.kzhovn.todoapp.data.monthRange
import com.kzhovn.todoapp.data.weekRange
import java.time.LocalDate
import java.time.YearMonth

// The calendar (CalendarPage.kt) is a list too, so its agenda's rows act and re-render as the others do; it
// sits under More, not with the three lists.
enum class ListMode(val label: String) { DOING(Labels.DOING), ACTIVE(Labels.ACTIVE), ALL(Labels.ALL), CALENDAR(Labels.CALENDAR) }
val LISTS = ListMode.entries - ListMode.CALENDAR

// The calendar's month on screen (or its week, by its Monday, in Week), the day picked in it (null: the agenda
// runs on), and the kinds of date shown.
data class CalendarView(val month: YearMonth, val day: LocalDate?, val kinds: Set<DateKind>, val week: LocalDate? = null) {
    val shown: ClosedRange<LocalDate> get() = week?.let(::weekRange) ?: monthRange(month)
}

// A list's search box and filters, and its context filter (`context`, kept when the box is cleared). The search
// narrows the list in place: Doing and Active keep their sections, the All tree keeps the matches' parents.
data class ListSearch(
    val text: String = "",
    val context: Long? = null,
    val starred: Boolean = false,
    val completed: Boolean = false,
    val after: LocalDate? = null,
    val before: LocalDate? = null
) {
    val params: List<String> get() = listOfNotNull(
        text.takeIf { it.isNotBlank() }?.let { "q=" + it.encodeURLParameter() }, context?.let { "context=$it" },
        "starred=1".takeIf { starred }, "completed=1".takeIf { completed }, after?.let { "after=$it" }, before?.let { "before=$it" }
    )
    // The box or its filters, not just the context.
    val searching: Boolean get() = copy(context = null) != ListSearch()
}

internal fun Parameters.listSearch() = ListSearch(
    text = this["q"].orEmpty(),
    context = this["context"]?.toLongOrNull(),
    starred = this["starred"] != null,
    completed = this["completed"] != null,
    after = this["after"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    before = this["before"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
)

// Where a list is: its mode, a folder's view of the All tree (a folder filter on Doing and Active), the calendar's
// month and day, the search.
class ListPlace(val mode: ListMode, val folder: Long?, val calendar: CalendarView?, val search: ListSearch = ListSearch()) {
    // In a URL's query ("mode=ALL&folder=12" goes in as `?mode=$q`), so an action on a row re-renders the same
    // view; `path` is the page itself.
    val q: String get() = mode.name + extra("&")
    val path: String get() = mode.path + extra("?")
    private fun extra(first: String) = (listOfNotNull(folder?.let { "folder=$it" }, calendar?.let { "month=${it.month}" }, calendar?.week?.let { "week=$it" }, calendar?.day?.let { "day=$it" }) + search.params)
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
    val calendar: CalendarView? = null,
    val search: ListSearch = ListSearch()
) {
    private val place = ListPlace(mode, folder, calendar, search)
    val q: String get() = place.q
    val path: String get() = place.path
    // This list's own URL (what re-renders it), with `param` ("toggle=12") added.
    fun listUrl(param: String) = "/list/${mode.name.lowercase()}" + path.removePrefix(mode.path).let { if (it.isEmpty()) "?$param" else "$it&$param" }
    // The same search on another list (the sidebar's, "3 more in All").
    fun pathOf(other: ListMode, folder: Long? = null, search: ListSearch = this.search) = ListPlace(other, folder, null, search).path
    val title: String get() = folder?.takeIf { mode == ListMode.ALL }?.let { byId[it]?.title } ?: mode.label

    val all: List<Task> = service.tasks()
    val byId = all.associateBy { it.id }
    val contexts: List<TaskContext> by lazy { service.contexts() }
    private val windowsByContext by lazy { contexts.associate { it.id to service.timeWindows(it.id) } }
    fun windows(contextId: Long) = windowsByContext[contextId].orEmpty()
    // The search's text, an "@home" typed in it read as the context filter (over the one picked).
    private val parsed by lazy { parseSearch(search.text, contexts) }
    val searchText: String get() = parsed.first
    val contextId: Long? by lazy { parsed.second ?: search.context }
    val filtering: Boolean get() = search.searching || contextId != null || (folder != null && mode != ListMode.ALL)
    private val matches: (Task) -> Boolean by lazy {
        val filters = SearchFilters(
            folderId = folder.takeIf { mode != ListMode.ALL }, contextId = contextId, starredOnly = search.starred, includeCompleted = search.completed,
            dueAfter = search.after?.atTimeMillis(LocalTime.MIDNIGHT), dueBefore = search.before?.atTimeMillis(LocalTime.MIDNIGHT)
        )
        searchMatcher(all, service.contextIdsByTask(), searchText, filters)
    }
    private fun <T> List<T>.matching(task: (T) -> Task) = if (filtering) filter { matches(task(it)) } else this
    // Folder mode: the folder every device is zoomed into (null: none). Doing and Active follow it
    // (TaskService), and the All tree is rooted at it.
    val modeFolder: Task? = service.modeFolder()
    // Doing in a mode: what's due today or overdue outside it, in one line under the list.
    val dueOutside: List<Task> = (if (mode == ListMode.DOING && modeFolder != null) service.dueOutsideMode() else emptyList()).matching { it }
    // Doing's quiet Waiting section: waiting items due a check-in, and how many open tasks each blocks.
    val waiting: List<Task> = (if (mode == ListMode.DOING) service.waitingToCheck() else emptyList()).matching { it }
    val waitingBlocks: Map<Long, Int> = if (waiting.isEmpty()) emptyMap() else waitingBlocks(waiting, service.dependencyEdges(), byId)
    // Doing's Running line: waits counting down, each with the step that comes after it.
    val running: List<RunningWait> = (if (mode == ListMode.DOING) service.runningWaits() else emptyList()).matching { it.wait }
    val folderColors = folderColorsHex(all)
    // New ▾'s "From template…" (see Templates.kt).
    val templates: List<Task> by lazy { service.templates() }
    private val counts = subtaskCounts(all)
    // Worked out once per request: the list and the sidebar's counts both need them.
    val active: List<Task> by lazy { service.active() }
    // The All tree dims what isn't workable for a reason other than contexts (dimmedInTree).
    val workable: Set<Long> by lazy { service.activeIgnoringContexts().map { it.id }.toSet() }
    val doing: List<Task> by lazy { service.doing(active) }
    // Filtered by a context, its tasks show as if it held (they're what was asked for); the sidebar's counts don't.
    private val shownActive: List<Task> by lazy { contextId?.let { service.active(asIfOn = it) } ?: active }
    val tasks: List<Task> = when (mode) {
        ListMode.DOING -> if (contextId == null) doing else service.doing(shownActive)
        ListMode.ACTIVE -> shownActive
        ListMode.ALL, ListMode.CALENDAR -> emptyList() // the tree is rendered from `roots`, the calendar from `agenda`
    }.matching { it }
    // Searching Doing or Active: the matches that list doesn't show (not doable yet, or done), which All has.
    val moreInAll: Int by lazy {
        if (!search.searching || mode !in setOf(ListMode.DOING, ListMode.ACTIVE)) 0
        else tasks.map { it.id }.toSet().let { shown -> all.count { matches(it) && inMode(it, modeFolder?.id, byId) && it.id !in shown } }
    }
    // The All tree, searched: a match's parents are there to place it, muted.
    fun onlyAParent(task: Task) = filtering && mode == ListMode.ALL && !matches(task)
    val agenda: List<AgendaDay> by lazy {
        calendar?.let { agenda(all, it.shown, it.day, it.kinds, now, service.reminderHour(), modeFolder?.id) }.orEmpty()
    }

    private val dues = service.dueDates()
    fun effectiveDue(task: Task) = dues.of(task)
    fun parentTitle(task: Task) = subtaskParentTitle(task, byId)
    fun folderColor(task: Task): String? = nearestFolder(task, byId)?.let { folderColors[it.id] }
    fun ownColor(folder: Task) = folderColors[folder.id]
    fun subtaskCounts(task: Task): Pair<Int, Int>? = counts[task.id]
    // The tree's top level for this view: a folder's contents, the mode's, or everything. As the phone's:
    // completed tasks and projects drop out (their open subtasks move up); completed checklists stay, struck through.
    val roots: List<OutlinerNode> by lazy {
        val tree = buildOutlinerTree(all, hideCompleted = !search.completed, rootId = folder ?: modeFolder?.id)
        if (filtering) pruneTree(tree, all.filter(matches).map { it.id }.toSet()) else tree
    }

    // One open project whose steps are all done, to ask about (the phone asks the same, one at a time).
    val stalled: Task? = stalledProjects(all).firstOrNull { it.id !in later }
}

val ListMode.path get() = "/${name.lowercase()}"
