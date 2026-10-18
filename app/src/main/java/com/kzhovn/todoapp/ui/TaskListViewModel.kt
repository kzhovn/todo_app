package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.subtaskCounts
import com.kzhovn.todoapp.data.dueOutsideMode
import com.kzhovn.todoapp.data.DueDates
import com.kzhovn.todoapp.data.CompleteQuestion
import com.kzhovn.todoapp.data.inMode
import com.kzhovn.todoapp.data.modeFolder
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import com.kzhovn.todoapp.repository.urgentFirst
import com.kzhovn.todoapp.repository.computeActiveIgnoringContexts
import com.kzhovn.todoapp.data.stalledProjects
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.parseSearch
import com.kzhovn.todoapp.data.searchMatcher
import com.kzhovn.todoapp.repository.ContextSummary
import com.kzhovn.todoapp.repository.contextSummaries
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.data.waitingToCheck
import com.kzhovn.todoapp.repository.RunningWait
import com.kzhovn.todoapp.repository.runningWaits
import com.kzhovn.todoapp.data.waitingBlocks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class TaskListMode { DOING, ACTIVE, ALL }

// The lists' context filter, picked from the @ button or the side menu: kept across tabs and screens until
// cleared, and until the app is closed.
object ContextFilter {
    val id = MutableStateFlow<Long?>(null)
}

