package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerUiFont

// Su=0 .. Sa=6 bitmask, shared by time-context windows and calendar recurrence.
@Composable
fun DayOfWeekToggle(daysMask: Int, onChange: (Int) -> Unit) {
    val labels = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
    Row {
        labels.forEachIndexed { i, label ->
            val bit = 1 shl i
            val on = (daysMask and bit) != 0
            Text(
                label,
                fontFamily = LedgerUiFont,
                fontSize = 11.sp,
                color = if (on) LedgerAccent else LedgerMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) LedgerAccentSoft else Color.Transparent)
                    .clickable { onChange(daysMask xor bit) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}
