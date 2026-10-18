package com.kzhovn.todoapp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.filled.AccountTree
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// Not a test: renders screens to PNGs for a look (set RENDER_DIR to run it).
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xxhdpi")
class ScreenshotRender {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = System.getenv("RENDER_DIR")

    init { System.setProperty("robolectric.pixelCopyRenderMode", "hardware") }

    // Drawn directly: captureToImage waits for a frame Robolectric never delivers.
    private fun draw(view: android.view.View, name: String) {
        compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        save(bitmap, name)
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun folderPicker() {
        if (dir == null) return
        val work = Task(id = 1, title = "Work", type = TaskType.FOLDER, colorIndex = 0)
        val personal = Task(id = 2, title = "Personal", type = TaskType.FOLDER, colorIndex = 1)
        val folders = listOf(
            work, Task(id = 3, title = "Clients", type = TaskType.FOLDER, parentId = 1),
            personal, Task(id = 4, title = "Recurring household", type = TaskType.FOLDER, parentId = 2),
            Task(id = 5, title = "Financial", type = TaskType.FOLDER, parentId = 2), Task(id = 6, title = "Taxes", type = TaskType.FOLDER, parentId = 5),
            Task(id = 7, title = "Hobby", type = TaskType.FOLDER, colorIndex = 2), Task(id = 8, title = "Groceries", type = TaskType.CHECKLIST, parentId = 2)
        )
        compose.setContent { LedgerTheme { FolderPickerDialog(folders, showNoFolderOption = true, onPick = {}, onDismiss = {}, onCreateNew = {}, selectedId = 2) } }
        draw(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView, "folder_picker")
    }

    @Test
    fun typeChip() {
        if (dir == null) return
        compose.setContent {
            LedgerTheme {
                Column(Modifier.width(400.dp).padding(8.dp)) {
                    PropertyChip("Task", null, androidx.compose.material.icons.Icons.Outlined.RadioButtonUnchecked, {}, showLabelWhenSet = false, picker = true)
                    PropertyChip("Project", "Project", androidx.compose.material.icons.Icons.Filled.AccountTree, {}, showLabelWhenSet = false, picker = true)
                }
            }
        }
        draw(compose.activity.window.decorView, "type_chip")
    }

    @Test
    fun searchBar() {
        if (dir == null) return
        compose.setContent { LedgerTheme { Column(Modifier.width(400.dp).padding(8.dp)) { SearchBar("", searching = false, onStart = {}, onChange = {}, Modifier.fillMaxWidth()); SearchBar("4417", searching = true, onStart = {}, onChange = {}, Modifier.fillMaxWidth().padding(top = 8.dp)) } } }
        draw(compose.activity.window.decorView, "search_bar")
    }

    @Test
    fun calendar() {
        if (dir == null) return
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.kzhovn.todoapp.data.TodoDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        val alarms = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        val repository = com.kzhovn.todoapp.repository.TaskRepository(db.taskDao(), com.kzhovn.todoapp.notifications.ReminderScheduler(context, alarms), db.taskContextDao())
        val today = java.time.LocalDate.now()
        fun on(days: Long) = com.kzhovn.todoapp.data.dayStart(today.plusDays(days))
        kotlinx.coroutines.runBlocking {
            listOf(
                Task(title = "Call dentist", dueDate = on(-2)), Task(title = "Submit expense report", dueDate = on(0)),
                Task(title = "Renew passport", dueDate = on(4)), Task(title = "Pack for Boston", startDate = on(0)),
                Task(title = "Water plants", startDate = on(2), recurrenceType = com.kzhovn.todoapp.data.RecurrenceType.RRULE, recurrenceRule = "FREQ=WEEKLY"),
                Task(title = "Try a pottery class", dueDate = on(8), isMaybe = true), Task(title = "Car registration", dueDate = on(7)),
            ).forEach { repository.createTask(it) }
        }
        val viewModel = TaskListViewModel(repository, com.kzhovn.todoapp.repository.ContextRepository(db.taskContextDao()))
        viewModel.load(TaskListMode.ALL)
        val all = com.kzhovn.todoapp.data.DateKind.entries.toSet()
        compose.setContent { LedgerTheme { CalendarScreen(viewModel, 9, all - com.kzhovn.todoapp.data.DateKind.DONE, {}, {}, {}, {}, {}) } }
        compose.waitForIdle()
        draw(compose.activity.window.decorView, "calendar")
    }

    @Test
    fun drawer() {
        if (dir == null) return
        compose.setContent { LedgerTheme { AppDrawer(androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Open), reviewSelected = true) {} } }
        draw(compose.activity.window.decorView, "drawer")
    }

