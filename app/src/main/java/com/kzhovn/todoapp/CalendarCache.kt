package com.kzhovn.todoapp

import android.content.Context
import com.kzhovn.todoapp.data.CalendarData
import com.kzhovn.todoapp.data.CalendarEvent
import com.kzhovn.todoapp.sync.SyncJson
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

// Google Calendar as the server last sent it (see CalendarData in :core), in a file so it's there offline. The
// calendars hidden in Settings sync like the other settings. `changes` ticks when either changes, so lists redraw.
object CalendarCache {
    private var cached: CalendarData? = null
    val changes = MutableStateFlow(0)

    private fun file(context: Context) = File(context.filesDir, "calendar.json")

    @Synchronized
    fun data(context: Context): CalendarData = cached ?: runCatching { SyncJson.decodeFromString(CalendarData.serializer(), file(context).readText()) }
        .getOrDefault(CalendarData()).also { cached = it }

    @Synchronized
    fun set(context: Context, data: CalendarData) {
        file(context).writeText(SyncJson.encodeToString(CalendarData.serializer(), data))
        cached = data
        changes.value++
    }

    // Events contexts go by every calendar; what you see, by the shown ones.
    fun events(context: Context): List<CalendarEvent> = data(context).events
    fun shownEvents(context: Context): List<CalendarEvent> = data(context).shown(hidden(context))

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun hidden(context: Context): Set<String> = prefs(context).getStringSet("hiddenCalendars", null).orEmpty()
    fun hiddenSetAt(context: Context): Long = prefs(context).getLong("hiddenCalendarsSetAt", 0)
    fun setHidden(context: Context, ids: Set<String>, setAt: Long = System.currentTimeMillis()) {
        prefs(context).edit().putStringSet("hiddenCalendars", ids).putLong("hiddenCalendarsSetAt", setAt).apply()
        changes.value++
    }
    fun setShown(context: Context, id: String, shown: Boolean) = setHidden(context, if (shown) hidden(context) - id else hidden(context) + id)
}