// What the list shows: a tab, narrowed in place by a search and the context filter (Doing and Active keep
// their sections, the All tree the matches' parents). It reloads itself on any data change (`changes`,
// from TodoApp.listInputChanges), so actions here and edits elsewhere (another screen, the widget, a
// sync) all show up without each caller reloading.
class TaskListViewModel(
    private val repository: TaskRepository,
    private val contextRepository: ContextRepository,
    changes: Flow<Unit> = emptyFlow(),
    private val clock: () -> Long = System::currentTimeMillis,
    // Folder mode's folder id (AppSettings); Doing and Active keep to it.
    private val modeFolderId: () -> Long? = { null }
) : ViewModel() {
    private data class Shown(val mode: TaskListMode, val query: String, val filters: SearchFilters)
    private var shown = Shown(TaskListMode.DOING, "", SearchFilters())

    // While searching, what for (the rows show the line of notes a task matched on): the text, less any "@home".
    var searchQuery: String? = null
        private set
    private var reloading: Job? = null

    init {
        viewModelScope.launch { changes.collect { reload() } }
    }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks

    private val _subtaskCounts = MutableStateFlow<Map<Long, Pair<Int, Int>>>(emptyMap())
    val subtaskCounts: StateFlow<Map<Long, Pair<Int, Int>>> = _subtaskCounts

    private val _allById = MutableStateFlow<Map<Long, Task>>(emptyMap())
    val allById: StateFlow<Map<Long, Task>> = _allById
    private val _edges = MutableStateFlow<List<TaskDependency>>(emptyList())
    val edges: StateFlow<List<TaskDependency>> = _edges // Doing's Cancelled line (EventLines)
    private val _dues = MutableStateFlow(DueDates(emptyList(), emptyList()))
    val dues: StateFlow<DueDates> = _dues
    private val _workable = MutableStateFlow<Set<Long>>(emptySet())
    val workable: StateFlow<Set<Long>> = _workable // see dimmedInTree

    private val _contextsByTaskId = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())
    val contextsByTaskId: StateFlow<Map<Long, Set<Long>>> = _contextsByTaskId

    private val _allContexts = MutableStateFlow<Map<Long, TaskContext>>(emptyMap())
    val allContexts: StateFlow<Map<Long, TaskContext>> = _allContexts

    // Folder mode's folder, if it still exists.
    private val _mode = MutableStateFlow<Task?>(null)
    val mode: StateFlow<Task?> = _mode

    // Doing in a mode: what's due today or overdue outside it (the line under the list).
    private val _dueOutside = MutableStateFlow<List<Task>>(emptyList())
    val dueOutside: StateFlow<List<Task>> = _dueOutside

    // Doing's quiet Waiting section: waiting items whose check-in has come round (see waitingToCheck).
    private val _waiting = MutableStateFlow<List<Task>>(emptyList())
    val waiting: StateFlow<List<Task>> = _waiting
    // How many open tasks each of those blocks.
    private val _waitingBlocks = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val waitingBlocks: StateFlow<Map<Long, Int>> = _waitingBlocks

    fun stillWaiting(taskId: Long) = act { repository.stillWaiting(taskId, clock()) }

    // Doing's Running line: workflows' waits counting down, each with the step after it (see Workflows.kt).
    private val _running = MutableStateFlow<List<RunningWait>>(emptyList())
    val running: StateFlow<List<RunningWait>> = _running

    fun moreWait(taskId: Long) = act { repository.moreWait(taskId, clock()) }

    // A change, then the list again. (Room's invalidation also reloads it; the explicit one keeps a list
    // without that feed, as in the tests, current.)
    private fun act(block: suspend () -> Unit) = viewModelScope.launch { block(); reload() }

    // `filters.contextId`: the context filter; an "@home" typed in `query` goes over it.
    fun load(mode: TaskListMode, query: String = "", filters: SearchFilters = SearchFilters()) {
        shown = Shown(mode, query, filters)
        reload()
    }

    // A newer reload replaces one still running, so a slow load can't land after a newer one.
    fun reload() {
        reloading?.cancel()
        reloading = viewModelScope.launch { loadTab(shown) }
    }

    // The All tree's matches while filtering (it shows them and their parents); null: the whole tree.
    private val _keep = MutableStateFlow<Set<Long>?>(null)
    val keep: StateFlow<Set<Long>?> = _keep
    // Searching Doing or Active: the matches that list doesn't show (not doable yet, or done), which All has.
    private val _moreInAll = MutableStateFlow(0)
    val moreInAll: StateFlow<Int> = _moreInAll
    // The context the list is filtered by, picked or typed.
    private val _shownContext = MutableStateFlow<Long?>(null)
    val shownContext: StateFlow<Long?> = _shownContext
    // The contexts as the @ menu shows them.
    private val _contextSummaries = MutableStateFlow<List<ContextSummary>>(emptyList())
    val contextSummaries: StateFlow<List<ContextSummary>> = _contextSummaries

    private suspend fun loadTab(s: Shown) {
        val (mode, query, picked) = s
        val now = clock()
        val all = refreshSubtaskCounts(repository.listedTasks(now))
        val byId = _allById.value
        val folder = _mode.value?.id
        val (text, typed) = parseSearch(query, _allContexts.value.values)
        val filters = picked.copy(contextId = typed ?: picked.contextId)
        val filtering = text.isNotBlank() || filters != SearchFilters()
        val matches = searchMatcher(all, _contextsByTaskId.value, text, filters)
        fun <T> List<T>.matching(task: (T) -> Task) = if (filtering) filter { matches(task(it)) } else this
        searchQuery = text.ifBlank { null }
        _shownContext.value = filters.contextId
        // Filtered by a context, its tasks show as if it held: they're what was asked for.
        val asIfOn = filters.contextId?.takeIf { it != SearchFilters.NO_CONTEXT }
        _dueOutside.value = emptyList()
        _waiting.value = (if (mode == TaskListMode.DOING) waitingToCheck(all, now).filter { inMode(it, folder, byId) } else emptyList()).matching { it }
        _running.value = (if (mode == TaskListMode.DOING) runningWaits(all, now).filter { inMode(it.wait, folder, byId) } else emptyList()).matching { it.wait }
        if (_waiting.value.isNotEmpty()) _waitingBlocks.value = waitingBlocks(_waiting.value, repository.getAllDependencyEdges(), byId)
        suspend fun active() = repository.getActiveTasksFrom(all, _contextsByTaskId.value, now, asIfOn = asIfOn)
        _tasks.value = when (mode) {
            // The tree roots itself at the mode's folder (OutlinerScreen's rootId), and cuts itself down to `keep`.
            TaskListMode.ALL -> all
            // Overdue and due today first, like Doing (each folder section keeps that order).
            TaskListMode.ACTIVE -> urgentFirst(active().filter { inMode(it, folder, byId) }, now, _dues.value).matching { it }
            TaskListMode.DOING -> {
                val doing = filterDoing(active(), now, _dues.value)
                _dueOutside.value = dueOutsideMode(doing, folder, byId, now, _dues.value).matching { it }
                doing.filter { inMode(it, folder, byId) }.matching { it }
            }
        }
        _keep.value = if (filtering && mode == TaskListMode.ALL) all.filter(matches).map { it.id }.toSet() else null
        val searching = query.isNotBlank() || picked.copy(contextId = null) != SearchFilters()
        _moreInAll.value = if (!searching || mode == TaskListMode.ALL) 0
            else _tasks.value.map { it.id }.toSet().let { shownIds -> all.count { matches(it) && inMode(it, folder, byId) && it.id !in shownIds } }
    }

    // Projects whose subtasks are all done, waiting on "complete it or add the next step".
    private val _stalledProjects = MutableStateFlow<List<Task>>(emptyList())
    val stalledProjects: StateFlow<List<Task>> = _stalledProjects

    fun completeProject(projectId: Long) = act { repository.markComplete(projectId, clock()) }

    fun move(taskId: Long, anchorId: Long, after: Boolean) = act { repository.moveNextTo(taskId, anchorId, after) }

    private suspend fun refreshSubtaskCounts(all: List<Task>): List<Task> {
        _stalledProjects.value = stalledProjects(all)
        _subtaskCounts.value = subtaskCounts(all)
        _allById.value = all.associateBy { it.id }
        val edges = repository.getAllDependencyEdges()
        _edges.value = edges
        _dues.value = DueDates(all, edges)
        _workable.value = computeActiveIgnoringContexts(all, edges, clock()).map { it.id }.toSet()
        _mode.value = modeFolder(modeFolderId(), _allById.value)
        _contextsByTaskId.value = repository.getAllTaskContexts()
        val contexts = contextRepository.getAllContexts()
        _allContexts.value = contexts.associateBy { it.id }
        _contextSummaries.value = contextSummaries(contexts, contextRepository.getAllTimeWindows(), all, _contextsByTaskId.value, clock(), repository.calendarEvents())
        return all
    }

    fun toggleStar(taskId: Long) = act { repository.toggleStar(taskId) }

    fun snooze(taskId: Long, until: Long) = act { repository.snooze(taskId, until, clock()) }

    fun reparent(taskId: Long, newParentId: Long?) = act { repository.reparent(taskId, newParentId) }

    // Open subtasks are asked about first (onNeedsDecision); otherwise it's ticked, or unticked, at once.
    fun requestComplete(taskId: Long, onNeedsDecision: (Long, CompleteQuestion) -> Unit) {
        viewModelScope.launch {
            val question = repository.completeQuestion(taskId)
            if (question != null) onNeedsDecision(taskId, question) else act { repository.toggleComplete(taskId, clock()) }
        }
    }

    // The answer to that question (planCompleteChoosing).
    fun completeChoosing(taskId: Long, moveOut: Boolean) = act { repository.completeChoosing(taskId, moveOut, clock()) }
}
