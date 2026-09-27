package com.kzhovn.todoapp.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.recurrence.NTH_NAMES
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.RecurrenceUnit
import com.kzhovn.todoapp.recurrence.WEEKDAY_NAMES
import com.kzhovn.todoapp.recurrence.ordinal
import com.kzhovn.todoapp.recurrence.recurrencePresets
import com.kzhovn.todoapp.recurrence.toTaskFields
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import com.kzhovn.todoapp.ui.theme.LedgerTile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Repeat: one-tap presets worded from the task's date, then "After completion…" and "Custom…"
// open the builder (every N days/weeks/months, which days, when it ends, and a preview).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatSheet(current: RecurrenceSelection, anchor: Long, onDone: (RecurrenceSelection) -> Unit, onDismiss: () -> Unit) {
    val presets = remember(anchor) { recurrencePresets(anchor) }
    var custom by remember { mutableStateOf<RecurrenceSelection?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LedgerSearchBackground) {
        Column(Modifier.padding(bottom = 16.dp)) {
            val building = custom
            if (building == null) {
                Title(Labels.REPEAT)
                PresetRow("Don't repeat", null, current.preset == RecurrencePreset.NONE) { onDone(RecurrenceSelection(RecurrencePreset.NONE)) }
                presets.forEach { (label, selection) -> PresetRow(label, null, current == selection) { onDone(selection) } }
                HorizontalDivider(color = LedgerBorder, modifier = Modifier.padding(vertical = 4.dp))
                val isAfter = current.preset == RecurrencePreset.AFTER_COMPLETION_N_DAYS
                val isCustom = current.preset == RecurrencePreset.CALENDAR && presets.none { it.second == current }
                PresetRow("${Labels.AFTER_COMPLETION}…", Labels.repeat(current)?.takeIf { isAfter }, isAfter) {
                    custom = if (isAfter) current else RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 3)
                }
                PresetRow("Custom…", Labels.repeat(current)?.takeIf { isCustom } ?: "›", isCustom) {
                    custom = if (current.preset == RecurrencePreset.CALENDAR) current else RecurrenceSelection(RecurrencePreset.CALENDAR, unit = RecurrenceUnit.WEEK)
                }
            } else {
                Builder(building, anchor, onChange = { custom = it })
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { custom = null }) { Text("Back", color = LedgerMuted) }
                    TextButton(onClick = { onDone(building) }) { Text("Done", color = LedgerAccent, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
private fun Builder(s: RecurrenceSelection, anchor: Long, onChange: (RecurrenceSelection) -> Unit) {
    val schedule = s.preset == RecurrencePreset.CALENDAR
    Title(if (schedule) "Custom repeat" else Labels.AFTER_COMPLETION)
    Segmented(listOf("On a schedule" to true, Labels.AFTER_COMPLETION to false), schedule) { toSchedule ->
        onChange(s.copy(preset = if (toSchedule) RecurrencePreset.CALENDAR else RecurrencePreset.AFTER_COMPLETION_N_DAYS))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (schedule) Text("Every", fontSize = 14.sp, color = LedgerInk, modifier = Modifier.padding(end = 8.dp))
        CompactNumberField(s.n) { onChange(s.copy(n = it)) }
        Spacer(Modifier.width(8.dp))
        listOf(RecurrenceUnit.DAY to "day", RecurrenceUnit.WEEK to "week", RecurrenceUnit.MONTH to "month").forEach { (unit, name) ->
            SelectablePill(if (s.n == 1) name else "${name}s", s.unit == unit) { onChange(s.copy(unit = unit)) }
        }
    }
    if (!schedule) Text("after completion", fontSize = 13.sp, color = LedgerMuted, modifier = Modifier.padding(horizontal = 16.dp))

    if (schedule && s.unit == RecurrenceUnit.WEEK) Box(Modifier.padding(horizontal = 16.dp)) { DayOfWeekToggle(s.weekdaysMask) { onChange(s.copy(weekdaysMask = it)) } }
    if (schedule && s.unit == RecurrenceUnit.MONTH) {
        val dayOfMonth = Calendar.getInstance().apply { timeInMillis = anchor }.get(Calendar.DAY_OF_MONTH)
        RadioRow(s.monthlyNth == null, { onChange(s.copy(monthlyNth = null)) }) { Text("On the ${ordinal(dayOfMonth)}", fontSize = 14.sp, color = LedgerInk) }
        RadioRow(s.monthlyNth != null, { if (s.monthlyNth == null) onChange(s.copy(monthlyNth = 1)) }) {
            Text("On the", fontSize = 14.sp, color = LedgerInk)
            Spacer(Modifier.width(8.dp))
            Choice(NTH_NAMES, s.monthlyNth ?: 1) { onChange(s.copy(monthlyNth = it)) }
            Spacer(Modifier.width(6.dp))
            Choice(WEEKDAY_NAMES.withIndex().map { it.index to it.value }, s.monthlyWeekday) { onChange(s.copy(monthlyNth = s.monthlyNth ?: 1, monthlyWeekday = it)) }
        }
    }

    if (schedule) {
        Text("ENDS", fontSize = 11.sp, color = LedgerMuted, letterSpacing = 0.5.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
        val activity = LocalContext.current as Activity
        RadioRow(s.until == null && s.count == null, { onChange(s.copy(until = null, count = null)) }) { Text("Never", fontSize = 14.sp, color = LedgerInk) }
        RadioRow(s.until != null, { pickDate(activity, s.until, withTime = false) { onChange(s.copy(until = it, count = null)) } }) {
            Text("On", fontSize = 14.sp, color = LedgerInk)
            Spacer(Modifier.width(8.dp))
            Token(s.until?.let { SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(it)) } ?: "pick a date") {
                pickDate(activity, s.until, withTime = false) { onChange(s.copy(until = it, count = null)) }
            }
        }
        RadioRow(s.count != null, { onChange(s.copy(count = s.count ?: 10, until = null)) }) {
            Text("After", fontSize = 14.sp, color = LedgerInk)
            Spacer(Modifier.width(8.dp))
            CompactNumberField(s.count ?: 10) { onChange(s.copy(count = it, until = null)) }
            Spacer(Modifier.width(8.dp))
            Text("times", fontSize = 14.sp, color = LedgerInk)
        }
    }

    // What the rule will actually do.
    val now = System.currentTimeMillis()
    val (type, rule) = s.toTaskFields()
    val next = if (type == null || rule == null) emptyList() else RecurrenceEngine.preview(type, rule, anchor, now)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(LedgerTile).padding(10.dp)) {
        Text(Labels.repeatPreviewLabel(afterCompletion = !schedule).uppercase(), fontSize = 10.sp, color = LedgerMuted, letterSpacing = 0.5.sp)
        Text(
            Labels.repeatPreview(next),
            fontSize = 13.sp, color = LedgerInk, modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = LedgerInk, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))

@Composable
private fun PresetRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(if (selected) LedgerAccentSoft else LedgerSearchBackground).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(label, fontSize = 14.sp, color = if (selected) LedgerAccent else LedgerInk, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
        detail?.let { Text(it, fontSize = 12.sp, color = LedgerMuted) }
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, LedgerBorder, RoundedCornerShape(6.dp))) {
        options.forEach { (label, value) ->
            val on = value == selected
            Text(
                label, fontSize = 13.sp, color = if (on) LedgerAccent else LedgerMuted, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f).background(if (on) LedgerAccentSoft else LedgerSearchBackground).clickable { onSelect(value) }.padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun RadioRow(selected: Boolean, onSelect: () -> Unit, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 4.dp).height(44.dp)) {
        RadioButton(selected = selected, onClick = onSelect, colors = RadioButtonDefaults.colors(selectedColor = LedgerAccent, unselectedColor = LedgerBorder), modifier = Modifier.size(40.dp))
        content()
    }
}

// A tappable value in a sentence ("first ▾"), with its choices in a small menu.
@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Token("${options.first { it.first == selected }.second} ▾") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = LedgerSearchBackground) {
            options.forEach { (value, label) -> DropdownMenuItem(text = { Text(label, fontSize = 14.sp) }, onClick = { open = false; onSelect(value) }) }
        }
    }
}

@Composable
private fun Token(text: String, onClick: () -> Unit) {
    Text(
        text, fontSize = 14.sp, color = LedgerAccent, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, LedgerBorder, RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

@Composable
internal fun CompactNumberField(value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    BasicTextField(
        value = text,
        onValueChange = { new ->
            text = new
            new.toIntOrNull()?.takeIf { it > 0 }?.let(onValueChange)
        },
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = LedgerInk),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(44.dp).border(1.dp, LedgerBorder, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 8.dp)
    )
}
