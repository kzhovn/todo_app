package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.repository.ContextStates
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ContextMenuTest {
    private val tuesday = LocalDate.of(2026, 10, 20)
    private fun at(hour: Int) = dayStart(tuesday) + hour * 3_600_000L

    @Test fun theWhyLineSaysWhenATimeContextChanges() {
        val errands = TaskContext(id = 1, name = "Errands", type = ContextType.TIME)
        val work = TaskContext(id = 2, name = "Work", type = ContextType.TIME)
        val windows = listOf(
            ContextTimeWindow(contextId = 1, windowStartMinute = 8 * 60 + 30, windowEndMinute = 21 * 60),
            ContextTimeWindow(contextId = 2, windowStartMinute = 9 * 60, windowEndMinute = 17 * 60, daysMask = 0b0001000) // We
        )
        fun why(hour: Int, id: Long) = ContextStates(listOf(errands, work), windows, emptyList(), at(hour)).why(id)
        assertEquals("until 21:00", why(16, 1))
        assertEquals("from 08:30", why(6, 1))
        assertEquals("from 08:30 tomorrow", why(22, 1))
        assertEquals("from 09:00 tomorrow", why(16, 2))
        assertEquals("switched On", ContextStates(listOf(work.copy(forced = true)), windows, emptyList(), at(16)).why(2))
    }

    @Test fun theWhyLineForWifiAndFilterOnly() {
        val home = TaskContext(id = 1, name = "Home", type = ContextType.PLACE, wifiSsid = "HomeNet", isCurrentlySatisfied = true)
        val calls = TaskContext(id = 2, name = "Calls", type = ContextType.LABEL)
        val states = ContextStates(listOf(home, calls), emptyList(), emptyList(), at(12))
        assertEquals("on HomeNet", states.why(1))
        assertEquals("filter only", states.why(2))
    }

    @Test fun theButtonRotatesAutoOnOffAndAFilterOnlyOneFlips() {
        val time = TaskContext(name = "Work", type = ContextType.TIME)
        assertEquals(listOf(true, false, null), listOf(null, true, false).map { nextForced(time.copy(forced = it)) })
        val label = TaskContext(name = "Calls", type = ContextType.LABEL)
        assertEquals(listOf(false, null), listOf(null, false).map { nextForced(label.copy(forced = it)) })
        assertEquals(listOf("On", "Off"), listOf(null, false).map { forcedLabel(label.copy(forced = it)) })
    }
}
