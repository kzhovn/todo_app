package com.kzhovn.todoapp

import android.content.Context
import com.kzhovn.todoapp.data.DEFAULT_ROLLOVER_HOUR
import kotlinx.coroutines.flow.MutableStateFlow

// Device-level preferences that aren't sync credentials.
object AppSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Hour (0-23) when "today" ends: expiring tasks are deleted at this time.
    fun rolloverHour(context: Context): Int = prefs(context).getInt("rolloverHour", DEFAULT_ROLLOVER_HOUR)

    // When it was last set (0: never), so the newer of this and the web's setting wins (see SyncRequest).
    fun rolloverSetAt(context: Context): Long = prefs(context).getLong("rolloverSetAt", 0)

    fun setRolloverHour(context: Context, hour: Int, setAt: Long = System.currentTimeMillis()) =
        prefs(context).edit().putInt("rolloverHour", hour).putLong("rolloverSetAt", setAt).apply()

    // Whether Discord posts the morning digest (the server does it; synced like the rollover hour).
    fun digestOn(context: Context): Boolean = prefs(context).getBoolean("digestOn", true)
    fun digestSetAt(context: Context): Long = prefs(context).getLong("digestSetAt", 0)
    fun setDigestOn(context: Context, on: Boolean, setAt: Long = System.currentTimeMillis()) =
        prefs(context).edit().putBoolean("digestOn", on).putLong("digestSetAt", setAt).apply()

    // Folder mode (FolderMode.kt): the folder every device is zoomed into (null: none), synced like the
    // rollover hour. modeChanges ticks on every change, so the lists and widgets redraw.
    fun modeFolderId(context: Context): Long? = prefs(context).getLong("modeFolderId", 0L).takeIf { it != 0L }
    fun modeSetAt(context: Context): Long = prefs(context).getLong("modeSetAt", 0)
    val modeChanges = MutableStateFlow(0)

    fun setMode(context: Context, folderId: Long?, setAt: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong("modeFolderId", folderId ?: 0L).putLong("modeSetAt", setAt).apply()
        modeChanges.value++
    }
}
