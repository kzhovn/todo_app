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
import androidx.compose.ui.unit.dp
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
    fun searchBar() {
        if (dir == null) return
        compose.setContent { LedgerTheme { Column(Modifier.width(400.dp).padding(8.dp)) { SearchBar("", searching = false, onStart = {}, onChange = {}, Modifier.fillMaxWidth()); SearchBar("4417", searching = true, onStart = {}, onChange = {}, Modifier.fillMaxWidth().padding(top = 8.dp)) } } }
        draw(compose.activity.window.decorView, "search_bar")
    }

    @Test
    fun drawer() {
        if (dir == null) return
        compose.setContent { LedgerTheme { AppDrawer(androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Open), reviewSelected = true) {} } }
        draw(compose.activity.window.decorView, "drawer")
    }

    @Test
    fun focusPicker() {
        if (dir == null) return
        val doing = listOf(Task(id = 1, title = "Send the invoice"), Task(id = 2, title = "Call the dentist"))
        compose.setContent { LedgerTheme { TaskPickerDialog("Focus on", doing, onPick = {}, onCreateNew = {}, onDismiss = {}, searchAll = { emptyList() }) } }
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
}
