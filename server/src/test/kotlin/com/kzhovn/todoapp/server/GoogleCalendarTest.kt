package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.CalendarData
import com.kzhovn.todoapp.data.CalendarEvent
import com.kzhovn.todoapp.data.CalendarInfo
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.sync.SyncRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class GoogleCalendarTest {
    private val store = Store(File.createTempFile("todo", ".db").apply { deleteOnExit() }.path)
    private var now = 1_800_000_000_000L
    private val service = TaskService(store) { now }
    private val hour = 3_600_000L
    private val party = CalendarEvent("e/party", "e", "Maya's party", now + 50 * hour, now + 54 * hour)
    private val dentist = CalendarEvent("p/dentist", "p", "Dentist", now + hour, now + 2 * hour)
    private fun data(vararg events: CalendarEvent) =
        CalendarData(listOf(CalendarInfo("p", "Personal"), CalendarInfo("e", "Events")), events.toList(), from = now - 30 * 24 * hour, to = now + 365 * 24 * hour)

    @Test
    fun `google's events are read as ours, without cancelled or declined ones`() {
        val json = Json { ignoreUnknownKeys = true }
        val zone = ZoneId.of("Europe/Berlin")
        val events = json.decodeFromString<GEvents>(
            """{"items": [
              {"id": "a", "summary": "Dentist", "location": "12 Elm St", "htmlLink": "https://g/a",
               "start": {"dateTime": "2026-10-20T14:00:00+02:00"}, "end": {"dateTime": "2026-10-20T15:00:00+02:00"}},
              {"id": "b", "summary": "Trip", "transparency": "transparent", "start": {"date": "2026-10-24"}, "end": {"date": "2026-10-26"}},
              {"id": "c", "status": "cancelled"},
              {"id": "d", "summary": "Standup", "start": {"dateTime": "2026-10-20T10:00:00Z"}, "end": {"dateTime": "2026-10-20T10:15:00Z"},
               "attendees": [{"self": true, "responseStatus": "declined"}]}
            ]}"""
        ).items.mapNotNull { it.toEvent("p", zone) }
        assertEquals(listOf("p/a", "p/b"), events.map { it.id })
        assertEquals(CalendarEvent("p/a", "p", "Dentist", 1792497600000L, 1792501200000L, location = "12 Elm St", link = "https://g/a"), events[0])
        assertEquals(LocalDate.of(2026, 10, 24).atStartOfDay(zone).toInstant().toEpochMilli(), events[1].start)
        assertTrue(events[1].allDay)
        assertFalse(events[1].busy)
    }

    @Test
    fun `a read is versioned only when it changes, and phones get it once`() {
        service.setCalendar(data(party))
        val first = store.calendar().version
        assertTrue(first > 0)
        now += 1000
        service.setCalendar(data(party))
        assertEquals(first, store.calendar().version)
        assertEquals(listOf("Maya's party"), store.sync(SyncRequest(0, emptyList())).calendar?.events?.map { it.title })
        assertNull(store.sync(SyncRequest(0, emptyList(), calendarVersion = first)).calendar)
        service.setCalendar(data(party, dentist))
        assertTrue(store.calendar().version > first)
    }

    @Test
    fun `linking before and after, following a move, and a cancel`() {
        service.setCalendar(data(party))
        val gift = service.create(Task(title = "Buy a gift")).id
        val note = service.create(Task(title = "Thank-you note")).id
        service.linkEvent(gift, "e/party", before = true)
        service.linkEvent(note, "e/party", before = false)
        val item = service.tasks().single { it.type == TaskType.EVENT }
        assertEquals(setOf(gift), service.dependsOn(item.id))
        assertEquals(setOf(item.id), service.dependsOn(note))
        assertEquals(party.start, service.dueDates().of(service.get(gift)!!)?.at)
        assertEquals(listOf("Buy a gift"), service.active().map { it.title })

        service.setCalendar(data(party.copy(start = party.start + hour)))
        assertEquals(party.start + hour, service.get(item.id)!!.dueDate)

        service.setCalendar(data())
        assertTrue(service.get(item.id)!!.eventCancelled)
        assertEquals(party.start + hour, service.get(gift)!!.dueDate)
        assertEquals(listOf("Buy a gift", "Thank-you note"), service.active().map { it.title })
        service.deleteEventTasks(item.id)
        assertEquals(emptyList<Task>(), service.tasks())
    }

    @Test
    fun `an events context holds back its tasks during a busy event, and hidden calendars sync`() {
        service.setCalendar(data(dentist.copy(start = now - hour)))
        val busy = service.saveContext(TaskContext(name = "busy", type = ContextType.EVENTS, calendarIds = listOf("p")), emptyList()).id
        val errands = service.saveContext(TaskContext(name = "errands", type = ContextType.LABEL, offWhile = listOf(busy)), emptyList()).id
        service.create(Task(title = "Dry cleaning"), contextIds = setOf(errands))
        assertEquals(emptyList<String>(), service.active().map { it.title })
        now += 2 * hour
        assertEquals(listOf("Dry cleaning"), service.active().map { it.title })

        service.setCalendarShown("p", shown = false)
        assertEquals(listOf("p"), store.sync(SyncRequest(0, emptyList())).hiddenCalendars)
        assertEquals(emptyList<CalendarEvent>(), service.shownEvents())
        store.sync(SyncRequest(0, emptyList(), hiddenCalendars = emptyList(), hiddenCalendarsSetAt = now + 10))
        assertEquals(emptySet<String>(), service.hiddenCalendars())
    }
}
