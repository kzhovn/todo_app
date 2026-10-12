package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.repository.TemplateChange
import com.kzhovn.todoapp.repository.describe
import com.kzhovn.todoapp.repository.tickedAtFirst
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerGood
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import com.kzhovn.todoapp.ui.theme.LedgerUp
import com.kzhovn.todoapp.ui.theme.LedgerUpSoft

// Above a template's title: it's out of the lists, and Use makes a list from it.
@Composable
internal fun TemplateBanner(onUse: () -> Unit) = Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).background(LedgerUpSoft, RoundedCornerShape(8.dp)).padding(start = 12.dp, end = 6.dp)
) {
    Text(Labels.TEMPLATE_BANNER, fontSize = 13.sp, color = LedgerUp, modifier = Modifier.weight(1f))
    Button(onClick = onUse, colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)) { Text(Labels.USE) }
}

// Under a list's title, when it was made from a template: "From template Packing · Update template…".
@Composable
internal fun FromTemplateLine(template: Task, onOpenTemplate: () -> Unit, onUpdate: () -> Unit) = Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
) {
    Text(Labels.fromTemplate(template.title), fontSize = 12.sp, color = LedgerAccent, modifier = Modifier.clickable(onClick = onOpenTemplate))
    Text("·", fontSize = 12.sp, color = LedgerMuted)
    Text(Labels.UPDATE_TEMPLATE, fontSize = 12.sp, color = LedgerAccent, modifier = Modifier.clickable(onClick = onUpdate))
}

// "Update template": what's different in this list, each ticked or not (removals start unticked); an added
// one can be marked "just this time" instead. Update writes the ticked ones into the template.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateTemplateSheet(
    template: Task, changes: List<TemplateChange>, byId: Map<Long, Task>,
    onUpdate: (chosen: Set<String>, oneOff: Set<String>) -> Unit, onDismiss: () -> Unit
) {
    var chosen by remember(changes) { mutableStateOf(changes.filter { it.tickedAtFirst() }.map { it.key }.toSet()) }
    var oneOff by remember(changes) { mutableStateOf(emptySet<String>()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LedgerSearchBackground) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(Labels.updateTemplateTitle(template.title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(if (changes.isEmpty()) Labels.TEMPLATE_UNCHANGED else Labels.UPDATE_TEMPLATE_HINT, fontSize = 13.sp, color = LedgerMuted, modifier = Modifier.padding(bottom = 8.dp))
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                changes.forEach { change ->
                    val (text, tag) = describe(change, byId)
                    val once = change.key in oneOff
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = change.key in chosen,
                            onCheckedChange = { on -> chosen = if (on) chosen + change.key else chosen - change.key; if (on) oneOff = oneOff - change.key },
                            colors = CheckboxDefaults.colors(checkedColor = LedgerAccent)
                        )
                        Text(
                            buildAnnotatedString {
                                append(text)
                                withStyle(SpanStyle(color = tagColor(tag), fontSize = 11.sp)) { append("  $tag") }
                            },
                            fontSize = 14.sp, color = if (once) LedgerMuted else Color.Unspecified, modifier = Modifier.weight(1f)
                        )
                        if (change is TemplateChange.Added) TextButton(onClick = {
                            oneOff = if (once) oneOff - change.key else oneOff + change.key
                            if (!once) chosen = chosen - change.key
                        }) { Text(Labels.JUST_THIS_TIME, fontSize = 11.sp, color = if (once) LedgerAccent else LedgerMuted) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(Labels.CANCEL, color = LedgerMuted) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onUpdate(chosen, oneOff) }, enabled = chosen.isNotEmpty() || oneOff.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)
                ) { Text(Labels.UPDATE_TEMPLATE_GO) }
            }
        }
    }
}

private fun tagColor(tag: String) = when (tag) {
    "added" -> LedgerGood
    "removed" -> LedgerOverdue
    else -> LedgerUp
}
