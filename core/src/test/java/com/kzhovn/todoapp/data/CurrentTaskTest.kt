package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentTaskTest {
    private val a = Task(id = 1, title = "A")
    private val b = Task(id = 2, title = "B")

    // Applies the returned writes, as the phone and server do.
    private fun List<Task>.apply(changes: List<Task>) = map { t -> changes.firstOrNull { it.id == t.id } ?: t }

    @Test
    fun `starting a timer pins its task, and pinning another stops it`() {
        var all = listOf(a, b).apply(CurrentTask.startTimer(listOf(a, b), a.id, 25, now = 1_000))
        assertEquals(a.id, pinnedTask(all)?.id)
        assertEquals(1_000 + 25 * 60_000L, all[0].timerEndsAt)

        all = all.apply(CurrentTask.pin(all, b.id, now = 2_000))
        assertEquals(b.id, pinnedTask(all)?.id)
        assertNull(all[0].timerEndsAt) // the old task's timer stopped
        assertNull(all[0].pinnedAt)
    }

    @Test
    fun `pause and resume keep the time left`() {
        var all = listOf(a).apply(CurrentTask.startTimer(listOf(a), a.id, 10, now = 0))
        all = all.apply(CurrentTask.pauseTimer(all, now = 4 * 60_000L))
        assertEquals(6 * 60_000L, all[0].timerRemaining)
        assertNull(all[0].timerEndsAt)
        all = all.apply(CurrentTask.resumeTimer(all, now = 100 * 60_000L))
        assertEquals(106 * 60_000L, all[0].timerEndsAt)
    }

    @Test
    fun `a focus session follows the pin, survives completion, and ends with unpinning`() {
        var all = listOf(a, b).apply(CurrentTask.focus(listOf(a, b), a.id, now = 1))
        assertEquals(a.id, CurrentTask.focusSession(all)?.id)
        assertEquals(a.id, pinnedTask(all)?.id)

        // Done: the session stays on the done task (every device asks what's next); its timer stops.
        all = all.map { if (it.id == a.id) it.copy(timerEndsAt = 99).completed(5) else it }
        assertEquals(a.id, CurrentTask.focusSession(all)?.id)
        assertNull(all[0].timerEndsAt)

        // The next task: the session moves to it.
        all = all.apply(CurrentTask.pin(all, b.id, now = 6))
        assertEquals(b.id, CurrentTask.focusSession(all)?.id)

        // Leaving (unpin) ends it everywhere.
        all = all.apply(CurrentTask.unpin(all))
        assertNull(CurrentTask.focusSession(all))
        assertNull(pinnedTask(all))
    }
}