    // The side menu's Contexts, open, with Errands' long-press menu; then a list filtered by Errands.
    @Test
    fun contextFilter() {
        if (dir == null) return
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<com.kzhovn.todoapp.TodoApp>()
        val viewModel = TaskListViewModel(app.repository, app.contextRepository)
        kotlinx.coroutines.runBlocking {
            val errands = app.contextRepository.createContext(com.kzhovn.todoapp.data.TaskContext(name = "Errands", type = com.kzhovn.todoapp.data.ContextType.LABEL, forced = false))
            app.contextRepository.createContext(com.kzhovn.todoapp.data.TaskContext(name = "Phone", type = com.kzhovn.todoapp.data.ContextType.LABEL))
            app.contextRepository.createContext(com.kzhovn.todoapp.data.TaskContext(name = "Home", type = com.kzhovn.todoapp.data.ContextType.PLACE, wifiSsid = "Raspberry", isCurrentlySatisfied = true))
            listOf("Renew passport", "Buy stamps").forEach { app.contextRepository.setTaskContexts(app.repository.createTask(Task(title = it)), setOf(errands)) }
            app.repository.createTask(Task(title = "Pay rent", isStarred = true))
            ContextFilter.id.value = errands
            viewModel.load(TaskListMode.ACTIVE, filters = com.kzhovn.todoapp.data.SearchFilters(contextId = errands))
        }
        compose.setContent { LedgerTheme { AppDrawer(androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Open)) {} } }
        compose.waitForIdle()
        draw(compose.activity.window.decorView, "drawer_contexts")
        compose.onNodeWithText("Phone").performTouchInput { longClick() }
        compose.waitForIdle()
        val global = Class.forName("android.view.WindowManagerGlobal").let { it.getMethod("getInstance").invoke(null) }
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
        draw(views.last() as android.view.View, "drawer_context_menu")
    }

    @Test
    fun filteredList() {
        if (dir == null) return
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<com.kzhovn.todoapp.TodoApp>()
        val viewModel = TaskListViewModel(app.repository, app.contextRepository)
        val errands = kotlinx.coroutines.runBlocking {
            val errands = app.contextRepository.createContext(com.kzhovn.todoapp.data.TaskContext(name = "Errands", type = com.kzhovn.todoapp.data.ContextType.LABEL, forced = false))
            listOf("Renew passport", "Buy stamps").forEach { app.contextRepository.setTaskContexts(app.repository.createTask(Task(title = it)), setOf(errands)) }
            app.repository.createTask(Task(title = "Pay rent", isStarred = true))
            errands
        }
        viewModel.load(TaskListMode.ACTIVE, "stamps", com.kzhovn.todoapp.data.SearchFilters(contextId = errands))
        compose.setContent {
            LedgerTheme {
                Column {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        SearchBar("stamps", searching = true, onStart = {}, onChange = {}, Modifier.weight(1f).padding(8.dp))
                        ContextButton(viewModel.contextSummaries.collectAsState().value, errands, onPick = {})
                    }
                    ContextFilterBar(errands, viewModel.contextSummaries.collectAsState().value, picked = true) {}
                    TaskListScreen(viewModel, {}, {}, {}, { _, _ -> }, {}, sectioned = true)
                }
            }
        }
        // The view model's loads hop back to the main looper, which nothing else idles here.
        compose.waitUntil(5000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); viewModel.tasks.value.isNotEmpty() && viewModel.contextSummaries.value.isNotEmpty() }
        draw(compose.activity.window.decorView, "filtered_list")
    }

    @Test
    fun checkInSheet() {
        if (dir == null) return
        val now = System.currentTimeMillis()
        compose.setContent { LedgerTheme { CheckInSheet(10, now + 10 * 86_400_000L, now, onEvery = {}, onNext = {}, onDismiss = {}) } }
        compose.waitForIdle()
        // The sheet is a window of its own: the newest root view.
        val global = Class.forName("android.view.WindowManagerGlobal").let { it.getMethod("getInstance").invoke(null) }
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
        draw(views.last() as android.view.View, "check_in_sheet")
    }

    @Test
    fun focusPicker() {
        if (dir == null) return
        val personal = Task(id = 10, title = "Personal", type = TaskType.FOLDER, colorIndex = 0)
        val money = Task(id = 11, title = "Money", type = TaskType.FOLDER, colorIndex = 2)
        val trip = Task(id = 12, title = "Trip", type = TaskType.PROJECT, parentId = 10)
        val doing = listOf(
            Task(id = 1, title = "charge flight, beads, vet(?) to card", parentId = 11, dueDate = System.currentTimeMillis() - 86_400_000),
            Task(id = 2, title = "Buy gas canisters (check theprepared.com for the right ones)", parentId = 12, isStarred = true),
            Task(id = 3, title = "Hem hiking pants", parentId = 10, dueDate = System.currentTimeMillis()),
            Task(id = 4, title = "Invest HSA", parentId = 11)
        )
        val byId = (doing + listOf(personal, money, trip)).associateBy { it.id }
        val lists = listOf("Doing" to doing.take(3), "Active" to doing, "All" to doing)
        compose.setContent { LedgerTheme { TaskPickerDialog("Focus on", lists, byId, onPick = {}, onCreateNew = {}, onDismiss = {}, chips = true, searchAll = { emptyList() }) } }
        draw(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView, "focus_picker")
    }

    // Review over 14 months of made-up completions, in each zoom.
    @Test
    @Config(qualifiers = "w400dp-h1800dp-xxhdpi")
    fun review() {
        if (dir == null) return
        val random = java.util.Random(3)
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val folders = listOf("Personal", "Work", "Home", "Errands").mapIndexed { i, name -> Task(id = 1000L + i, title = name, type = TaskType.FOLDER, colorIndex = i) }
        val titles = listOf("Call the dentist", "Send the invoice", "Water the plants", "Pay rent", "Fix the sink", "Renew passport")
        var seq = 0L
        val done = (420 downTo 0).flatMap { back ->
            (0 until (random.nextGaussian() * 2 + 4.5).toInt().coerceAtLeast(0)).map {
                val finished = now - back * day - random.nextInt(10 * 3600000)
                val age = (listOf(0.2, 0.5, 1.5, 2.0, 4.0, 6.0, 10.0, 20.0, 40.0, 120.0)[random.nextInt(10)] * day).toLong()
                Task(id = ((finished - age) shl 11) + (seq++ % 2048), title = titles[random.nextInt(titles.size)], parentId = folders[random.nextInt(4)].id,
                    isComplete = true, completedAt = finished, dueDate = if (random.nextInt(3) == 0) finished + (random.nextInt(5) - 3) * day else null)
            }
        }
        val open = (0 until 40).map { Task(id = ((now - random.nextInt(200) * day) shl 11) + it, title = "Open task $it", parentId = folders[it % 4].id) }
        val all = folders + done + open
        // setContent only works once per test, so the zoom is state.
        var zoom by androidx.compose.runtime.mutableStateOf(com.kzhovn.todoapp.repository.Zoom.WEEK)
        compose.setContent { LedgerTheme { ReviewScreen(com.kzhovn.todoapp.repository.review(all, open, now, 4, zoom), all, go = { _, _ -> }, openTask = {}) } }
        for (z in com.kzhovn.todoapp.repository.Zoom.entries) {
            zoom = z
            draw(compose.activity.window.decorView, "review_${z.name.lowercase()}")
        }
    }

    @Test
    fun runningLine() {
        if (dir == null) return
        val now = System.currentTimeMillis()
        val washing = Task(id = 1, title = "Washing", type = TaskType.WAITING, durationMinutes = 50, dueDate = now + 32 * 60_000L + 1000)
        val pasta = Task(id = 2, title = "Pasta", type = TaskType.WAITING, durationMinutes = 10, dueDate = now + 6 * 60_000L + 1000)
        val running = listOf(com.kzhovn.todoapp.repository.RunningWait(pasta, null), com.kzhovn.todoapp.repository.RunningWait(washing, Task(id = 3, title = "Switch to dryer")))
        compose.setContent { LedgerTheme { Column(Modifier.width(400.dp)) { com.kzhovn.todoapp.RunningSection(running, {}, {}, {}, {}) } } }
        draw(compose.activity.window.decorView, "running_line")
    }
}
