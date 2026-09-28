package com.kzhovn.todoapp

import android.content.Context
import com.kzhovn.todoapp.data.DEFAULT_ROLLOVER_HOUR

// Device-level preferences that aren't sync credentials.
object AppSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Hour (0-23) when "today" ends: expiring tasks are deleted at this time.
    fun rolloverHour(context: Context): Int = prefs(context).getInt("rolloverHour", DEFAULT_ROLLOVER_HOUR)

    // When it was last set (0: never), so the newer of this and the web's setting wins (see SyncRequest).
    fun rolloverSetAt(context: Context): Long = prefs(context).getLong("rolloverSetAt", 0)

    fun setRolloverHour(context: Context, hour: Int, setAt: Long = System.currentTimeMillis()) =
        prefs(context).edit().putInt("rolloverHour", hour).putLong("rolloverSetAt", setAt).apply()
}
