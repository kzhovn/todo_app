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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerAccentSoft
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
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
            var name by remember { mutableStateOf("") }
            var type by remember { mutableStateOf(ContextType.PLACE) }
            var wifiSsid by remember { mutableStateOf<String?>(null) }
            var startMinute by remember { mutableStateOf<Int?>(null) }
            var endMinute by remember { mutableStateOf<Int?>(null) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) { contexts = contextRepository.getAllContexts() }

            Column(modifier = Modifier.fillMaxSize().background(LedgerBackground).padding(16.dp)) {
                Text("Contexts", fontFamily = LedgerTitleFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerInk)
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(contexts, key = { it.id }) { ctx ->
                        Text(
                            text = if (ctx.type == ContextType.PLACE) {
                                "${ctx.name} — Wifi: ${ctx.wifiSsid}"
                            } else {
                                "${ctx.name} — time window"
                            },
                            fontFamily = LedgerUiFont, fontSize = 14.sp, color = LedgerInk,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                HorizontalDivider(color = LedgerBorder)
                Spacer(Modifier.height(12.dp))
                Text("New context", fontFamily = LedgerUiFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LedgerInk)
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
                    Row {
                        Button(onClick = { pickTime(this@ContextsActivity) { startMinute = it } }) {
                            Text(startMinute?.let { minuteToLabel(it) } ?: "Start time")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { pickTime(this@ContextsActivity) { endMinute = it } }) {
                            Text(endMinute?.let { minuteToLabel(it) } ?: "End time")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val contextId = contextRepository.createContext(
                                TaskContext(
                                    name = name,
                                    type = type,
                                    wifiSsid = if (type == ContextType.PLACE) wifiSsid else null,
                                    // "Use current network" captured the exact SSID we're on right now, so a
                                    // PLACE context starts satisfied — otherwise its tasks vanish from
                                    // Doing/Active until the next wifi connect/disconnect event.
                                    isCurrentlySatisfied = type == ContextType.PLACE && wifiSsid != null
                                )
                            )
                            if (type == ContextType.TIME && startMinute != null && endMinute != null) {
                                contextRepository.addTimeWindow(
                                    ContextTimeWindow(contextId = contextId, windowStartMinute = startMinute!!, windowEndMinute = endMinute!!)
                                )
                            }
                            name = ""
                            wifiSsid = null
                            startMinute = null
                            endMinute = null
                            contexts = contextRepository.getAllContexts()
                        }
                    },
                    enabled = name.isNotBlank() &&
                        (type == ContextType.PLACE && wifiSsid != null ||
                            type == ContextType.TIME && startMinute != null && endMinute != null),
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)
                ) {
                    Text("Create")
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
