package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerCheckBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground

// The top bar's search: a pill reading "Search tasks"; tapping it starts searching right there.
@Composable
fun SearchBar(query: String, searching: Boolean, onStart: () -> Unit, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search tasks") {
    val shape = RoundedCornerShape(20.dp)
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 15.sp, color = LedgerInk),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = modifier.height(40.dp).onFocusChanged { if (it.isFocused && !searching) onStart() },
        decorationBox = { field ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize().background(LedgerSearchBackground, shape).border(1.dp, if (searching) LedgerAccent else LedgerBorder, shape).padding(horizontal = 12.dp)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = LedgerMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(placeholder, fontSize = 15.sp, color = LedgerCheckBorder)
                    field()
                }
            }
        }
    )
}
