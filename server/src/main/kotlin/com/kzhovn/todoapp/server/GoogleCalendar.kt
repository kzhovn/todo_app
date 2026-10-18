package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.CalendarData
import com.kzhovn.todoapp.data.CalendarEvent
import com.kzhovn.todoapp.data.CalendarInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Google Calendar, read-only (docs/superpowers/specs/2026-10-18-google-calendar-design.md): every calendar the
// account sees, and their events from a month back to a year ahead, which TaskService keeps and sync hands on.
// ponytail: read whole every few minutes; Google's sync tokens would fetch only changes if it gets slow or nears quota.
class GoogleCalendarApi(private val google: GoogleClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(from: Long, to: Long): CalendarData {
        val calendars = pages<GCalendarList> { token -> "$BASE/users/me/calendarList?maxResults=250" + page(token) }.flatMap { it.items }
        val events = calendars.flatMap { cal ->
            val query = "singleEvents=true&maxResults=2500&timeMin=${enc(Instant.ofEpochMilli(from).toString())}&timeMax=${enc(Instant.ofEpochMilli(to).toString())}"
            pages<GEvents> { token -> "$BASE/calendars/${enc(cal.id)}/events?$query" + page(token) }.flatMap { it.items }
                .mapNotNull { it.toEvent(cal.id, ZoneId.systemDefault()) }
        }
        return CalendarData(calendars.map { CalendarInfo(it.id, it.summaryOverride ?: it.summary, it.backgroundColor) }, events, from = from, to = to)
    }

    private inline fun <reified T : Paged> pages(url: (String?) -> String): List<T> {
        val all = mutableListOf<T>()
        var token: String? = null
        do {
            val next = json.decodeFromString<T>(google.send(HttpRequest.newBuilder(URI(url(token))).GET()))
            all += next
            token = next.nextPageToken
        } while (token != null)
        return all
    }

    private fun page(token: String?) = token?.let { "&pageToken=${enc(it)}" }.orEmpty()
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private companion object {
        const val BASE = "https://www.googleapis.com/calendar/v3"
    }
}

internal interface Paged { val nextPageToken: String? }

@Serializable internal data class GCalendarList(val items: List<GCalendar> = emptyList(), override val nextPageToken: String? = null) : Paged
@Serializable internal data class GCalendar(val id: String, val summary: String = "", val summaryOverride: String? = null, val backgroundColor: String? = null)
@Serializable internal data class GEvents(val items: List<GEvent> = emptyList(), override val nextPageToken: String? = null) : Paged
@Serializable internal data class GTime(val date: String? = null, val dateTime: String? = null)
@Serializable internal data class GAttendee(val self: Boolean = false, val responseStatus: String? = null)

@Serializable
internal data class GEvent(
    val id: String, val status: String? = null, val summary: String? = null, val location: String? = null, val htmlLink: String? = null,
    val start: GTime? = null, val end: GTime? = null, val transparency: String? = null, val attendees: List<GAttendee> = emptyList()
) {
    // Cancelled ones and ones declined are left out. An all-day one ("date") starts at the day's start here.
    fun toEvent(calendarId: String, zone: ZoneId): CalendarEvent? {
        if (status == "cancelled" || attendees.any { it.self && it.responseStatus == "declined" }) return null
        fun millis(t: GTime?): Long? = t?.dateTime?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            ?: t?.date?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() }
        val start = millis(start) ?: return null
        return CalendarEvent(
            "$calendarId/$id", calendarId, summary?.takeIf { it.isNotBlank() } ?: "(no title)", start, millis(end) ?: start,
            allDay = this.start?.dateTime == null, busy = transparency != "transparent", location = location, link = htmlLink
        )
    }
}

// Reads the calendars every few minutes into TaskService. Logged, so a broken connection (or a sign-in without the
// calendar scope) shows in the journal.
object GoogleCalendarRead {
    private const val DAY = 86_400_000L

    fun readOnce(service: TaskService, api: GoogleCalendarApi) {
        val now = service.now()
        service.setCalendar(api.read(now - 30 * DAY, now + 365 * DAY))
    }

    fun start(service: TaskService, api: GoogleCalendarApi) {
        println("Google Calendar on: reading every 3 minutes")
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            runCatching { readOnce(service, api) }.onFailure { println("Google Calendar read failed: ${it.message}") }
        }, 5, 180, TimeUnit.SECONDS)
    }
}
