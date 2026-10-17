package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.Labels
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A Su=0 .. Sa=6 bitmask, shared by time-context windows and calendar recurrence, shown Monday first.

@Composable
fun DayOfWeekToggle(daysMask: Int, onChange: (Int) -> Unit) {
    Row {
        Labels.WEEKDAYS.forEach { (i, label) ->
            val bit = 1 shl i
            SelectablePill(label, selected = daysMask and bit != 0, fontSize = 11.sp, horizontalPadding = 6.dp, verticalPadding = 4.dp) { onChange(daysMask xor bit) }
        }
    }
}
