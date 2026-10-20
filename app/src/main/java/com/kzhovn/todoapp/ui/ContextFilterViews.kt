package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.forcedLabel
import com.kzhovn.todoapp.data.forcedShown
import com.kzhovn.todoapp.repository.ContextSummary
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerChip
import com.kzhovn.todoapp.ui.theme.LedgerGood
import com.kzhovn.todoapp.ui.theme.LedgerGoodSoft
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted

// The top bar's @: the context filter's menu. Once one is picked it reads its name instead. A row's name filters by
// it; its button rotates Auto/On/Off and leaves the menu open.
@Composable
fun ContextButton(summaries: List<ContextSummary>, picked: Long?, onPick: (Long?) -> Unit, onCycle: (TaskContext) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = if (picked == SearchFilters.NO_CONTEXT) Labels.NO_CONTEXT else summaries.firstOrNull { it.context.id == picked }?.context?.name
    Box {
        if (name == null) IconButton(onClick = { open = true }) { Icon(Icons.Filled.AlternateEmail, contentDescription = Labels.CONTEXT, tint = LedgerMuted) }
        else Text(
            "@$name", color = LedgerAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp).background(LedgerAccentSoft, RoundedCornerShape(6.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(id: Long?) { open = false; onPick(id) }
            DropdownMenuItem(text = { Text(Labels.ANY_CONTEXT, fontWeight = if (picked == null) FontWeight.SemiBold else null) }, onClick = { pick(null) })
            HorizontalDivider(color = LedgerBorder)
            summaries.filter { it.inFilter }.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ContextDot(s)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.context.name, fontWeight = if (picked == s.context.id) FontWeight.SemiBold else null)
                                s.why?.let { Text(it, fontSize = 11.sp, color = LedgerMuted) }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("${s.open}", fontSize = 12.sp, color = LedgerMuted)
                            Spacer(Modifier.width(8.dp))
                            ForcedButton(s.context) { onCycle(s.context) }
                        }
                    },
                    onClick = { pick(s.context.id) }
                )
            }
            HorizontalDivider(color = LedgerBorder)
            DropdownMenuItem(text = { Text(Labels.NO_CONTEXT, color = LedgerMuted) }, onClick = { pick(SearchFilters.NO_CONTEXT) })
            HorizontalDivider(color = LedgerBorder)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                listOf(true to false, false to false, true to true).zip(Labels.CONTEXT_DOTS).forEach { (dot, word) ->
                    ContextDot(holds = dot.first, label = dot.second)
                    Text(word, fontSize = 10.sp, color = LedgerMuted, modifier = Modifier.padding(start = 4.dp, end = 10.dp))
                }
            }
        }
    }
}

// A context's Auto/On/Off as one button showing where it is now; a tap moves it on (nextForced). Auto is outlined
// (it runs itself), On and Off filled (set by hand), matching the dots.
@Composable
fun ForcedButton(context: TaskContext, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val forced = forcedShown(context)
    Text(
        forcedLabel(context), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        color = when (forced) { null -> LedgerMuted; true -> LedgerGood; false -> LedgerInk },
        modifier = Modifier.width(48.dp).clip(shape)
            .then(when (forced) { null -> Modifier.border(1.dp, LedgerBorder, shape); true -> Modifier.background(LedgerGoodSoft); false -> Modifier.background(LedgerChip) })
            .clickable(onClick = onClick).padding(vertical = 4.dp)
    )
}

// Under the tabs while filtering by a context: which, and that one off now is shown as if on. ✕ clears a picked
// one (a typed "@home" goes with the search's text).
@Composable
fun ContextFilterBar(contextId: Long, summaries: List<ContextSummary>, picked: Boolean, onClear: () -> Unit) {
    val summary = summaries.firstOrNull { it.context.id == contextId }
    val text = if (contextId == SearchFilters.NO_CONTEXT) Labels.NO_CONTEXT else summary?.let { Labels.contextFilter(it.context.name, it.holds) } ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(LedgerAccentSoft, RoundedCornerShape(6.dp)).padding(start = 10.dp)
    ) {
        Text(text, color = LedgerAccent, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        if (picked) Text("✕", color = LedgerAccent, modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
