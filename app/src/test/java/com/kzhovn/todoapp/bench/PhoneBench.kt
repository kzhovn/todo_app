package com.kzhovn.todoapp.bench

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.TaskContextCrossRef
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TodoDatabase
import com.kzhovn.todoapp.notifications.ReminderScheduler
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.ui.TaskListMode
import com.kzhovn.todoapp.ui.TaskListViewModel
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The phone's side of tools/bench.sh (run with `tools/bench.sh phone`; skipped in normal test runs): the
// list's reload and a tick, over the benchmarks' task list in Room on Robolectric's SQLite. Only relative
// numbers mean anything: a phone is several times slower than this machine.
@RunWith(RobolectricTestRunner::class)
class PhoneBench {
    @Test
    fun bench() {
        assumeTrue(System.getenv("BENCH") != null)
        val sizes = System.getenv("BENCH")!!.split(' ').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(3000) }
        sizes.forEach(::run)
    }

    private fun run(size: Int) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(Executor { it.run() }).setTransactionExecutor(Executor { it.run() }).build()
        val alarms = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        val repository = TaskRepository(db.taskDao(), ReminderScheduler(context, alarms), db.taskContextDao())
        val data = benchData(size)
        data.contexts.forEach { (c, windows) -> db.taskContextDao().insert(c); windows.forEach { db.taskContextDao().insertTimeWindow(it) } }
        data.tasks.forEach { db.taskDao().insert(it) }
        data.contextIds.forEach { (t, ids) -> ids.forEach { db.taskContextDao().assignContext(TaskContextCrossRef(t, it)) } }
        data.dependsOn.forEach { (t, ids) -> ids.forEach { db.taskDao().insertDependency(TaskDependency(t, it)) } }
        val viewModel = TaskListViewModel(repository, ContextRepository(db.taskContextDao()))
        println("\n== phone, $size tasks ==")
        println("%-44s %9s %9s".format("", "median", "p90"))
        time("read every task (getAllOnce)") { db.taskDao().getAllOnce() }
        time("list reload, Doing") { viewModel.load(TaskListMode.DOING); advanceUntilIdle() }
        time("list reload, Active") { viewModel.load(TaskListMode.ACTIVE); advanceUntilIdle() }
        time("tick a task + reload") { repository.toggleComplete(data.openTask, System.currentTimeMillis()); viewModel.reload(); advanceUntilIdle() }
        time("clear checked + undo") { repository.clearChecked(data.checklist); repository.lastDeleted.value?.let { repository.undoDelete(it) } }
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun TestScope.time(name: String, warm: Int = 3, runs: Int = 15, block: suspend TestScope.() -> Unit) {
        repeat(warm) { block() }
        val ms = (1..runs).map { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }.sorted()
        println("%-44s %9.2f %9.2f".format(name, ms[ms.size / 2], ms[(ms.size * 9 / 10).coerceAtMost(ms.size - 1)]))
    }
}
