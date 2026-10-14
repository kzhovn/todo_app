package com.kzhovn.todoapp.bench

import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.DAY_MS
import com.kzhovn.todoapp.data.RecurrenceType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskType
import kotlin.random.Random

// The benchmarks' task list (tools/bench.sh), the same for the server's and the phone's. `openTask`: an open
// task with no subtasks, repeat or prerequisites, to complete and reopen; `bulkIds`: 50 more like it;
// `checklist`: one with 30 checked items.
class BenchData(
    val tasks: List<Task>,
    val contexts: List<Pair<TaskContext, List<ContextTimeWindow>>>,
    val contextIds: Map<Long, Set<Long>>,
    val dependsOn: Map<Long, Set<Long>>,
    val openTask: Long,
    val bulkIds: List<Long>,
    val checklist: Long
)

// A tree like a few years of use: folders two deep, projects with steps, checklists with sections, about two
// thirds completed, some dated, starred, repeating, maybe, waiting or with contexts, a few dependencies.
fun benchData(size: Int, now: Long = System.currentTimeMillis(), rnd: Random = Random(42)): BenchData {
    val home = TaskContext(id = 1, name = "home", type = ContextType.PLACE, wifiSsid = "Home")
    val work = TaskContext(id = 2, name = "work", type = ContextType.TIME)
    val tasks = mutableListOf<Task>()
    val ctx = mutableMapOf<Long, Set<Long>>()
    val deps = mutableMapOf<Long, MutableSet<Long>>()
    var seq = 0L
    // Ids carry creation time (newId): spread over the last two years.
    fun id() = ((now - rnd.nextLong(730 * DAY_MS)) shl 11) or (seq++ % 2048)
    fun add(t: Task) = t.also { tasks += it }
    val tops = (1..6).map { add(Task(id = id(), type = TaskType.FOLDER, title = "Folder $it", colorIndex = it - 1)) }
    val folders = tops + tops.flatMap { top -> (1..3).map { add(Task(id = id(), type = TaskType.FOLDER, title = "${top.title}.$it", parentId = top.id, colorIndex = it - 1)) } }
    fun day(offset: Int) = (now / DAY_MS + offset) * DAY_MS
    fun task(parent: Long?, type: TaskType = TaskType.TASK): Task {
        val complete = rnd.nextDouble() < 0.65
        val repeat = !complete && rnd.nextDouble() < 0.08
        return add(Task(
            id = id(), type = type, title = "Task ${seq} " + listOf("call", "email", "buy", "fix", "read", "plan").random(rnd), parentId = parent,
            isComplete = complete, completedAt = if (complete) now - rnd.nextLong(700 * DAY_MS) else null,
            isStarred = !complete && rnd.nextDouble() < 0.08, isMaybe = !complete && rnd.nextDouble() < 0.04,
            isHighPriority = !complete && rnd.nextDouble() < 0.04,
            dueDate = if (rnd.nextDouble() < 0.15) day(rnd.nextInt(-5, 30)) else null,
            startDate = if (rnd.nextDouble() < 0.10) day(rnd.nextInt(-10, 20)) else null,
            recurrenceType = if (repeat) (if (rnd.nextBoolean()) RecurrenceType.RRULE else RecurrenceType.AFTER_COMPLETION) else null,
            recurrenceRule = if (repeat) (if (rnd.nextBoolean()) "FREQ=WEEKLY;BYDAY=MO,TH" else "FREQ=MONTHLY") else null,
            notes = if (rnd.nextDouble() < 0.2) "some notes about it\nsecond line https://example.com" else null,
            durationMinutes = if (rnd.nextDouble() < 0.05) 30 else null
        ).let { if (repeat && it.recurrenceType == RecurrenceType.AFTER_COMPLETION) it.copy(recurrenceRule = "3") else it })
    }
    // Checklists, one of them with 30 checked and 10 open items in two sections.
    val checklist = add(Task(id = id(), type = TaskType.CHECKLIST, title = "Groceries", parentId = folders[0].id))
    (1..2).forEach { s ->
        val section = add(Task(id = id(), type = TaskType.SECTION, title = "Aisle $s", parentId = checklist.id))
        (1..20).forEach { i -> add(Task(id = id(), title = "item $s.$i", parentId = section.id, isComplete = i <= 15, completedAt = if (i <= 15) now - DAY_MS else null)) }
    }
    repeat(9) { c ->
        val list = add(Task(id = id(), type = TaskType.CHECKLIST, title = "List $c", parentId = folders.random(rnd).id))
        repeat(15) { add(Task(id = id(), title = "thing $it", parentId = list.id, isComplete = rnd.nextBoolean())) }
    }
    while (tasks.size < size) {
        val folder = folders.random(rnd).id
        when (rnd.nextInt(10)) {
            0 -> { val p = task(folder, TaskType.PROJECT); repeat(rnd.nextInt(3, 9)) { task(p.id) } }
            1 -> { val p = task(folder); repeat(rnd.nextInt(1, 4)) { task(p.id) } }
            2 -> add(Task(id = id(), type = TaskType.WAITING, title = "wait on ${seq}", parentId = folder))
            else -> task(folder)
        }
    }
    val open = tasks.filter { !it.isComplete && it.type == TaskType.TASK }
    tasks.filter { rnd.nextDouble() < 0.05 }.forEach { ctx[it.id] = setOf(if (rnd.nextBoolean()) home.id else work.id) }
    repeat(size / 50) {
        val (a, b) = open.random(rnd) to open.random(rnd)
        if (a.id < b.id) deps.getOrPut(b.id) { mutableSetOf() } += a.id // only older-first: no cycles
    }
    val plain = open.filter { it.recurrenceType == null && tasks.none { c -> c.parentId == it.id } && it.id !in deps }
    val windows = listOf(ContextTimeWindow(contextId = work.id, windowStartMinute = 540, windowEndMinute = 1020))
    return BenchData(tasks, listOf(home to emptyList(), work to windows), ctx, deps, plain.first().id, plain.drop(1).take(50).map { it.id }, checklist.id)
}
