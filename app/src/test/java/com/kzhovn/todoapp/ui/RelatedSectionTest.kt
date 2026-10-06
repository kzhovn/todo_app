package com.kzhovn.todoapp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.notifications.ReminderScheduler
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executor

// The editor's Related section: long-press reorder, the ✕ menu's move out, and (with RENDER_DIR) a picture.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xxhdpi")
class RelatedSectionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java).allowMainThreadQueries()
        .setQueryExecutor(Executor { it.run() }).setTransactionExecutor(Executor { it.run() }).build()
    private val repository = TaskRepository(db.taskDao(), ReminderScheduler(context, context.getSystemService(android.app.AlarmManager::class.java)), db.taskContextDao())

    @After fun tearDown() = db.close()

    private fun open(taskId: Long = 3): TaskEditViewModel = runBlocking {
        db.taskDao().insert(Task(id = 1, title = "Personal", type = TaskType.FOLDER))
        db.taskDao().insert(Task(id = 2, title = "Admin", type = TaskType.FOLDER, parentId = 1))
        db.taskDao().insert(Task(id = 3, title = "Renew passport", parentId = 2))
        db.taskDao().insert(Task(id = 4, title = "Take photo", parentId = 3, isComplete = true))
        db.taskDao().insert(Task(id = 5, title = "Fill in the form", parentId = 3))
        db.taskDao().insert(Task(id = 6, title = "Post it", parentId = 3))
        db.taskDao().insert(Task(id = 7, title = "Pay rent", parentId = 1))
        repository.addDependency(3, 7)
        repository.addDependency(3, 5) // a subtask that's also a prerequisite
        TaskEditViewModel(repository, ContextRepository(db.taskContextDao()), taskId = taskId).also { it.load(null, null); it.refresh() }
    }

    private fun show(vm: TaskEditViewModel) = compose.setContent {
        LedgerTheme { Column(Modifier.padding(16.dp)) { Breadcrumb(vm) {}; RelatedSection(vm, {}, {}, {}, {}) } }
    }

    private fun subtaskTitles() = runBlocking { db.taskDao().getAllOnce().filter { it.parentId == 3L }.sortedWith(TaskOrder).map { it.title } }

    @Test
    fun longPressDragsASubtaskToTheTop() {
        val vm = open()
        show(vm)
        compose.onNodeWithText("Post it").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(Offset(0f, -height * 0.2f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("Post it", "Take photo", "Fill in the form"), subtaskTitles())
    }

    @Test
    fun crossMovesASubtaskOutToTheFolder() {
        val vm = open()
        show(vm)
        compose.onAllNodesWithContentDescription("Remove")[2].performClick()
        compose.onNodeWithText("Move out to Admin").performClick()
        compose.waitForIdle()
        assertEquals(2L, runBlocking { db.taskDao().getById(6)!!.parentId })
    }

    @Test
    fun aSubtaskTagsBeingAPrerequisite() {
        show(open())
        compose.onNodeWithText("Fill in the form").assertExists()
        compose.onAllNodesWithText("Prerequisite").assertCountEquals(2) // after the subtask, and after Pay rent
    }

    @Test
    fun render() = draw(open(), "related")

    @Test
    fun renderSubtask() = draw(open(taskId = 6), "related_subtask")

    private fun draw(vm: TaskEditViewModel, name: String) {
        val dir = System.getenv("RENDER_DIR") ?: return
        compose.setContent {
            LedgerTheme { Column(Modifier.padding(16.dp)) { Breadcrumb(vm) {}; TitleBox(vm, false, {}, {}, {}); RelatedSection(vm, {}, {}, {}, {}) } }
        }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height / 2, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
