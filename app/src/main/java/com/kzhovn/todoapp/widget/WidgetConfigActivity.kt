package com.kzhovn.todoapp.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.ui.SelectablePill
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

// Per-widget settings: which list it shows and an optional folder filter. Opened by tapping the
// widget's header, or via the launcher's "widget settings" (reconfigurable in the widget info).
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_CANCELED, result)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        val repository = (application as TodoApp).repository

        setContent {
            LedgerTheme {
                var mode by remember { mutableStateOf(WidgetMode.DOING) }
                var folderId by remember { mutableStateOf<Long?>(null) }
                var folders by remember { mutableStateOf<List<Task>>(emptyList()) }

                LaunchedEffect(Unit) {
                    val prefs = getAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)
                    mode = prefs.widgetMode()
                    folderId = prefs[WIDGET_FOLDER_KEY]
                    folders = repository.getFolders().sortedBy { it.title.lowercase() }
                }

                Column(
                    Modifier.fillMaxSize().background(LedgerBackground).verticalScroll(rememberScrollState()).padding(16.dp)
                ) {
                    Text("Widget", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerInk)
                    Spacer(Modifier.height(16.dp))
                    Text("List", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LedgerInk)
                    Row(Modifier.padding(vertical = 8.dp)) {
                        WidgetMode.entries.forEach {
                            SelectablePill(it.label, selected = mode == it, fontSize = 15.sp) { mode = it }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Folder", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LedgerInk)
                    Column(Modifier.padding(vertical = 8.dp)) {
                        SelectablePill("Any folder", selected = folderId == null, fontSize = 15.sp) { folderId = null }
                        folders.forEach { folder ->
                            Spacer(Modifier.height(6.dp))
                            SelectablePill(folder.title, selected = folderId == folder.id, fontSize = 15.sp) { folderId = folder.id }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        lifecycleScope.launch {
                            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                                prefs[WIDGET_MODE_KEY] = mode.name
                                folderId?.let { prefs[WIDGET_FOLDER_KEY] = it } ?: prefs.remove(WIDGET_FOLDER_KEY)
                            }
                            TodoWidget().update(this@WidgetConfigActivity, glanceId)
                            setResult(RESULT_OK, result)
                            finish()
                        }
                    }) { Text("Save") }
                }
            }
        }
    }
}
