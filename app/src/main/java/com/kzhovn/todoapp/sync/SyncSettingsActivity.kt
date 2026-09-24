package com.kzhovn.todoapp.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.ui.theme.LedgerTitleFont
import kotlinx.coroutines.launch

class SyncSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        val saved = SyncSettings.config(app)
        setContent {
            LedgerTheme {
                var url by remember { mutableStateOf(saved?.url ?: "https://") }
                var token by remember { mutableStateOf(saved?.token ?: "") }
                var status by remember { mutableStateOf(SyncSettings.status(app)) }
                var syncing by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                Column(Modifier.fillMaxSize().background(LedgerBackground).padding(16.dp)) {
                    Text("Sync", fontFamily = LedgerTitleFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerInk)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        token, { token = it }, label = { Text("API token") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !syncing && url.isNotBlank() && token.isNotBlank(),
                        onClick = {
                            SyncSettings.save(app, url, token)
                            syncing = true
                            scope.launch {
                                val outcome = runCatching { app.syncClient.sync(SyncSettings.config(app)!!) }
                                SyncSettings.recordResult(app, outcome)
                                SyncWorker.ensurePeriodic(app)
                                status = SyncSettings.status(app)
                                syncing = false
                            }
                        }
                    ) { Text(if (syncing) "Syncing…" else "Save and sync now") }
                    Spacer(Modifier.height(12.dp))
                    status?.let { Text(it, color = LedgerMuted, fontSize = 13.sp) }
                }
            }
        }
    }
}
