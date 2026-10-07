package com.kzhovn.todoapp.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The All tree: press and hold a task (without dragging) opens the same menu as the lists' rows.
@RunWith(RobolectricTestRunner::class)
class OutlinerMenuTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun pressAndHoldOpensTheRowMenu() {
        compose.setContent {
            LedgerTheme { OutlinerScreen(listOf(Task(id = 1, title = "Water the plants")), onCheck = {}, onEdit = {}, onStar = {}, onReparent = { _, _ -> }, onAddSubtask = {}) }
        }
        compose.onNodeWithText("Water the plants").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText(Labels.SNOOZE).assertExists()
    }
}
