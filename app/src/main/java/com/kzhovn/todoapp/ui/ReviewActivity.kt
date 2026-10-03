package com.kzhovn.todoapp.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.AppSettings
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.inMode
import com.kzhovn.todoapp.data.modeFolder
import com.kzhovn.todoapp.data.sectionsByTopFolder
import com.kzhovn.todoapp.data.walkParentChain
import com.kzhovn.todoapp.repository.DONE_BUCKETS
import com.kzhovn.todoapp.repository.ReviewPage
import com.kzhovn.todoapp.repository.WAITING_BUCKETS
import com.kzhovn.todoapp.repository.Zoom
import com.kzhovn.todoapp.repository.bucketCounts
import com.kzhovn.todoapp.repository.dayOfWeekMask
import com.kzhovn.todoapp.repository.medianByTopFolder
import com.kzhovn.todoapp.repository.minuteOfDay
import com.kzhovn.todoapp.repository.review
import com.kzhovn.todoapp.repository.shortAge
import com.kzhovn.todoapp.ui.theme.LedgerAccent
import com.kzhovn.todoapp.ui.theme.LedgerBackground
import com.kzhovn.todoapp.ui.theme.LedgerBorder
import com.kzhovn.todoapp.ui.theme.LedgerGood
import com.kzhovn.todoapp.ui.theme.LedgerInk
import com.kzhovn.todoapp.ui.theme.LedgerMuted
import com.kzhovn.todoapp.ui.theme.LedgerOverdue
import com.kzhovn.todoapp.ui.theme.LedgerSearchBackground
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.ui.theme.LedgerTile
import com.kzhovn.todoapp.ui.theme.folderColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Review: rolling windows (Week · Month · Year · All) stepped with ‹ › or a swipe on the chart, a tap
// on a bar zooming in. The numbers and wording come from core's ReviewPage, shared with the web.
class ReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TodoApp
        setContent {
            LedgerTheme {
                var all by remember { mutableStateOf<List<Task>?>(null) }
                var waiting by remember { mutableStateOf<List<Task>>(emptyList()) }
                var zoom by remember { mutableStateOf(Zoom.MONTH) }
                var end by remember { mutableStateOf<LocalDate?>(null) } // null: today
                // In folder mode, the mode's folder only, unless this is on.
                var everywhere by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val tasks = app.repository.getAllTasks()
                    val now = System.currentTimeMillis()
                    // "Waiting now" is Active with contexts ignored: no task has any here.
                    waiting = app.repository.getActiveTasksFrom(tasks, emptyMap(), now, minuteOfDay(now), dayOfWeekMask(now))
                    all = tasks
                }
                val tasks = all
                AppDrawer(rememberDrawerState(DrawerValue.Closed), reviewSelected = true) {
                    if (tasks != null) {
                        val byId = remember(tasks) { tasks.associateBy { it.id } }
                        val mode = modeFolder(AppSettings.modeFolderId(app), byId)
                        val shown = mode?.takeUnless { everywhere }
                        val page = remember(tasks, waiting, zoom, end, shown) {
                            review(tasks, waiting.filter { inMode(it, shown?.id, byId) }, System.currentTimeMillis(), AppSettings.rolloverHour(app), zoom, end, shown?.id)
                        }
                        ReviewScreen(
                            page, tasks,
                            modeTitle = mode?.title, everywhere = everywhere, onEverywhere = { everywhere = it },
                            go = { z, e -> zoom = z; end = e?.takeIf { it < page.today } },
                            openTask = { id -> startActivity(Intent(this, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_TASK_ID, id)) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewScreen(
    page: ReviewPage, all: List<Task>, go: (Zoom, LocalDate?) -> Unit, openTask: (Long) -> Unit,
    // Folder mode: its folder's name, and whether this shows all folders instead.
    modeTitle: String? = null, everywhere: Boolean = false, onEverywhere: (Boolean) -> Unit = {}
) {
    val byId = remember(all) { all.associateBy { it.id } }
    val colors = remember(all) { folderColors(all) }
    val colorOf = { folder: Task? -> folder?.let { colors[it.id] } ?: LedgerMuted }
    val ages = remember(page) { page.timeToDone.associate { it.task.id to it.ms } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dayList = page.zoom == Zoom.WEEK || page.zoom == Zoom.MONTH
    val days = if (dayList) page.bars.reversed().filter { it.tasks.isNotEmpty() } else emptyList()

    @Composable
    fun TaskLine(task: Task, right: String?, done: Boolean = true) {
        // The tick takes the task's own folder shade, as its row's colour bar does.
        val tick = task.parentId?.let { walkParentChain(it, byId) { id -> colors[id] } } ?: LedgerMuted
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✓ " else "○ ", fontSize = 14.sp, color = if (done) tick else LedgerMuted)
            Text(task.title, fontSize = 14.sp, color = LedgerInk, maxLines = 1, modifier = Modifier.weight(1f).clickable { openTask(task.id) }.padding(vertical = 3.dp))
            right?.let { Text(it, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(start = 8.dp)) }
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(LedgerBackground).padding(horizontal = 16.dp)) {
        item(key = "top") {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 16.dp)) {
                Text("Review", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LedgerInk)
                if (modeTitle != null && !everywhere) Text(modeTitle, fontSize = 13.sp, color = LedgerMuted, modifier = Modifier.padding(start = 8.dp, bottom = 3.dp))
                Spacer(Modifier.weight(1f))
                modeTitle?.let {
                    Text(if (everywhere) "Just $it" else "See all folders", fontSize = 13.sp, color = LedgerAccent, modifier = Modifier.clickable { onEverywhere(!everywhere) }.padding(4.dp))
                }
            }
            // The zoom levels, then the window with its arrows.
            Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).background(LedgerTile).border(1.dp, LedgerBorder, RoundedCornerShape(8.dp)).padding(2.dp)) {
                Zoom.entries.forEach { z ->
                    val on = z == page.zoom
                    Text(
                        z.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 13.sp,
                        color = if (on) LedgerInk else LedgerMuted, fontWeight = if (on) FontWeight.SemiBold else null,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) LedgerSearchBackground else Color.Transparent)
                            .clickable { go(z, page.end) }.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)) {
                page.previous?.let { StepButton("‹") { go(page.zoom, it) } }
                Text(page.windowLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LedgerInk, modifier = Modifier.padding(horizontal = 8.dp))
                page.next?.let { StepButton("›") { go(page.zoom, it) } }
                if (page.end != page.today && page.zoom != Zoom.ALL) {
                    Text("Back to today", fontSize = 13.sp, color = LedgerAccent, modifier = Modifier.clickable { go(page.zoom, null) }.padding(8.dp))
                }
            }

            ReviewChart(
                page, colorOf, byId,
                onTap = { bar ->
                    val zoomed = page.zoomIn(bar)
                    if (zoomed != null) go(zoomed.first, zoomed.second)
                    else days.indexOf(bar).takeIf { it >= 0 }?.let { scope.launch { listState.animateScrollToItem(it + 1) } }
                },
                onSwipe = { later -> (if (later) page.next else page.previous)?.let { go(page.zoom, it) } }
            )
            // The legend: every folder in the window, in tree order.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                sectionsByTopFolder(page.done, byId).forEach { (folder, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colorOf(folder)))
                        Text(folder?.title ?: Labels.NO_FOLDER, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            HorizontalDivider(color = LedgerBorder, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))

            if (page.timeToDone.isNotEmpty()) {
                SectionTitle("Time to done", "from start or creation · ${page.timeToDone.size} tasks, repeats left out")
                HBars(bucketCounts(page.timeToDone, DONE_BUCKETS), LedgerAccent)
                SmallLabel("Median by folder")
                medianByTopFolder(page.timeToDone, byId).forEach { (folder, median) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colorOf(folder)))
                        Text(folder?.title ?: Labels.NO_FOLDER, fontSize = 13.sp, color = LedgerInk, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        Text(shortAge(median), fontSize = 12.sp, color = LedgerMuted)
                    }
                }
                SmallLabel("Took longest")
                page.timeToDone.sortedByDescending { it.ms }.take(3).forEach { TaskLine(it.task, shortAge(it.ms)) }
            }
            if (page.waiting.isNotEmpty()) {
                SectionTitle("Waiting now", "Active tasks, plus those only held back by a context, by how long they've waited")
                HBars(bucketCounts(page.waiting, WAITING_BUCKETS), LedgerOverdue)
                SmallLabel("Waited longest")
                page.waiting.take(3).forEach { TaskLine(it.task, shortAge(it.ms), done = false) }
            }
            if (page.dueOutcomes.isNotEmpty()) {
                val late = page.dueOutcomes.filter { it.ms > 0 }.sortedByDescending { it.ms }
                SectionTitle("Due dates", null)
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val onTime = page.dueOutcomes.size - late.size
                    if (onTime > 0) Box(Modifier.weight(onTime.toFloat()).fillMaxSize().background(LedgerGood))
                    if (late.isNotEmpty()) Box(Modifier.weight(late.size.toFloat()).fillMaxSize().background(LedgerOverdue))
                }
                if (late.isNotEmpty()) {
                    SmallLabel("Latest")
                    late.take(3).forEach { TaskLine(it.task, "${shortAge(it.ms, "under a day")} late") }
                }
            }
            SectionTitle(if (dayList) "Done ${page.windowLabel}" else "By month", null)
            if (page.done.isEmpty()) Text("Nothing completed in ${page.windowLabel}.", fontSize = 13.sp, color = LedgerMuted)
        }
        // Week and Month: every completion, day by day (the chart's table view). Year and All: a row per month.
        items(days, key = { it.first.toEpochDay() }) { day ->
            Column(Modifier.padding(bottom = 14.dp)) {
                Text("${page.barLabel(day)} · ${day.tasks.size}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LedgerInk)
                sectionsByTopFolder(day.tasks, byId).forEach { (folder, tasks) ->
                    Text(folder?.title ?: Labels.NO_FOLDER, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(top = 6.dp))
                    tasks.forEach { TaskLine(it, ages[it.id]?.let(::shortAge)) }
                }
            }
        }
        if (!dayList) items(page.months, key = { it.first.toString() }) { (month, tasks) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { go(Zoom.MONTH, page.monthEnd(month)) }.padding(vertical = 9.dp)
            ) {
                Text(month.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)), fontSize = 14.sp, color = LedgerInk, modifier = Modifier.width(84.dp))
                Row(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(3.dp)), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    sectionsByTopFolder(tasks, byId).forEach { (folder, group) -> Box(Modifier.weight(group.size.toFloat()).fillMaxSize().background(colorOf(folder))) }
                }
                Text("${tasks.size}", fontSize = 13.sp, color = LedgerMuted, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
                Text(" ›", fontSize = 14.sp, color = LedgerMuted)
            }
            HorizontalDivider(color = LedgerBorder)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 16.sp, color = LedgerMuted,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, LedgerBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

