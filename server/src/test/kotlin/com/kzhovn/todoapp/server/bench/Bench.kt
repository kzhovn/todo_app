package com.kzhovn.todoapp.server.bench

import com.kzhovn.todoapp.bench.BenchData
import com.kzhovn.todoapp.bench.benchData
import com.kzhovn.todoapp.data.DueDates
import com.kzhovn.todoapp.repository.BulkEdit
import com.kzhovn.todoapp.repository.computeActiveTasks
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.repository.planClearChecked
import com.kzhovn.todoapp.repository.planPurgeExpired
import com.kzhovn.todoapp.server.Store
import com.kzhovn.todoapp.server.TaskService
import com.kzhovn.todoapp.server.api
import com.kzhovn.todoapp.sync.CONTEXTS
import com.kzhovn.todoapp.sync.SyncRequest
import com.kzhovn.todoapp.sync.SyncRow
import com.kzhovn.todoapp.sync.TASKS
import com.kzhovn.todoapp.sync.contextFields
import com.kzhovn.todoapp.sync.taskFields
import com.kzhovn.todoapp.widget.TodoWidgetPresenter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

// Times the web pages, the top bar's API, sync and the :core list logic over a generated task list of a
// given size (most of it completed, as tasks are kept forever). Not a test: run with tools/bench.sh [sizes...].
// Medians (and p90) of warm runs, in ms; compare runs on the same machine only.
fun main(args: Array<String>) {
    val sizes = args.mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(3000) }
    for (size in sizes) runAll(size)
}

private fun runAll(size: Int) {
    val db = File.createTempFile("bench", ".db").apply { deleteOnExit() }
    val store = Store(db.path)
    val service = TaskService(store)
    val data = benchData(size)
    load(store, data)
    val all = service.tasks()
    println("\n== $size tasks (${all.count { !it.isComplete }} open, ${data.dependsOn.values.sumOf { it.size }} dependencies) ==")
    println("%-44s %9s %9s".format("", "median", "p90"))

    testApplication {
        application { api(store, "token", service, webEnabled = true) }
        suspend fun get(path: String) = client.get(path) { header("Authorization", "Bearer token") }.bodyAsText()
        suspend fun post(path: String) = client.post(path) { header("Authorization", "Bearer token") }.bodyAsText()
        listOf("/doing", "/active", "/all", "/api/tray?list=all").forEach { path ->
            val body = get(path).toByteArray()
            val gzipped = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(body) } }.size()
            println("  size of $path: ${body.size / 1024} KB, gzipped ${gzipped / 1024} KB")
        }
        time("GET /doing (full page)") { get("/doing") }
        time("GET /active (full page)") { get("/active") }
        time("GET /all (full page)") { get("/all") }
        time("GET /list/doing (60s htmx refresh)") { get("/list/doing") }
        time("GET /tasks/{id} (editor)") { get("/tasks/${data.openTask}") }
        time("GET /search/results?q=a") { get("/search/results?q=a") }
        time("GET /review") { get("/review") }
        time("GET /api/tray?list=none (60s poll)") { get("/api/tray?list=none") }
        time("GET /api/tray?list=all") { get("/api/tray?list=all") }
        var done = false
        time("POST complete/uncomplete + list") {
            post("/tasks/${data.openTask}/${if (done) "uncomplete" else "complete"}?mode=DOING"); done = !done
        }
    }
    var flip = false
    time("bulk edit 20 tasks", runs = 10) {
        flip = !flip
        service.applyBulkEdit(data.bulkIds, BulkEdit(starred = flip))
    }
    var stamp = System.currentTimeMillis()
    time("sync: phone pushes 50 edited rows", runs = 10) {
        stamp += 1000
        val rows = data.bulkIds.take(50).map { SyncRow(TASKS, it, JsonObject(mapOf("title" to JsonPrimitive("edit $stamp"))), mapOf("title" to stamp)) }
        store.sync(SyncRequest(cursor = Long.MAX_VALUE, changes = rows))
    }

    val now = System.currentTimeMillis()
    val tasks = service.tasks()
    val edges = service.dependencyEdges()
    val contexts = service.contextIdsByTask()
    val ctxList = service.contexts()
    val windows = ctxList.flatMap { service.timeWindows(it.id) }
    time("core: computeActiveTasks") { computeActiveTasks(tasks, contexts, ctxList, windows, edges, now) }
    val active = computeActiveTasks(tasks, contexts, ctxList, windows, edges, now)
    time("core: filterDoing (over Active)") { filterDoing(active, now, DueDates(tasks, edges)) }
    time("core: planClearChecked (30 checked)") { planClearChecked(tasks, data.checklist) }
    time("core: planPurgeExpired") { planPurgeExpired(tasks, now) }
    time("core: widget rows, All") { TodoWidgetPresenter.rows(TodoWidgetPresenter.allOpen(tasks, tasks.associateBy { it.id }), tasks, edges, now, false) }
}

// Straight to the store in one transaction (service.create would re-plan against everything each time).
private fun load(store: Store, data: BenchData) = store.transaction {
    val now = System.currentTimeMillis()
    data.contexts.forEach { (c, windows) -> store.write(CONTEXTS, c.id, contextFields(c, windows), now) }
    data.tasks.forEach { t -> store.write(TASKS, t.id, taskFields(t, data.contextIds[t.id].orEmpty(), data.dependsOn[t.id].orEmpty()), now) }
}

private fun time(name: String, warm: Int = 5, runs: Int = 20, block: suspend () -> Unit) {
    runBlocking { repeat(warm) { block() } }
    val ms = (1..runs).map { runBlocking { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 } }.sorted()
    println("%-44s %9.2f %9.2f".format(name, ms[ms.size / 2], ms[(ms.size * 9 / 10).coerceAtMost(ms.size - 1)]))
}

