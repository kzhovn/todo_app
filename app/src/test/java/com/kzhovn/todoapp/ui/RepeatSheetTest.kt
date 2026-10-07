package com.kzhovn.todoapp.ui

import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

// The Repeat sheet is a dialog, whose context wraps the Activity (a plain cast crashed "Custom…" on the phone).
@RunWith(RobolectricTestRunner::class)
class RepeatSheetTest {
    @Test
    fun findsTheActivityInsideADialogsContext() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertSame(activity, ContextThemeWrapper(ContextThemeWrapper(activity, 0), 0).findActivity())
    }
}
