package com.kzhovn.todoapp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.MainActivity
import com.kzhovn.todoapp.repository.templateSize
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerAccentInk
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

// Templates (docs/superpowers/specs/2026-10-09-templates-design.md): the side panel's one entry for them. Each
// template's type, size and folder; tap to edit it, Use to make a list from it (opened in the editor).
class TemplatesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as TodoApp).repository
        setContent {
            LedgerTheme {
                var rows by remember { mutableStateOf<List<Pair<Task, String>>?>(null) }
                val scope = rememberCoroutineScope()
                LifecycleResumeEffect(Unit) {
                    scope.launch { rows = templateRows(repository) }
                    onPauseOrDispose { }
                }
                AppDrawer(rememberDrawerState(DrawerValue.Closed)) {
                    Column(Modifier.fillMaxSize().background(LedgerBackground).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Labels.TEMPLATES, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = LedgerInk, modifier = Modifier.weight(1f))
                            TextButton(onClick = { startActivity(newTemplateIntent(this@TemplatesActivity)) }) { Text(Labels.NEW_TEMPLATE, color = LedgerAccent) }
                        }
                        val shown = rows
                        if (shown != null && shown.isEmpty()) Text(Labels.NO_TEMPLATES, color = LedgerMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                        LazyColumn {
                            items(shown.orEmpty(), key = { it.first.id }) { (template, summary) ->
                                TemplateRow(template, summary, onOpen = { open(template.id) }) {
                                    scope.launch { repository.useTemplate(template.id, System.currentTimeMillis())?.let { opened(repository, it) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun open(id: Long) = startActivity(Intent(this, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_TASK_ID, id))

    // A list from the template opens in the editor; a workflow has started, so Doing shows instead.
    private suspend fun opened(repository: TaskRepository, id: Long) {
        if (!repository.isWorkflow(id)) return open(id)
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_MODE, TaskListMode.DOING.name)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        fun newTemplateIntent(context: Context): Intent = Intent(context, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_CREATE_AS_TEMPLATE, true)

        // Each template with its line: "Checklist · 24 items · Personal".
        suspend fun templateRows(repository: TaskRepository): List<Pair<Task, String>> {
            val everything = repository.getEverything()
            val byId = everything.associateBy { it.id }
            return repository.getTemplates().map { t -> t to Labels.templateSummary(t.type, templateSize(t, everything), byId[t.parentId]?.title) }
        }
    }
}

@Composable
private fun TemplateRow(template: Task, summary: String, onOpen: () -> Unit, onUse: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(template.title, fontSize = 16.sp, color = LedgerInk)
            Text(summary, fontSize = 12.sp, color = LedgerMuted)
        }
        Button(onClick = onUse, colors = ButtonDefaults.buttonColors(containerColor = LedgerAccent, contentColor = LedgerAccentInk)) { Text(Labels.USE) }
    }
    HorizontalDivider(color = LedgerBorder, thickness = 0.5.dp)
}
