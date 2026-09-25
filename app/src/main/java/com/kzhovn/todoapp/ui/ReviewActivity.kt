package com.kzhovn.todoapp.ui

import android.os.Bundle
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
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.repository.DayCompletions
import com.kzhovn.todoapp.repository.completionsByDay
import com.kzhovn.todoapp.ui.theme.LedgerAccent
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        setContent {
            LedgerTheme {
                var days by remember { mutableStateOf<List<DayCompletions>>(emptyList()) }
                var folderNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
                LaunchedEffect(Unit) {
                    val all = app.repository.getAllTasks()
                    folderNames = all.filter { it.type == TaskType.FOLDER }.associate { it.id to it.title }
                    days = completionsByDay(all, System.currentTimeMillis(), AppSettings.rolloverHour(app), LIST_DAYS)
                }
                val week = days.take(7).sumOf { it.tasks.size }

                LazyColumn(Modifier.fillMaxSize().background(LedgerBackground).padding(horizontal = 16.dp)) {
                    item {
                        Text("Review", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerInk, modifier = Modifier.padding(top = 16.dp))
                        Text(
                            "$week done in the last 7 days · ${"%.1f".format(week / 7.0)} a day",
                            fontSize = 13.sp, color = LedgerMuted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        )
                        CompletionChart(days.take(CHART_DAYS).reversed())
                        Spacer(Modifier.height(16.dp))
                    }
                    items(days.filter { it.tasks.isNotEmpty() }, key = { it.dayStart }) { day ->
                        Column(Modifier.padding(bottom = 14.dp)) {
                            Text(
                                SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(day.dayStart)) + " · ${day.tasks.size}",
                                fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LedgerInk
                            )
                            day.tasks.forEach { task ->
                                Row {
                                    Text("✓ ", fontSize = 14.sp, color = LedgerAccent)
                                    Text(task.title, fontSize = 14.sp, color = LedgerInk, modifier = Modifier.weight(1f))
                                    task.parentId?.let(folderNames::get)?.let { Text(it, fontSize = 12.sp, color = LedgerMuted) }
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
@Composable
private fun CompletionChart(days: List<DayCompletions>) {
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
            days.forEachIndexed { i, day ->
                val h = size.height * day.tasks.size / max
                drawRoundRect(
                    color = LedgerAccent,
                    topLeft = Offset(i * slot + (slot - barWidth) / 2, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            days.forEach { Text(SimpleDateFormat("EEEEE", Locale.US).format(Date(it.dayStart)), fontSize = 10.sp, color = LedgerMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
    }
}
