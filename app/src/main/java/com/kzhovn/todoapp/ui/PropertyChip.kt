package com.kzhovn.todoapp.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.kzhovn.todoapp.data.chipDate
import com.kzhovn.todoapp.data.timeText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Shared by the quick-add overlay and the full edit screen. Always shows its label (e.g.
// "Start"/"Due") alongside the value, so two chips holding the same-shaped value (two dates, in
// particular) never look identical to each other the way the original quick-add chips did.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PropertyChip(
    label: String,
    valueText: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
    showLabelWhenSet: Boolean = true,
    // A set chip's colour, e.g. a folder's own; its fill is a pale version of it.
    tint: Color = LedgerAccent,
    iconOnly: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val set = valueText != null
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (!set) Color.Transparent else if (tint == LedgerAccent) LedgerAccentSoft else tint.copy(alpha = 0.14f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label.takeIf { iconOnly }, tint = if (set) tint else LedgerMuted, modifier = Modifier.size(14.dp))
        if (!iconOnly) {
            Spacer(Modifier.width(4.dp))
            val text = when {
                !set -> label
                showLabelWhenSet -> "$label: $valueText"
                else -> valueText ?: label
            }
            Text(
                text = text,
                fontSize = 12.sp,
                color = if (set) tint else LedgerMuted
            )
        }
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

// " 5:00 PM" when the value has a time, "" for a date-only value.
fun formatTimeSuffix(epochMillis: Long): String = if (hasTime(epochMillis)) " " + timeText(epochMillis) else ""

// A date, then OK; a time only if asked for ("+ Time"), like MLO. A value that already has a time
// keeps it on OK. "Today" jumps the calendar to today without closing.
fun pickDate(activity: Activity, currentValue: Long?, withTime: Boolean = true, title: String? = null, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance()
    if (currentValue != null) cal.timeInMillis = currentValue
    val existingTime = currentValue?.takeIf(::hasTime)
    val dialog = DatePickerDialog(activity, null, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
    dialog.datePicker.firstDayOfWeek = Calendar.MONDAY
    title?.let(dialog::setTitle)
    fun picked() = dialog.datePicker.let { Calendar.getInstance().apply { set(it.year, it.month, it.dayOfMonth) }.startOfDay() }
    dialog.setButton(DialogInterface.BUTTON_POSITIVE, "OK") { _, _ ->
        onPicked(existingTime?.let { atTime(picked(), cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)) } ?: picked())
    }
    if (withTime) dialog.setButton(DialogInterface.BUTTON_NEGATIVE, if (existingTime != null) "Time" else "+ Time") { _, _ ->
        val day = picked()
        TimePickerDialog(
            activity,
            { _, hour, minute -> onPicked(atTime(day, hour, minute)) },
            if (existingTime != null) cal.get(Calendar.HOUR_OF_DAY) else 9,
            if (existingTime != null) cal.get(Calendar.MINUTE) else 0,
            android.text.format.DateFormat.is24HourFormat(activity)
        ).apply { setButton(DialogInterface.BUTTON_NEUTRAL, "No time") { _, _ -> onPicked(day) } }.show()
    }
    dialog.setButton(DialogInterface.BUTTON_NEUTRAL, "Today") { _, _ -> }
    dialog.show()
    // Set after show() so the button doesn't close the dialog.
    dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
        val today = Calendar.getInstance()
        dialog.datePicker.updateDate(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH))
    }
}
