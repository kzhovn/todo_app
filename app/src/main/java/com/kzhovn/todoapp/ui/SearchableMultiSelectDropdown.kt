package com.kzhovn.todoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerUiFont

// Generic dropdown-based multi-select: tap the summary row to open a checkbox list, with an
// optional search field (Depends-on needs it — task lists can get long) and an optional
// trailing "create new" row (Contexts needs it; Depends-on doesn't, tasks are created elsewhere).
@Composable
fun <T> SearchableMultiSelectDropdown(
    label: String,
    items: List<T>,
    selectedIds: Set<Long>,
    idOf: (T) -> Long,
    labelOf: (T) -> String,
    onToggle: (Long) -> Unit,
    searchable: Boolean = false,
    onCreateNew: (() -> Unit)? = null,
    createNewLabel: String = "+ Create new"
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selectedLabels = items.filter { idOf(it) in selectedIds }.map { labelOf(it) }
    val filtered = if (searchable && query.isNotBlank()) {
        items.filter { labelOf(it).contains(query, ignoreCase = true) }
    } else items

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selectedLabels.isEmpty()) label else "$label: ${selectedLabels.joinToString(", ")}",
                fontFamily = LedgerUiFont,
                fontSize = 13.sp,
                color = if (selectedLabels.isEmpty()) LedgerMuted else LedgerInk,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = LedgerMuted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (searchable) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.padding(horizontal = 8.dp).width(240.dp)
                )
            }
            LazyColumn(modifier = Modifier.height((filtered.size.coerceIn(1, 6) * 40).dp)) {
                items(filtered, key = { idOf(it) }) { item ->
                    val id = idOf(item)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = id in selectedIds, onCheckedChange = { onToggle(id) })
                        Text(labelOf(item), fontFamily = LedgerUiFont, fontSize = 13.sp, color = LedgerInk)
                    }
                }
            }
            if (onCreateNew != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = false; onCreateNew() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = LedgerAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(createNewLabel, fontFamily = LedgerUiFont, fontSize = 13.sp, color = LedgerAccent)
                }
            }
        }
    }
}
