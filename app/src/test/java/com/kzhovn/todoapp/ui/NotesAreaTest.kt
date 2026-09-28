package com.kzhovn.todoapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotesAreaTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `tapping the preview edits the note with the cursor at the end, and typing appends`() {
        var notes: String? by mutableStateOf("ask about the fee")
        compose.setContent { NotesArea(notes.orEmpty(), titleFocused = false) { notes = it } }
        compose.onNodeWithText("ask about the fee").performClick()
        val field = compose.onNode(hasSetTextAction())
        field.assertIsFocused()
        assertEquals(TextRange(17), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field.performTextInput("!")
        assertEquals("ask about the fee!", notes)
    }

    @Test
    fun `typing into an empty note keeps the field, and leaving it shows the preview`() {
        var notes: String? by mutableStateOf(null)
        var titleFocused by mutableStateOf(true)
        compose.setContent {
            Column {
                BasicTextField("title", {}, Modifier.testTag("title"))
                NotesArea(notes.orEmpty(), titleFocused) { notes = it }
            }
        }
        compose.onNode(hasSetTextAction() and !androidx.compose.ui.test.hasTestTag("title")).performClick()
        titleFocused = false
        compose.onNode(hasSetTextAction() and !androidx.compose.ui.test.hasTestTag("title")).performTextInput("c")
        compose.onNode(hasSetTextAction() and !androidx.compose.ui.test.hasTestTag("title")).performTextInput("all")
        assertEquals("call", notes)
        // Focus moves elsewhere: back to the preview (no text field for the note).
        compose.onNodeWithTag("title").performClick()
        compose.onNodeWithText("call").assertExists()
        assertEquals(1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `tapping on the text puts the cursor there, and opening doesn't change the height`() {
        var notes: String? by mutableStateOf("ask about the fee")
        compose.setContent { NotesArea(notes.orEmpty(), titleFocused = false) { notes = it } }
        val preview = compose.onNodeWithText("ask about the fee")
        val height = preview.fetchSemanticsNode().boundsInRoot.height
        // A tap near the text's start: the cursor lands near there, not at the end.
        preview.performTouchInput { click(androidx.compose.ui.geometry.Offset(2f, centerY)) }
        val field = compose.onNode(hasSetTextAction())
        val caret = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange].start
        assert(caret < 3) { "caret at $caret" }
        assertEquals(height, field.fetchSemanticsNode().boundsInRoot.height, 0.5f)
    }
}
