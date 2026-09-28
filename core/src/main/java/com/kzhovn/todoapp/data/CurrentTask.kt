package com.kzhovn.todoapp.data

// The current task (docs/superpowers/specs/2026-09-27-focus-everywhere-design.md): the pin, with an
// optional timer and focus session on it. All three are fields on the task, synced like any other; these
// rules keep them on one task. Each returns the tasks to write, for the phone and the server alike.
object CurrentTask {
    private const val MINUTE = 60_000L

    // A focus session, if one is running: the newest focus, even once its task is done (every device
    // then asks "Done! Next or finish?" until one answers).
    fun focusSession(tasks: Collection<Task>): Task? = tasks.filter { it.focusedAt != null }.maxByOrNull { it.focusedAt!! }

    fun pin(all: Collection<Task>, id: Long, now: Long): List<Task> = place(all, id, now) { it }

    // Pins the task too; a running focus session moves to it.
    fun startTimer(all: Collection<Task>, id: Long, minutes: Int, now: Long): List<Task> =
        place(all, id, now) { it.copy(timerEndsAt = now + minutes * MINUTE, timerRemaining = null) }

    fun focus(all: Collection<Task>, id: Long, now: Long): List<Task> = place(all, id, now) { it.copy(focusedAt = now) }

    // Unpinning ends the timer and any focus session too. Stopping the timer and leaving focus are the
    // same thing (both unpin).
    fun unpin(all: Collection<Task>): List<Task> = all.filter(::isCurrent).map(::cleared)

    fun pauseTimer(all: Collection<Task>, now: Long): List<Task> = listOfNotNull(
        pinnedTask(all)?.takeIf { it.timerEndsAt != null }?.let { it.copy(timerEndsAt = null, timerRemaining = (it.timerEndsAt!! - now).coerceAtLeast(0)) }
    )

    fun resumeTimer(all: Collection<Task>, now: Long): List<Task> = listOfNotNull(
        pinnedTask(all)?.takeIf { it.timerRemaining != null }?.let { it.copy(timerEndsAt = now + it.timerRemaining!!, timerRemaining = null) }
    )

    // "Not yet" when time's up: the timer runs again for this long.
    fun addTime(all: Collection<Task>, minutes: Int, now: Long): List<Task> = listOfNotNull(
        pinnedTask(all)?.let { it.copy(timerEndsAt = now + minutes * MINUTE, timerRemaining = null) }
    )

    // Everything else loses its pin, timer and focus. Pinning a different task stops the old task's timer
    // (it was that task's), and a running focus session moves along to the new one.
    private fun place(all: Collection<Task>, id: Long, now: Long, change: (Task) -> Task): List<Task> {
        val task = all.firstOrNull { it.id == id } ?: return emptyList()
        val focusing = focusSession(all) != null
        val same = pinnedTask(all)?.id == id
        val base = if (same) task else task.copy(timerEndsAt = null, timerRemaining = null)
        val placed = change(base.copy(pinnedAt = if (same) task.pinnedAt else now, focusedAt = if (focusing) task.focusedAt ?: now else task.focusedAt))
        return all.filter { it.id != id && isCurrent(it) }.map(::cleared) + placed
    }

    private fun isCurrent(t: Task) = t.pinnedAt != null || t.timerEndsAt != null || t.timerRemaining != null || t.focusedAt != null

    private fun cleared(t: Task) = t.copy(pinnedAt = null, timerEndsAt = null, timerRemaining = null, focusedAt = null)
}

// Completing a task stops its timer; its pin and focus stay, so a focus session can ask what's next
// (and undoing the completion brings the pin back).
fun Task.completed(now: Long): Task = copy(isComplete = true, completedAt = now, timerEndsAt = null, timerRemaining = null)