@Composable
private fun SectionTitle(title: String, caption: String?) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LedgerInk, modifier = Modifier.padding(top = 16.dp))
    caption?.let { Text(it, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(bottom = 4.dp)) }
}

@Composable
private fun SmallLabel(text: String) = Text(text, fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(top = 8.dp))

// Horizontal bars, one per bucket, scaled to the largest.
@Composable
private fun HBars(counts: List<Pair<String, Int>>, color: Color) {
    val max = counts.maxOf { it.second }.coerceAtLeast(1)
    counts.forEach { (label, n) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Text(label, fontSize = 12.sp, color = LedgerMuted, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(96.dp).padding(end = 8.dp))
            Box(Modifier.weight(1f)) {
                if (n > 0) Box(Modifier.fillMaxWidth(n.toFloat() / max).height(12.dp).clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)).background(color.copy(alpha = .85f)))
            }
            Text("$n", fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.width(36.dp).padding(start = 6.dp))
        }
    }
}

// Oldest to newest, left to right: each bar stacks its folders bottom-up, a small gap between
// segments. Counts sit above the bars only in a Week (a phone's Month is 30 bars of ~9dp). A tap
// zooms in (or goes to that day in the list); a sideways swipe steps through time.
@Composable
private fun ReviewChart(page: ReviewPage, colorOf: (Task?) -> Color, byId: Map<Long, Task>, onTap: (com.kzhovn.todoapp.repository.ReviewBar) -> Unit, onSwipe: (later: Boolean) -> Unit) {
    val bars = page.bars
    if (bars.isEmpty()) return
    val max = bars.maxOf { it.tasks.size }.coerceAtLeast(1)
    val stacks = remember(page) { bars.map { bar -> sectionsByTopFolder(bar.tasks, byId).map { (folder, tasks) -> folder to tasks.size } } }
    Column {
        if (page.zoom == Zoom.WEEK) Row(Modifier.fillMaxWidth()) {
            bars.forEach { Text(if (it.tasks.isEmpty()) "" else "${it.tasks.size}", fontSize = 10.sp, color = LedgerMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        Canvas(
            Modifier.fillMaxWidth().height(120.dp)
                .pointerInput(page) { detectTapGestures { offset -> bars.getOrNull((offset.x / (size.width.toFloat() / bars.size)).toInt())?.let(onTap) } }
                .pointerInput(page) {
                    var dragged = 0f
                    detectHorizontalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (kotlin.math.abs(dragged) > 80) onSwipe(dragged < 0) }) { _, dx -> dragged += dx }
                }
        ) {
            val slot = size.width / bars.size
            val barWidth = slot * if (bars.size > 31) 0.72f else 0.6f
            val gap = (if (bars.size > 31) 1 else 2).dp.toPx()
            drawLine(LedgerBorder, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
            stacks.forEachIndexed { i, segments ->
                var bottom = size.height
                segments.forEachIndexed { k, (folder, count) ->
                    val h = size.height * count / max
                    val top = bottom - h
                    val x = i * slot + (slot - barWidth) / 2
                    // Only the top segment gets the rounded data end.
                    if (k == segments.lastIndex) drawRoundRect(colorOf(folder), Offset(x, top), Size(barWidth, h), CornerRadius(4f, 4f))
                    else drawRect(colorOf(folder), Offset(x, top + gap), Size(barWidth, h - gap))
                    bottom = top
                }
            }
        }
        // Axis labels are sparse (every 7th day, a month's initial), so each is centred on its bar
        // and allowed wider than the bar.
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val slot = maxWidth / bars.size
            val labelWidth = 44.dp
            bars.indices.forEach { i ->
                val label = page.axisLabel(i)
                if (label.isNotEmpty()) {
                    val x = (slot * i + slot / 2 - labelWidth / 2).coerceIn(0.dp, maxWidth - labelWidth)
                    Text(label, fontSize = 10.sp, color = LedgerMuted, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.offset(x = x).width(labelWidth))
                }
            }
        }
    }
}
