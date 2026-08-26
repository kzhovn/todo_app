package com.kzhovn.todoapp.contexts

import android.app.TimePickerDialog
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.ui.theme.LedgerTitleFont
import com.kzhovn.todoapp.ui.theme.LedgerUiFont
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class ContextsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contextRepository = (application as TodoApp).contextRepository
        val wifiManager = getSystemService(WifiManager::class.java)
        setContent {
            LedgerTheme {
            var contexts by remember { mutableStateOf<List<TaskContext>>(emptyList()) }
            var editingContextId by remember { mutableStateOf<Long?>(null) }
            var name by remember { mutableStateOf("") }
            var type by remember { mutableStateOf(ContextType.PLACE) }
            var wifiSsid by remember { mutableStateOf<String?>(null) }
            var windows by remember { mutableStateOf<List<ContextTimeWindow>>(emptyList()) }
            val scope = rememberCoroutineScope()

            suspend fun refresh() { contexts = contextRepository.getAllContexts() }
            LaunchedEffect(Unit) { refresh() }

            fun resetForm() {
                editingContextId = null; name = ""; type = ContextType.PLACE
                wifiSsid = null; windows = emptyList()
            }

            fun loadForEdit(ctx: TaskContext) {
                scope.launch {
                    editingContextId = ctx.id
                    name = ctx.name
                    type = ctx.type
                    wifiSsid = ctx.wifiSsid
                    windows = if (ctx.type == ContextType.TIME) contextRepository.getTimeWindows(ctx.id) else emptyList()
                }
            }

            Column(modifier = Modifier.fillMaxSize().background(LedgerBackground).padding(16.dp)) {
                Text("Contexts", fontFamily = LedgerTitleFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerInk)
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(contexts, key = { it.id }) { ctx ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { loadForEdit(ctx) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (ctx.type == ContextType.PLACE) "${ctx.name} — Wifi: ${ctx.wifiSsid}" else ctx.name,
                                fontFamily = LedgerUiFont, fontSize = 14.sp, color = LedgerInk,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Delete ${ctx.name}",
                                tint = LedgerOverdue,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        contextRepository.deleteContext(ctx.id)
                                        refresh()
                                        if (editingContextId == ctx.id) resetForm()
                                    }
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(color = LedgerBorder)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (editingContextId == null) "New context" else "Edit context",
                    fontFamily = LedgerUiFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LedgerInk
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, placeholder = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row {
                    TypeOption("Place (wifi)", type == ContextType.PLACE) { type = ContextType.PLACE }
                    Spacer(Modifier.width(12.dp))
                    TypeOption("Time window", type == ContextType.TIME) { type = ContextType.TIME }
                }
                Spacer(Modifier.height(8.dp))
                if (type == ContextType.PLACE) {
                    Button(onClick = { wifiSsid = wifiManager?.connectionInfo?.ssid?.trim('"') }) {
                        Text(wifiSsid?.let { "Network: $it" } ?: "Use current network")
                    }
                } else {
                    Column {
                        windows.forEachIndexed { index, window ->
                            WindowRow(
                                window = window,
                                activity = this@ContextsActivity,
                                onChange = { updated -> windows = windows.toMutableList().also { it[index] = updated } },
                                onRemove = { windows = windows.toMutableList().also { it.removeAt(index) } }
                            )
                        }
                        Text(
                            "+ Add window",
                            color = LedgerAccent,
                            fontFamily = LedgerUiFont, fontSize = 13.sp,
                            modifier = Modifier
                                .clickable {
                                    windows = windows + ContextTimeWindow(contextId = editingContextId ?: 0, windowStartMinute = 540, windowEndMinute = 1020)
                                }
                                .padding(vertical = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(
                        onClick = {
                            scope.launch {
                                val contextToSave = TaskContext(
                                    id = editingContextId ?: 0,
                                    name = name,
                                    type = type,
                                    wifiSsid = if (type == ContextType.PLACE) wifiSsid else null,
                                    isCurrentlySatisfied = type == ContextType.PLACE && wifiSsid != null
                                )
                                val savedId = if (editingContextId != null) {
                                    contextRepository.updateContext(contextToSave)
                                    editingContextId!!
                                } else {
                                    contextRepository.createContext(contextToSave)
                                }
                                if (type == ContextType.TIME) {
                                    contextRepository.getTimeWindows(savedId).forEach { contextRepository.removeTimeWindow(it.id) }
                                    windows.forEach { contextRepository.addTimeWindow(it.copy(id = 0, contextId = savedId)) }
                                }
                                refresh()
                                resetForm()
                            }
                        },
                        enabled = name.isNotBlank() &&
                            (type == ContextType.PLACE && wifiSsid != null ||
                                type == ContextType.TIME && windows.isNotEmpty()),
                        colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)
                    ) {
                        Text(if (editingContextId == null) "Create" else "Save")
                    }
                    if (editingContextId != null) {
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { resetForm() }) { Text("Cancel") }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun TypeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontFamily = LedgerUiFont,
        fontSize = 13.sp,
        color = if (selected) LedgerAccent else LedgerMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) LedgerAccentSoft else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun WindowRow(
    window: ContextTimeWindow,
    activity: android.app.Activity,
    onChange: (ContextTimeWindow) -> Unit,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { pickTime(activity) { onChange(window.copy(windowStartMinute = it)) } }) {
                Text(minuteToLabel(window.windowStartMinute))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { pickTime(activity) { onChange(window.copy(windowEndMinute = it)) } }) {
                Text(minuteToLabel(window.windowEndMinute))
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove window",
                tint = LedgerOverdue,
                modifier = Modifier.clickable { onRemove() }
            )
        }
        DayOfWeekToggle(window.daysMask) { onChange(window.copy(daysMask = it)) }
    }
}

@Composable
private fun DayOfWeekToggle(daysMask: Int, onChange: (Int) -> Unit) {
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

private fun pickTime(activity: android.app.Activity, onPicked: (Int) -> Unit) {
    val cal = Calendar.getInstance()
    TimePickerDialog(
        activity,
        { _, hour, minute -> onPicked(hour * 60 + minute) },
        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
    ).show()
}

private fun minuteToLabel(minuteOfDay: Int): String {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    return String.format(Locale.US, "%02d:%02d", h, m)
}
