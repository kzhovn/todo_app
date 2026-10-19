package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.CalendarData
import com.kzhovn.todoapp.data.CalendarEvent
import com.kzhovn.todoapp.data.CalendarInfo
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder

// Google Calendar on the web: Doing's lines, the event sheet's add, linking from Related, the snooze, a cancel.
class WebCalendarTest {
    private val store = Store(File.createTempFile("web", ".db").apply { deleteOnExit() }.path)
    // Noon, so the events a few hours either side stay on today's Doing whenever the tests run.
    private var now = java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val service = TaskService(store) { now }
    private val hour = 3_600_000L
    private val meeting = CalendarEvent("p/m1", "p", "Team meeting", now - hour, now + hour)
    private val party = CalendarEvent("e/p1", "e", "Maya's party", now + 3 * hour, now + 5 * hour)
    private val data = CalendarData(listOf(CalendarInfo("p", "Personal", "#7986CB"), CalendarInfo("e", "Events")), listOf(meeting, party), from = now - 24 * hour, to = now + 240 * hour)
    private fun web(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { api(store, "token", service, webEnabled = true) }
        block()
    }
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    @Test
    fun `doing shows what's on, the sheet adds before and after, related links an event, and snooze goes till it ends`() = web {
        service.setCalendar(data)
        val doing = client.get("/doing").bodyAsText()
        assertTrue(doing.contains("Team meeting") && doing.contains("Maya"))

        client.submitForm("/event/add?id=${enc(party.id)}&mode=DOING", parameters { append("text", "Buy a gift"); append("kind", "before") })
        client.submitForm("/event/add?id=${enc(party.id)}&mode=DOING", parameters { append("text", "Thank-you note"); append("kind", "after") })
        val sheet = client.get("/event?id=${enc(party.id)}&mode=DOING").bodyAsText().substringAfter("event-sheet")
        assertTrue(sheet.indexOf("Buy a gift") in sheet.indexOf("Before it")..sheet.indexOf("After it"))
        assertTrue(sheet.indexOf("Thank-you note") > sheet.indexOf("After it"))
        // An existing task, as the editor's field suggests it; already before it, the gift isn't offered after.
        val cake = service.create(Task(title = "Bake a cake"))
        assertTrue(client.get("/event/suggest?id=${enc(party.id)}&kind=before&text=cake").bodyAsText().contains("value=\"${cake.id}\""))
        assertFalse(client.get("/event/suggest?id=${enc(party.id)}&kind=after&text=gift").bodyAsText().contains("Buy a gift"))
        client.submitForm("/event/add?id=${enc(party.id)}&mode=DOING", parameters { append("existing", cake.id.toString()); append("kind", "before") })
        assertEquals(party.start, service.dueDates().at(service.get(cake.id)!!))

        val notes = service.create(Task(title = "Write up notes"))
        assertTrue(client.get("/tasks/${notes.id}/related/suggest?kind=prerequisite&text=team").bodyAsText().contains("value=\"p/m1\""))
        client.submitForm("/tasks/${notes.id}/related?mode=ALL", parameters { append("kind", "prerequisite"); append("event", meeting.id) })
        assertEquals("Team meeting", service.dependsOn(notes.id).single().let(service::get)?.title)
        assertTrue(service.active().none { it.id == notes.id })

        val dryCleaning = service.create(Task(title = "Dry cleaning"))
        assertTrue(client.get("/tasks/${dryCleaning.id}/snooze-events?mode=DOING").bodyAsText().contains("Maya"))
        client.post("/tasks/${dryCleaning.id}/snooze?event=${enc(party.id)}&mode=DOING")
        assertEquals(party.end, service.get(dryCleaning.id)!!.startDate)
    }

    @Test
    fun `a cancelled event shows in doing, and deleting its tasks can be undone`() = web {
        service.setCalendar(data)
        val gift = service.create(Task(title = "Buy a gift"))
        service.linkEvent(gift.id, party.id, before = true)
        service.setCalendar(data.copy(events = listOf(meeting)))
        val item = service.tasks().single { it.type == TaskType.EVENT }
        assertTrue(client.get("/doing").bodyAsText().contains("Cancelled"))
        val response = client.post("/event-item/${item.id}/delete?mode=DOING").bodyAsText()
        assertEquals(emptyList<Task>(), service.tasks())
        val at = Regex("restore\\?at=(\\d+)").find(response)!!.groupValues[1]
        client.post("/event-item/restore?at=$at&mode=DOING")
        assertEquals(setOf("Buy a gift", "Maya's party"), service.tasks().map { it.title }.toSet())
    }
}
