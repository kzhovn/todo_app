package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerMuted

// Shared "selectable pill" shape — was ContextsActivity's private TypeOption and FilterPanel's
// private FilterChipItem, identical apart from font size/padding, which both callers now pass
// explicitly to preserve their exact current look.
@Composable
fun SelectablePill(
    label: String,
    selected: Boolean,
    fontSize: TextUnit = 13.sp,
    horizontalPadding: Dp = 10.dp,
    verticalPadding: Dp = 6.dp,
    onClick: () -> Unit
) {
    Text(
        label,
        fontSize = fontSize,
        color = if (selected) LedgerAccent else LedgerMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) LedgerAccentSoft else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    )
}
