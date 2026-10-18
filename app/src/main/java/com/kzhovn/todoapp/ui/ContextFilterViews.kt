package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.repository.ContextSummary
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerMuted

// The top bar's @: the context filter's menu. Once one is picked it reads its name instead.
@Composable
fun ContextButton(summaries: List<ContextSummary>, picked: Long?, onPick: (Long?) -> Unit) {
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
            summaries.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ContextDot(s)
                            Spacer(Modifier.width(10.dp))
                            Text(s.context.name, fontWeight = if (picked == s.context.id) FontWeight.SemiBold else null, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(16.dp))
                            Text(s.countText, fontSize = 12.sp, color = LedgerMuted)
                        }
                    },
                    onClick = { pick(s.context.id) }
                )
            }
            HorizontalDivider(color = LedgerBorder)
            DropdownMenuItem(text = { Text(Labels.NO_CONTEXT, color = LedgerMuted) }, onClick = { pick(SearchFilters.NO_CONTEXT) })
        }
    }
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
