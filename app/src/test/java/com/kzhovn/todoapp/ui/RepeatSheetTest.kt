package com.kzhovn.todoapp.ui

import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// "Custom…" opens the builder inside the sheet (a dialog), whose "Ends" choice needs the Activity.
@RunWith(RobolectricTestRunner::class)
class RepeatSheetTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun customOpensTheBuilder() {
        compose.setContent { LedgerTheme { RepeatSheet(RecurrenceSelection(RecurrencePreset.NONE), System.currentTimeMillis(), onDone = {}, onDismiss = {}) } }
        compose.onNodeWithText("Custom…").performClick()
        compose.onAllNodesWithText("Never").onFirst().assertExists()
    }
}
