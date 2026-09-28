package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.ui.theme.folderColors
import com.kzhovn.todoapp.data.walkParentChain
import com.kzhovn.todoapp.data.sectionsByTopFolder
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.Labels
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import android.content.Intent
import android.os.Bundle
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import com.kzhovn.todoapp.MainActivity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.AppSettings
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.repository.DayCompletions
import com.kzhovn.todoapp.repository.completionsByDay
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CHART_DAYS = 14
private const val LIST_DAYS = 30

class ReviewActivity : ComponentActivity() {
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        setContent {
            LedgerTheme {
                var days by remember { mutableStateOf<List<DayCompletions>>(emptyList()) }
                var byId by remember { mutableStateOf<Map<Long, Task>>(emptyMap()) }
                var colors by remember { mutableStateOf<Map<Long, Color>>(emptyMap()) }
                LaunchedEffect(Unit) {
                    val all = app.repository.getAllTasks()
                    byId = all.associateBy { it.id }
                    colors = folderColors(all)
                    days = completionsByDay(all, System.currentTimeMillis(), AppSettings.rolloverHour(app), LIST_DAYS)
                }
                // Grouped and coloured by top-level folder (Active's sections), each in its family colour.
                val colorOf = { folder: Task? -> folder?.let { colors[it.id] } ?: LedgerMuted }
                val week = days.take(7).sumOf { it.tasks.size }

                AppDrawer(rememberDrawerState(DrawerValue.Closed), reviewSelected = true) {
                LazyColumn(Modifier.fillMaxSize().background(LedgerBackground).padding(horizontal = 16.dp)) {
                    item {
                        Text("Review", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerInk, modifier = Modifier.padding(top = 16.dp))
                        Text(
                            "$week done in the last 7 days · ${"%.1f".format(week / 7.0)} a day",
                            fontSize = 13.sp, color = LedgerMuted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        )
                        val chartDays = days.take(CHART_DAYS).reversed()
                        CompletionChart(chartDays.map { day -> sectionsByTopFolder(day.tasks, byId).map { (folder, tasks) -> colorOf(folder) to tasks.size } }, chartDays)
                        // The legend: every folder in the chart, in tree order.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                            sectionsByTopFolder(chartDays.flatMap { it.tasks }, byId).forEach { (folder, _) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(colorOf(folder)))
                                    Text(folder?.title ?: Labels.NO_FOLDER, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    items(days.filter { it.tasks.isNotEmpty() }, key = { it.dayStart }) { day ->
                        Column(Modifier.padding(bottom = 14.dp)) {
                            Text(
                                SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(day.dayStart)) + " · ${day.tasks.size}",
                                fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LedgerInk
                            )
                            sectionsByTopFolder(day.tasks, byId).forEach { (folder, tasks) ->
                                Text(folder?.title ?: Labels.NO_FOLDER, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(top = 6.dp))
                                tasks.forEach { task ->
                                    // The tick takes the task's own folder shade, as its row's colour bar does.
                                    val tick = task.parentId?.let { walkParentChain(it, byId) { id -> colors[id] } } ?: LedgerMuted
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("✓ ", fontSize = 14.sp, color = tick)
                                        Text(
                                            task.title, fontSize = 14.sp, color = LedgerInk,
                                            modifier = Modifier.weight(1f).clickable {
                                                startActivity(Intent(this@ReviewActivity, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_TASK_ID, task.id))
                                            }.padding(vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

// Oldest to newest, left to right, with each day's count above its bar and weekday initial below.
// Each bar stacks its folders (colour, count) bottom-up, a small gap between segments.
@Composable
private fun CompletionChart(stacks: List<List<Pair<Color, Int>>>, days: List<DayCompletions>) {
    if (days.isEmpty()) return
    val max = days.maxOf { it.tasks.size }.coerceAtLeast(1)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            days.forEach { Text(if (it.tasks.isEmpty()) "" else "${it.tasks.size}", fontSize = 10.sp, color = LedgerMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / days.size
            val barWidth = slot * 0.6f
            drawLine(LedgerBorder, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
            val gap = 2.dp.toPx()
            stacks.forEachIndexed { i, segments ->
                var bottom = size.height
                segments.forEachIndexed { k, (color, count) ->
                    val h = size.height * count / max
                    val top = bottom - h
                    val x = i * slot + (slot - barWidth) / 2
                    // Only the top segment gets the rounded data end.
                    if (k == segments.lastIndex) drawRoundRect(color, Offset(x, top), Size(barWidth, h), CornerRadius(4f, 4f))
                    else drawRect(color, Offset(x, top + gap), Size(barWidth, h - gap))
                    bottom = top
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            days.forEach { Text(SimpleDateFormat("EEEEE", Locale.US).format(Date(it.dayStart)), fontSize = 10.sp, color = LedgerMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
    }
}
