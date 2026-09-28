package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted

private val URL = Regex("https?://\\S+")

// A note's text with its links tappable (trailing punctuation isn't part of the link).
fun linkified(text: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    URL.findAll(text).forEach { m ->
        val url = m.value.trimEnd('.', ',', ')', ';', ':', '!', '?')
        append(text.substring(at, m.range.first))
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = LedgerAccent, textDecoration = TextDecoration.Underline)))) { append(url) }
        at = m.range.first + url.length
    }
    append(text.substring(at))
}

// The task's note, inside the title box: collapsed to two lines, tap for all of it, editable. With no
// note it only appears while the title is being edited, as a faint "Notes" line.
@Composable
internal fun NotesArea(notes: String, titleFocused: Boolean, onChange: (String?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    if (notes.isBlank() && !titleFocused && !focused && !editing) return
    Box(Modifier.fillMaxWidth().height(1.dp).background(LedgerBorder.copy(alpha = 0.5f)))
    val padding = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
    if (notes.isNotBlank() && !editing) {
        var overflows by remember { mutableStateOf(false) }
        Column(padding.clickable { editing = true }) {
            Text(linkified(notes), fontSize = 14.sp, lineHeight = 20.sp, color = LedgerInk, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = { overflows = it.hasVisualOverflow })
            if (overflows) Text("more", fontSize = 13.sp, color = LedgerAccent)
        }
        return
    }
    val requester = remember { FocusRequester() }
    // Opened from the preview, the cursor starts at the end, ready to add to the note.
    var field by remember(editing) { mutableStateOf(TextFieldValue(notes, TextRange(notes.length))) }
    BasicTextField(
        value = field,
        onValueChange = { field = it; onChange(it.text.ifEmpty { null }) },
        textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = LedgerInk),
        modifier = padding.focusRequester(requester).onFocusChanged { f ->
            // In the field (an empty note too, so the first letter typed doesn't swap it for the
            // preview) until focus leaves; then back to the collapsed preview.
            if (f.isFocused) editing = true else if (focused) editing = false
            focused = f.isFocused
        },
        decorationBox = { field ->
            if (notes.isEmpty()) Text("Notes", fontSize = 14.sp, color = LedgerMuted)
            field()
        }
    )
    LaunchedEffect(editing) { if (editing) requester.requestFocus() }
}
