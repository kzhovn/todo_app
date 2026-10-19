package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.repository.ContextStates
import com.kzhovn.todoapp.repository.applyTo
import com.kzhovn.todoapp.repository.computeActiveTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarEventsTest {
    private val today = LocalDate.of(2026, 10, 20) // a Tuesday
    private fun at(hour: Int, day: LocalDate = today) = dayStart(day) + hour * 3_600_000L
    private val now = at(13)
    private val dentist = CalendarEvent("p/dentist", "p", "Dentist", at(14), at(15))
    private val party = CalendarEvent("e/party", "e", "Maya's party", at(19, today.plusDays(4)), at(23, today.plusDays(4)))
    private val oncall = CalendarEvent("w/oncall", "w", "oncall for Payments", at(9), at(17))
    private val free = CalendarEvent("p/lunch", "p", "Lunch", at(12), at(14), busy = false)

    private val gift = Task(id = 1, title = "Buy Maya a gift")
    private val thanks = Task(id = 2, title = "Send thank-you note")
    private val tasks = listOf(gift, thanks)

    private fun linked(all: List<Task>, vararg links: Triple<Long, CalendarEvent, Boolean>): Pair<List<Task>, List<TaskDependency>> {
        var current = all
        val edges = mutableListOf<TaskDependency>()
        links.forEach { (taskId, event, before) ->
            val plan = planLinkEvent(current, taskId, event, before, now)
            current = plan.applyTo(current)
            edges += plan.links
        }
        return current to edges
    }

    @Test fun anEventIsMadeOnceAndLinkedBeforeOrAfter() {
        val (all, edges) = linked(tasks, Triple(1, party, true), Triple(2, party, false))
        val item = all.single { it.type == TaskType.EVENT }
        assertEquals(party.start, item.dueDate)
        assertEquals(240, item.durationMinutes)
        assertEquals(listOf(TaskDependency(item.id, 1), TaskDependency(2, item.id)), edges)
        assertEquals(EventLinks(listOf(gift), listOf(thanks)), linkedToEvent(item.id, all, edges))
    }

    @Test fun theSheetOffersTasksNotYetLinkedOnThatSide() {
        assertEquals(tasks, eventLinkCandidates(party, before = true, tasks, emptyList()).second)
        val (all, edges) = linked(tasks, Triple(1, party, true))
        assertEquals(listOf(thanks), eventLinkCandidates(party, before = true, all, edges).second)
        // Already before it, gift can't also come after it (a loop).
        assertEquals(listOf(thanks), eventLinkCandidates(party, before = false, all, edges).second)
    }

    @Test fun beforeIsDueWhenItStartsAndAfterWaitsTillItEnds() {
        val (all, edges) = linked(tasks, Triple(1, party, true), Triple(2, party, false))
        val due = DueDates(all, edges).of(gift)!!
        assertEquals(party.start, due.at)
        assertEquals("Maya's party", due.forTask?.title)
        assertEquals(listOf("Buy Maya a gift"), computeActiveTasks(all, emptyMap(), emptyList(), emptyList(), edges, now).map { it.title })
        // Over, though not resolved yet: the note's up.
        val after = computeActiveTasks(all, emptyMap(), emptyList(), emptyList(), edges, party.end + 1).map { it.title }
        assertEquals(listOf("Buy Maya a gift", "Send thank-you note"), after)
        assertTrue(all.single { it.type == TaskType.EVENT }.resolvesBy(party.end))
    }

    @Test fun itemsFollowTheirEventsAndACancelledOneResolves() {
        val (all, edges) = linked(tasks, Triple(1, party, true), Triple(2, party, false))
        val item = all.single { it.type == TaskType.EVENT }
        val window = CalendarData(events = listOf(party.copy(title = "Maya's 30th", start = party.start + 3_600_000L)), from = at(0), to = at(0, today.plusDays(30)))
        assertEquals(listOf("Maya's 30th" to party.start + 3_600_000L), planFollowEvents(all, edges, window, now).updates.map { it.title to it.dueDate })

        val cancelled = planFollowEvents(all, edges, window.copy(events = emptyList()), now).updates
        assertEquals(item.copy(eventCancelled = true, isComplete = true, completedAt = now), cancelled[0])
        assertEquals(gift.copy(dueDate = party.start), cancelled[1]) // keeps the due date it had
        assertEquals(listOf(item.id), cancelledEvents(planFollowEvents(all, edges, window.copy(events = emptyList()), now).applyTo(all)).map { it.id })
        // Outside what was read, gone means nothing.
        assertEquals(emptyList<Task>(), planFollowEvents(all, edges, window.copy(events = emptyList(), to = at(0, today.plusDays(2))), now).updates)
    }

    @Test fun relinkingMovesBothSidesToTheNewEvent() {
        val (all, edges) = linked(tasks, Triple(1, party, true), Triple(2, party, false))
        val old = all.single { it.type == TaskType.EVENT }
        val moved = party.copy(id = "e/party2", start = party.start + 86_400_000L, end = party.end + 86_400_000L)
        val plan = planRelinkEvent(all, edges, old.id, moved, now)
        val item = plan.creates.single().first
        assertEquals(setOf(old.id), plan.deletes)
        assertEquals(listOf(TaskDependency(item.id, 1), TaskDependency(2, item.id)), plan.links)
    }

    @Test fun anEventsContextHoldsDuringBusyMatchingEvents() {
        val busy = TaskContext(id = 1, name = "busy", type = ContextType.EVENTS, calendarIds = listOf("p", "w"))
        val onCall = TaskContext(id = 2, name = "oncall", type = ContextType.EVENTS, calendarIds = listOf("w"), titleWords = "pager, oncall for")
        val errands = TaskContext(id = 3, name = "errands", type = ContextType.LABEL, offWhile = listOf(1))
        val events = listOf(dentist, oncall, free)
        fun states(at: Long, vararg contexts: TaskContext) = ContextStates(contexts.toList(), emptyList(), events, at)
        // 13:00: on call (busy too); the free lunch doesn't count.
        assertEquals(listOf(true, true, false), listOf(1L, 2L, 3L).map { states(now, busy, onCall, errands).holds(it) })
        assertEquals(listOf(false, false, true), listOf(1L, 2L, 3L).map { states(at(18), busy, onCall, errands).holds(it) })
        assertFalse(states(at(12) + 1, busy.copy(calendarIds = listOf("p"))).holds(1))
        assertTrue(states(at(12) + 1, busy.copy(calendarIds = listOf("p"), busyOnly = false)).holds(1))
        // By hand: busy Off brings errands back.
        assertTrue(states(now, busy.copy(forced = false), errands).holds(3))
        assertEquals(oncall, states(now, busy, errands).eventNow(3))
        // Only the other's own state counts, so two off while each other don't loop.
        val x = TaskContext(id = 4, name = "x", type = ContextType.LABEL, offWhile = listOf(3))
        assertEquals(listOf(false, false), listOf(3L, 4L).map { states(at(18), errands.copy(offWhile = listOf(4)), x).holds(it) })
    }

    @Test fun theAgendaMixesEventsInByTime() {
        val allDay = CalendarEvent("p/trip", "p", "Trip", dayStart(today), dayStart(today.plusDays(2)), allDay = true)
        val rent = Task(id = 3, title = "Pay rent", dueDate = dayStart(today))
        val bank = Task(id = 4, title = "Call bank", dueDate = at(14) + 1_800_000L)
        val days = agenda(listOf(rent, bank), today..today.plusDays(1), null, DEFAULT_DATE_KINDS, now, 9, events = listOf(dentist, allDay))
        assertEquals(listOf(today, today.plusDays(1)), days.map { it.day })
        val titles = days[0].rows().map { when (it) { is AgendaRow.Event -> it.event.title; is AgendaRow.Entry -> it.entry.task.title } }
        assertEquals(listOf("Dentist", "Call bank", "Pay rent"), titles)
        assertEquals(listOf("Trip"), days[0].allDayEvents().map { it.title })
        assertEquals(listOf("Trip"), days[1].events.map { it.title })
        assertNull(agenda(listOf(rent), today..today, null, setOf(DateKind.DUE), now, 9, events = listOf(dentist)).single().events.firstOrNull())
    }

    @Test fun snoozeAndNowNextLookAhead() {
        val tomorrow = CalendarEvent("w/shift", "w", "Shift", at(9, today.plusDays(1)), at(17, today.plusDays(1)))
        val later = CalendarEvent("w/later", "w", "Later", at(9, today.plusDays(2)), at(10, today.plusDays(2)))
        assertEquals(listOf("oncall for Payments", "Dentist", "Shift"), snoozeEvents(listOf(later, tomorrow, dentist, oncall, free.copy(end = at(12, today))), now).map { it.title })
        assertEquals(listOf("oncall for Payments", "Dentist"), nowAndNext(listOf(tomorrow, dentist, oncall), now).map { it.title })
    }

    @Test fun eventsStayOutOfTheTreeSearchAndPickers() {
        val (all, edges) = linked(tasks, Triple(1, party, true))
        assertEquals(listOf("Buy Maya a gift", "Send thank-you note"), buildOutlinerTree(all).map { it.task.title })
        assertEquals(listOf("Send thank-you note"), linkCandidates(gift, LinkKind.PREREQUISITE, all, edges).map { it.title })
        assertFalse(searchMatcher(all, emptyMap(), "maya", SearchFilters())(all.single { it.type == TaskType.EVENT }))
    }
}
