package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.hasTime
import com.kzhovn.todoapp.data.atTime
import java.text.DateFormat
import android.content.DialogInterface
import android.app.TimePickerDialog
import android.app.Activity
import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.quickadd.startOfDay
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Shared by the quick-add overlay and the full edit screen. Always shows its label (e.g.
// "Start"/"Due") alongside the value, so two chips holding the same-shaped value (two dates, in
// particular) never look identical to each other the way the original quick-add chips did.
@Composable
fun PropertyChip(
    label: String,
    valueText: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
    showLabelWhenSet: Boolean = true
) {
    val set = valueText != null
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (set) LedgerAccentSoft else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (set) LedgerAccent else LedgerMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        val text = when {
            !set -> label
            showLabelWhenSet -> "$label: $valueText"
            else -> valueText ?: label
        }
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (set) LedgerAccent else LedgerMuted
        )
        if (set && onClear != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier.size(20.dp).clickable { onClear() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear $label",
                    tint = LedgerMuted,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

fun formatChipDate(epochMillis: Long): String =
    SimpleDateFormat("MMM d", Locale.US).format(Date(epochMillis)) + formatTimeSuffix(epochMillis)

// " 5:00 PM" when the value has a time, "" for a date-only value.
fun formatTimeSuffix(epochMillis: Long): String =
    if (hasTime(epochMillis)) " " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis)) else ""

// Date first, then an optional time: dismissing the time dialog (or "No time") keeps the value
// date-only, or keeps its existing time if it had one.
fun pickDate(activity: Activity, currentValue: Long?, withTime: Boolean = true, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance()
    if (currentValue != null) cal.timeInMillis = currentValue
    val existingTime = currentValue?.takeIf(::hasTime)
    DatePickerDialog(
        activity,
        { _, year, month, day ->
            val picked = Calendar.getInstance().apply { set(year, month, day) }.startOfDay()
            onPicked(existingTime?.let { atTime(picked, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)) } ?: picked)
            if (!withTime) return@DatePickerDialog
            TimePickerDialog(
                activity,
                { _, hour, minute -> onPicked(atTime(picked, hour, minute)) },
                if (existingTime != null) cal.get(Calendar.HOUR_OF_DAY) else 9,
                if (existingTime != null) cal.get(Calendar.MINUTE) else 0,
                android.text.format.DateFormat.is24HourFormat(activity)
            ).apply {
                setButton(DialogInterface.BUTTON_NEUTRAL, "No time") { _, _ -> onPicked(picked) }
            }.show()
        },
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
    ).apply { datePicker.firstDayOfWeek = Calendar.MONDAY }.show()
}
