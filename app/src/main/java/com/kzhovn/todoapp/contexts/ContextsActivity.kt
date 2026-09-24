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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.ui.ConfirmDialog
import com.kzhovn.todoapp.ui.DayOfWeekToggle
import com.kzhovn.todoapp.ui.SelectablePill
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerTheme
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
            var wifiSsidError by remember { mutableStateOf<String?>(null) }
            var windows by remember { mutableStateOf<List<ContextTimeWindow>>(emptyList()) }
            // Preserves the stored satisfaction state while editing a PLACE context, since
            // wifiSsid gets prefilled from the saved row (not freshly captured) and may not
            // reflect the network the device is on right now.
            var editingIsCurrentlySatisfied by remember { mutableStateOf(false) }
            var contextPendingDelete by remember { mutableStateOf<TaskContext?>(null) }
            val scope = rememberCoroutineScope()

            suspend fun refresh() { contexts = contextRepository.getAllContexts() }
            LaunchedEffect(Unit) { refresh() }

            fun resetForm() {
                editingContextId = null; name = ""; type = ContextType.PLACE
                wifiSsid = null; windows = emptyList(); wifiSsidError = null
            }

            fun loadForEdit(ctx: TaskContext) {
                scope.launch {
                    editingContextId = ctx.id
                    name = ctx.name
                    type = ctx.type
                    wifiSsid = ctx.wifiSsid
                    wifiSsidError = null
                    editingIsCurrentlySatisfied = ctx.isCurrentlySatisfied
                    windows = if (ctx.type == ContextType.TIME) contextRepository.getTimeWindows(ctx.id) else emptyList()
                }
            }

            Column(modifier = Modifier.fillMaxSize().background(LedgerBackground).padding(16.dp)) {
                Text("Contexts", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerInk)
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(contexts, key = { it.id }) { ctx ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { loadForEdit(ctx) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (ctx.type == ContextType.PLACE) "${ctx.name} — Wifi: ${ctx.wifiSsid}" else ctx.name,
                                fontSize = 14.sp, color = LedgerInk,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Delete ${ctx.name}",
                                tint = LedgerOverdue,
                                modifier = Modifier.clickable { contextPendingDelete = ctx }
                            )
                        }
                    }
                }
                HorizontalDivider(color = LedgerBorder)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (editingContextId == null) "New context" else "Edit context",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LedgerInk
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, placeholder = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row {
                    SelectablePill(label = "Place (wifi)", selected = type == ContextType.PLACE, onClick = { type = ContextType.PLACE })
                    Spacer(Modifier.width(12.dp))
                    SelectablePill(label = "Time window", selected = type == ContextType.TIME, onClick = { type = ContextType.TIME })
                }
                Spacer(Modifier.height(8.dp))
                if (type == ContextType.PLACE) {
                    Column {
                        Button(onClick = {
                            val ssid = wifiManager?.connectionInfo?.ssid?.trim('"')
                            if (ssid == null || ssid == WifiManager.UNKNOWN_SSID) {
                                wifiSsid = null
                                wifiSsidError = "Couldn't read your network name — check that Location " +
                                    "is turned on and this app has Location permission, then try again."
                            } else {
                                wifiSsid = ssid
                                wifiSsidError = null
                            }
                        }) {
                            Text(wifiSsid?.let { "Network: $it" } ?: "Use current network")
                        }
                        wifiSsidError?.let { error ->
                            Text(
                                error,
                                fontSize = 11.sp,
                                color = LedgerOverdue,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
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
                            fontSize = 13.sp,
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
                                    isCurrentlySatisfied = if (editingContextId != null) {
                                        editingIsCurrentlySatisfied
                                    } else {
                                        type == ContextType.PLACE && wifiSsid != null
                                    }
                                )
                                val currentEditingId = editingContextId
                                val savedId = if (currentEditingId != null) {
                                    contextRepository.updateContext(contextToSave)
                                    currentEditingId
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
            contextPendingDelete?.let { ctx ->
                ConfirmDialog(
                    title = "Delete this context?",
                    body = "\"${ctx.name}\" will be removed from every task using it. This can't be undone.",
                    confirmLabel = "Delete",
                    onConfirm = {
                        contextPendingDelete = null
                        scope.launch {
                            contextRepository.deleteContext(ctx.id)
                            refresh()
                            if (editingContextId == ctx.id) resetForm()
                        }
                    },
                    onDismiss = { contextPendingDelete = null }
                )
            }
            }
        }
    }
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
