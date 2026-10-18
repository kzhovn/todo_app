package com.kzhovn.todoapp.quickadd

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

// The Quick Settings tile: quick add from the pull-down shade, over whatever app is open.
class QuickAddTile : TileService() {
    override fun onClick() {
        val intent = Intent(this, QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // From Android 14 the Intent form throws for apps targeting it.
        val open = {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
        if (isLocked) unlockAndRun(open) else open()
    }
}
