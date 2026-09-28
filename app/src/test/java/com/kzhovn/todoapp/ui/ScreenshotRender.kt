package com.kzhovn.todoapp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
}
