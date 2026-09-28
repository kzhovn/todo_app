package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.RecurrenceType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.quickadd.startOfDay
import com.kzhovn.todoapp.sync.SyncJson
import com.kzhovn.todoapp.sync.SyncRequest
import com.kzhovn.todoapp.sync.SyncResponse
import com.kzhovn.todoapp.sync.SyncRow
import com.kzhovn.todoapp.sync.TASKS
import com.kzhovn.todoapp.sync.diff
import com.kzhovn.todoapp.sync.dependsOn
import com.kzhovn.todoapp.sync.taskFields
import com.kzhovn.todoapp.sync.toTask
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerTest {
    private val store = Store(File.createTempFile("todo", ".db").apply { deleteOnExit() }.path)
    private var now = 1_000_000L
    private val service = TaskService(store) { now }
    private val logic = BotLogic(service, store)

    @Test
    fun `sync endpoint rejects bad tokens and round-trips rows`() = testApplication {
        application { api(store, "secret") }
        val row = SyncRow(TASKS, 5, taskFields(Task(id = 5, title = "From phone"), emptySet(), emptySet()), mapOf("title" to 1L))
        val body = SyncJson.encodeToString(SyncRequest.serializer(), SyncRequest(0, listOf(row)))

        val denied = client.post("/sync") { header("Authorization", "Bearer nope"); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)

        val ok = client.post("/sync") { header("Authorization", "Bearer secret"); contentType(ContentType.Application.Json); setBody(body) }
        val response = SyncJson.decodeFromString(SyncResponse.serializer(), ok.bodyAsText())
        assertEquals("From phone", response.rows.single().toTask().title)

        val again = store.sync(SyncRequest(response.cursor, emptyList()))
        assertTrue("nothing new since cursor", again.rows.isEmpty())
    }

    @Test
    fun `the tray API needs the token and serves the widget's rows, one list at a time`() = testApplication {
        application { api(store, "secret") }
        fun tray(body: String) = SyncJson.decodeFromString(TrayState.serializer(), body)
        val auth = "Bearer secret"
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/tray").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/unpin") { header("Authorization", "Bearer nope") }.status)

        val call = service.create(Task(title = "Call mom", isStarred = true))
        val step = service.create(Task(title = "Find number", parentId = call.id, isStarred = true))
        val doing = tray(client.get("/api/tray?list=doing") { header("Authorization", auth) }.bodyAsText())
        assertEquals(listOf("Find number"), doing.rows.map { it.title }) // the parent waits on its subtask
        assertEquals("Call mom", doing.rows.single().parentTitle)
        assertEquals(TrayCounts(doing = 1, active = 1, all = 2), doing.counts)
        val all = tray(client.get("/api/tray?list=all") { header("Authorization", auth) }.bodyAsText())
        assertEquals(listOf("Call mom", "Find number"), all.rows.map { it.title }) // All has the waiting parent too
        // The popup closed: only the pin and the counts.
        assertTrue(tray(client.get("/api/tray?list=none") { header("Authorization", auth) }.bodyAsText()).rows.isEmpty())

        val pinned = tray(client.post("/api/tasks/${call.id}/pin?list=doing") { header("Authorization", auth) }.bodyAsText())
        assertEquals(PinnedRow(call.id, "Call mom"), pinned.pinned)
        val focused = tray(client.post("/api/tasks/${call.id}/focus?list=doing") { header("Authorization", auth) }.bodyAsText())
        assertEquals(FocusRow(call.id, "Call mom", done = false), focused.focus)
        // The checkbox completes the subtasks too, like the widget's.
        val done = tray(client.post("/api/tasks/${call.id}/complete?list=doing") { header("Authorization", auth) }.bodyAsText())
        assertTrue(service.get(step.id)!!.isComplete)
        assertNull(done.pinned)
        assertTrue(done.rows.isEmpty())

        val added = tray(client.post("/api/quickadd") {
            header("Authorization", auth); contentType(ContentType.Application.FormUrlEncoded); setBody("text=Water+plants&mode=doing")
        }.bodyAsText())
        assertEquals(listOf("Water plants"), added.rows.map { it.title }) // added from Doing: starred

        // A long list is capped; the count still says how many there are.
        repeat(120) { service.create(Task(title = "Task $it")) }
        val long = tray(client.get("/api/tray?list=all") { header("Authorization", auth) }.bodyAsText())
        assertEquals(100, long.rows.size)
        assertEquals(121, long.counts.all)
    }

    @Test
    fun `the tray's timer is the current task's, and time's up adds time`() = testApplication {
        application { api(store, "secret") }
        fun tray(body: String) = SyncJson.decodeFromString(TrayState.serializer(), body)
        val work = service.create(Task(title = "Ticket work", durationMinutes = 60))
        val started = tray(client.post("/api/tasks/${work.id}/timer?list=none") { header("Authorization", "Bearer secret") }.bodyAsText())
        // (The API runs on the real clock, so times are checked against the server's `now`.)
        assertTrue(kotlin.math.abs(started.pinned!!.timerEndsAt!! - started.now - 60 * 60_000L) < 5_000) // starting a timer pins its task
        val paused = tray(client.post("/api/timer/pause?list=none") { header("Authorization", "Bearer secret") }.bodyAsText())
        assertTrue(kotlin.math.abs(paused.pinned!!.timerRemaining!! - 60 * 60_000L) < 5_000)
        val more = tray(client.post("/api/timer/add?minutes=10&list=none") { header("Authorization", "Bearer secret") }.bodyAsText())
        assertTrue(kotlin.math.abs(more.pinned!!.timerEndsAt!! - more.now - 10 * 60_000L) < 5_000)
    }

    @Test
    fun `phone edit and bot edit to different fields of one task both survive`() {
        val task = service.create(Task(title = "Taxes"))
        val base = store.get(TASKS, task.id)!!
        now += 1000
        service.setStarred(task.id, true)

        val phoneEdit = diff(TASKS, task.id, base, taskFields(task.copy(title = "File taxes"), emptySet(), emptySet()), now - 500)!!
        val result = store.sync(SyncRequest(0, listOf(phoneEdit))).rows.single { it.id == task.id }.toTask()

        assertEquals("File taxes", result.title)
        assertTrue(result.isStarred)
    }

    @Test
    fun `completing a recurring task spawns the next instance and un-completing removes it`() {
        val task = service.create(Task(title = "Trash", recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "7"))

        service.complete(task.id)
        assertEquals(2, service.tasks().size)

        service.uncomplete(task.id)
        assertEquals(listOf(task.id), service.tasks().map { it.id })
        assertFalse(service.get(task.id)!!.isComplete)
    }

    @Test
    fun `completing a recurring task re-creates its subtasks under the next instance`() {
        val review = service.create(Task(title = "Review", recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "30"))
        service.create(Task(title = "Move money", parentId = review.id))

        service.complete(review.id)
        val next = service.tasks().single { it.title == "Review" && !it.isComplete }
        assertEquals(listOf("Move money"), service.tasks().filter { it.parentId == next.id }.map { it.title })

        service.uncomplete(review.id)
        assertEquals(setOf("Review", "Move money"), service.tasks().map { it.title }.toSet())
        assertEquals(2, service.tasks().size)
    }

    @Test
    fun `delete takes the subtree down together and restore brings back only that batch`() {
        val parent = service.create(Task(title = "Project"))
        val child = service.create(Task(title = "Step", parentId = parent.id))
        val earlier = service.create(Task(title = "Deleted earlier", parentId = parent.id))
        service.delete(earlier.id)
        now += 1000

        service.delete(parent.id)
        assertTrue(service.tasks().isEmpty())

        service.restore(parent.id)
        assertEquals(setOf(parent.id, child.id), service.tasks().map { it.id }.toSet())
    }

    @Test
    fun `adds parse flags and folder prefixes, unknown prefixes stay in the title`() {
        val work = service.create(Task(type = TaskType.FOLDER, title = "Work"))

        assertEquals(work.id, logic.parseAdd("--work: send report -d tomorrow")!!.parentId)
        assertNotNull(logic.parseAdd("--work: send report -d tomorrow")!!.dueDate)
        assertEquals("Note: call Bob", logic.parseAdd("-- Note: call Bob")!!.title)
        assertNull(logic.parseAdd("hello"))
        assertNull(logic.parseAdd("--   "))
    }

    @Test
    fun `adds without a folder prefix land in Personal, and stay starred`() {
        val personal = service.create(Task(type = TaskType.FOLDER, title = "Personal"))
        val work = service.create(Task(type = TaskType.FOLDER, title = "Work"))

        val bare = logic.parseAdd("-- call mom")!!
        assertEquals(personal.id, bare.parentId)
        assertTrue(bare.isStarred)
        assertEquals(work.id, logic.parseAdd("--work: report")!!.parentId)

        val due = logic.parseAdd("-- work: update bug due today")!!
        assertEquals("update bug", due.title)
        assertEquals(work.id, due.parentId)
        assertEquals(java.util.Calendar.getInstance().apply { timeInMillis = now }.startOfDay(), due.dueDate)
    }

    @Test
    fun `adds share the quick-add syntax - checklist items, contexts, pin and focus`() {
        val groceries = service.create(Task(type = TaskType.CHECKLIST, title = "Groceries"))
        assertTrue(logic.onAdd(1L, "u", "--groceries: milk, eggs"))
        assertEquals(listOf("milk", "eggs"), service.tasks().filter { it.parentId == groceries.id }.map { it.title })
        assertEquals(listOf("milk", "eggs"), logic.command(".list groceries")!!.getOrThrow().map { it.title })

        logic.onAdd(2L, "u", "-- packing [passport, charger] -p")
        val packing = service.tasks().single { it.title == "packing" }
        assertEquals(TaskType.CHECKLIST, packing.type)
        assertEquals(2, service.tasks().count { it.parentId == packing.id })
        assertEquals(packing.id, service.pinned()?.id)

        val home = service.saveContext(com.kzhovn.todoapp.data.TaskContext(name = "Home", type = com.kzhovn.todoapp.data.ContextType.PLACE), emptyList())
        val web = service.quickAdd("fix sink @home -f", fromDoing = false)!!
        assertEquals("fix sink", web.title)
        assertEquals(setOf(home.id), service.contextIdsByTask()[web.id])
        assertEquals(web.id, service.focusSession()?.id)
    }

    @Test
    fun `app- and web-side completion and deletion of a Discord task mirror onto its message`() {
        val mirrored = mutableListOf<SourceReaction>()
        store.onChange = { before, after -> mirrored += logic.sourceReactions(before, after) }
        logic.onAdd(55L, "https://discord.com/channels/1/22/55", "-- call mom")
        val id = service.tasks().single().id

        fun push(change: (Task) -> Task, now: Long) {
            val base = store.get(TASKS, id)!!
            store.sync(SyncRequest(0, listOf(diff(TASKS, id, base, taskFields(change(base.toTask()), emptySet(), emptySet()), now)!!)))
        }
        push({ it.copy(isComplete = true) }, now + 10)
        push({ it.copy(isComplete = false) }, now + 20)
        logic.onReaction(55L, DONE, added = true) // completed from Discord itself: no mirror
        logic.onReaction(55L, DONE, added = false)
        service.delete(id) // from the web
        assertEquals(
            listOf(SourceReaction(22, 55, DONE, true), SourceReaction(22, 55, DONE, false), SourceReaction(22, 55, DELETE, true)),
            mirrored
        )
    }

    @Test
    fun `a trailing question mark makes an unstarred maybe, hidden from Doing but listed in its folder`() {
        val work = service.create(Task(type = TaskType.FOLDER, title = "Work"))

        val maybe = logic.parseAdd("-- work: update bug? due today")!!
        assertTrue(maybe.isMaybe)
        assertFalse(maybe.isStarred)
        assertEquals("update bug", maybe.title)
        assertFalse(logic.parseAdd("--work: update(?) bug due today")!!.isMaybe)
        assertFalse(logic.parseAdd("-- someday?")!!.isStarred)

        logic.onAdd(1L, "u", "-- work: update bug? due today")
        val id = service.tasks().single { it.title == "update bug" }.id
        service.setStarred(id, true)
        assertFalse(service.get(id)!!.isStarred)
        assertTrue(service.doing().none { it.id == id })
        assertTrue(logic.listChunks(service.openInFolder(work.id)).single().content.contains("update bug ?"))
    }

    @Test
    fun `--d adds expire at the rollover the phone reported, then disappear and get purged`() {
        store.sync(SyncRequest(0, emptyList(), rolloverHour = 6))
        val personal = service.create(Task(type = TaskType.FOLDER, title = "Personal"))

        logic.onAdd(1L, "u", "--d: shower")
        val task = service.tasks().single { it.title == "shower" }
        assertEquals(com.kzhovn.todoapp.data.nextRollover(now, 6), task.expiresAt)
        assertEquals(personal.id, task.parentId)
        assertTrue(task.isStarred)
        assertNull(logic.parseAdd("-- d is for dog")!!.expiresAt)

        now = task.expiresAt!!
        assertNull(service.get(task.id))
        assertTrue(logic.command(".doing")!!.getOrThrow().isEmpty())
        assertTrue(store.get(TASKS, task.id)!!.isDeleted)
    }

    @Test
    fun `bare adds are starred, adds with a date or folder are not`() {
        service.create(Task(type = TaskType.FOLDER, title = "Work"))

        assertTrue(logic.parseAdd("-- call mom")!!.isStarred)
        assertFalse(logic.parseAdd("-- call mom -d tomorrow")!!.isStarred)
        assertFalse(logic.parseAdd("-- call mom -s tomorrow")!!.isStarred)
        assertFalse(logic.parseAdd("--work: send report")!!.isStarred)
    }

    @Test
    fun `list lines never show a star`() {
        service.create(Task(title = "Starred", isStarred = true))
        assertFalse(logic.listChunks(service.doing()).single().content.contains(STAR))
    }

    @Test
    fun `source-message reactions complete, delete, star, and undo`() {
        assertTrue(logic.onAdd(1L, "https://discord.com/channels/@me/2/1", "-- call mom -d tomorrow"))
        val id = service.tasks().single().id

        logic.onReaction(1L, STAR, added = true)
        assertTrue(service.get(id)!!.isStarred)
        logic.onReaction(1L, DONE, added = true)
        assertTrue(service.get(id)!!.isComplete)
        logic.onReaction(1L, DONE, added = false)
        assertFalse(service.get(id)!!.isComplete)
        logic.onReaction(1L, DELETE, added = true)
        assertNull(service.get(id))
        logic.onReaction(1L, DELETE, added = false)
        assertNotNull(service.get(id))
        logic.onReaction(1L, STAR, added = false)
        assertFalse(service.get(id)!!.isStarred)
    }

    @Test
    fun `a task can wait on a checklist, and an edit keeps that`() {
        val packing = service.create(Task(title = "Packing", type = TaskType.CHECKLIST))
        val leave = service.create(Task(title = "Leave"))
        service.edit(leave, emptySet(), setOf(packing.id))
        assertEquals(setOf(packing.id), service.dependsOn(leave.id))
    }

    @Test
    fun `completing from Discord completes everything under the task`() {
        logic.onAdd(1L, "u", "-- pack")
        val id = service.tasks().single().id
        val sub = service.create(Task(title = "socks", parentId = id))
        logic.onReaction(1L, DONE, added = true)
        assertTrue(service.get(id)!!.isComplete)
        assertTrue(service.get(sub.id)!!.isComplete)
    }

    @Test
    fun `editing the message only rewrites fields whose parse changed`() {
        logic.onAdd(1L, "u", "-- call mom -d tomorrow")
        val id = service.tasks().single().id
        service.update(id) { it.copy(dueDate = 42L) } // changed in the app since

        logic.onEdit(1L, "-- call mum -d tomorrow")

        val task = service.get(id)!!
        assertEquals("call mum", task.title)
        assertEquals(42L, task.dueDate)
    }

    @Test
    fun `replying to a todo with a todo makes the first wait for the second`() {
        logic.onAdd(1L, "u1", "-- hang mirror")
        logic.onAdd(2L, "u2", "-- move mirror upstairs", replyToMessageId = 1L)
        val hang = service.tasks().single { it.title == "hang mirror" }
        val move = service.tasks().single { it.title == "move mirror upstairs" }

        assertEquals(setOf(move.id), store.get(TASKS, hang.id)!!.dependsOn())
        assertTrue(service.active().none { it.id == hang.id })

        logic.onAdd(3L, "u3", "-- loop back", replyToMessageId = 2L) // move waits for loop: fine
        service.addDependency(service.tasks().single { it.title == "loop back" }.id, hang.id) // would close a loop: refused
        assertTrue(store.get(TASKS, service.tasks().single { it.title == "loop back" }.id)!!.dependsOn().isEmpty())
    }

    @Test
    fun `tasks stuck in Doing get nudged after 3 days, then every 3 days, and nudges act on them`() {
        val day = 24L * 60 * 60 * 1000
        val stuck = service.create(Task(title = "Taxes", isStarred = true))
        assertTrue(logic.dueNudges().isEmpty()) // first digest to see it: clock starts

        now += 3 * day
        assertEquals(listOf("Taxes" to 3), logic.dueNudges().map { (t, d) -> t.title to d })
        now += day
        assertTrue(logic.dueNudges().isEmpty()) // nudged a day ago
        now += 2 * day
        assertEquals(1, logic.dueNudges().size)

        logic.recordNudge(900L, stuck.id)
        logic.onAdd(901L, "u", "-- gather receipts\n-- fill forms", replyToMessageId = 900L)
        val steps = service.tasks().filter { it.parentId == stuck.id }
        assertEquals(listOf("gather receipts", "fill forms"), steps.map { it.title })
        // Taxes now waits on its steps, so the first takes its place in Doing.
        assertEquals(listOf(true, false), steps.map { it.isStarred })

        logic.onReaction(900L, MOVE_OUT, added = true)
        assertFalse(service.get(stuck.id)!!.isStarred)
        now += 3 * day
        assertTrue(logic.dueNudges().none { it.first.id == stuck.id }) // left Doing: clock reset
        logic.onReaction(900L, MOVE_OUT, added = false)
        assertTrue(service.get(stuck.id)!!.isStarred)
    }

    @Test
    fun `deleting the source message soft-deletes the task`() {
        logic.onAdd(1L, "u", "-- call mom")
        logic.onDelete(1L)
        assertTrue(service.tasks().isEmpty())
    }

    @Test
    fun `source-linked lines end in their link, app lines in their reaction emoji`() {
        logic.onAdd(1L, "https://discord.com/channels/@me/2/1", "-- call mom")
        val app = service.create(Task(title = "Buy milk", isStarred = true, dueDate = com.kzhovn.todoapp.data.atTime(now, 0, 0)))

        val chunk = logic.listChunks(service.doing()).single()
        val emoji = chunk.lines.single { it.taskId == app.id }.emoji!!
        assertEquals(listOf(emoji), chunk.emojis)
        val lines = chunk.content.lines()
        assertTrue("- call mom [↗](<https://discord.com/channels/@me/2/1>)" in lines)
        assertTrue(lines.any { Regex("- Buy milk · due \\w{3} \\d+ \\w{3} $emoji").matches(it) })
        val timed = service.create(Task(title = "Dentist", dueDate = com.kzhovn.todoapp.data.atTime(now, 17, 0)))
        assertTrue(logic.listChunks(listOf(timed)).single().content.contains("5:00 PM"))
    }

    @Test
    fun `list emoji are sticky, unique, and toggling one completes and strikes its line`() {
        val a = service.create(Task(title = "A", isStarred = true))
        val b = service.create(Task(title = "B", isStarred = true))
        logic.onAdd(1L, "u", "-- C")

        val chunk = logic.listChunks(service.doing()).single()
        assertEquals(2, chunk.emojis.distinct().size)
        assertEquals(chunk.emojis, logic.listChunks(service.doing()).single().emojis)
        logic.recordList(7L, 99L, chunk)

        logic.onReaction(1L, DONE, added = true)
        val aEmoji = chunk.lines.single { it.taskId == a.id }.emoji!!
        // The newest list is re-rendered via listToRefresh, like a completion from anywhere else.
        assertEquals(ReactionOutcome.None, logic.onReaction(99L, aEmoji, added = true))
        assertTrue(service.get(a.id)!!.isComplete)
        val lines = logic.renderList(99L)!!.lines()
        assertTrue("- ~~A~~ $aEmoji" in lines)
        assertTrue("- ~~C~~ [↗](<u>)" in lines)
        assertTrue(lines.any { it.startsWith("- B ") })

        logic.onReaction(99L, aEmoji, added = false)
        assertFalse(service.get(a.id)!!.isComplete)
        assertFalse(service.get(b.id)!!.isComplete)
    }

    @Test
    fun `long lists split at Discord's reaction limit, counting only emoji lines`() {
        repeat(25) { service.create(Task(title = "T$it", isStarred = true)) }
        repeat(10) { logic.onAdd(it.toLong(), "u", "-- S$it") }
        val chunks = logic.listChunks(service.doing())
        assertEquals(listOf(20, 5), chunks.map { it.emojis.size })
        assertEquals(35, chunks.sumOf { it.lines.size })
    }

    @Test
    fun `long lists split at Discord's length limit, leaving room to strike every line`() {
        val url = "https://discord.com/channels/123456789012345678/123456789012345678/123456789012345678"
        repeat(30) { logic.onAdd(it.toLong(), url, "-- " + "x".repeat(120)) }
        val chunks = logic.listChunks(service.doing())
        assertTrue(chunks.size > 1)
        assertEquals(30, chunks.sumOf { it.lines.size })
        assertTrue(chunks.all { it.emojis.isEmpty() && it.content.length + 4 * it.lines.size <= 2000 })
    }

    @Test
    fun `commands resolve folders and reject unknown ones`() {
        val work = service.create(Task(type = TaskType.FOLDER, title = "Work"))
        service.create(Task(title = "In work", parentId = work.id))
        service.create(Task(title = "Starred elsewhere", isStarred = true))

        assertEquals(listOf("In work"), logic.command(".list work")!!.getOrThrow().map { it.title })
        assertEquals(listOf("Starred elsewhere"), logic.command(".list")!!.getOrThrow().map { it.title })
        assertTrue(logic.command(".doing nowhere")!!.isFailure)
        assertNull(logic.command("hello"))
    }

    @Test
    fun `emoji pool is single-codepoint, distinct, and avoids the control reactions`() {
        assertEquals(EMOJI_POOL.size, EMOJI_POOL.distinct().size)
        assertTrue(EMOJI_POOL.none { it in setOf(DONE, DELETE, STAR, MOVE_OUT) })
        assertTrue(EMOJI_POOL.all { it.codePointCount(0, it.length) == 1 && it.codePointAt(0) > 0x2000 })
    }

    @Test
    fun `completing from anywhere strikes the task in the newest list showing it`() {
        val refreshed = mutableListOf<Pair<Long, Long>>()
        store.onChange = { before, after -> logic.listToRefresh(before, after)?.let(refreshed::add) }
        val a = service.create(Task(title = "A", isStarred = true))
        val older = logic.listChunks(service.doing()).single()
        logic.recordList(7L, 98L, older)
        val newer = logic.listChunks(service.doing()).single()
        logic.recordList(7L, 99L, newer)

        service.update(a.id) { it.copy(title = "A2") } // not a completion: nothing to strike
        assertEquals(emptyList<Pair<Long, Long>>(), refreshed)
        service.complete(a.id) // e.g. from the web
        assertEquals(listOf(7L to 99L), refreshed)
        assertTrue(logic.renderList(99L)!!.contains("~~A~~"))

        // An older list reacted on is re-rendered directly instead.
        val emoji = older.lines.single().emoji!!
        assertTrue(logic.onReaction(98L, emoji, added = false) is ReactionOutcome.EditList)
    }

    @Test
    fun `completing in the app or on the web takes the task's emoji reaction off its newest list`() {
        val mirrored = mutableListOf<SourceReaction>()
        store.onChange = { before, after -> mirrored += logic.listReactions(before, after) }
        val a = service.create(Task(title = "A", isStarred = true))
        val chunk = logic.listChunks(service.doing()).single()
        logic.recordList(7L, 99L, chunk)
        val emoji = chunk.lines.single().emoji!!

        service.complete(a.id)
        assertEquals(listOf(SourceReaction(7L, 99L, emoji, add = false)), mirrored)
        assertTrue(logic.renderList(99L)!!.contains("~~A~~ $emoji")) // the line keeps its emoji
        service.uncomplete(a.id)
        assertEquals(SourceReaction(7L, 99L, emoji, add = true), mirrored.last())

        // Done from Discord (tapping the emoji): the user's tap already shows it.
        mirrored.clear()
        logic.onReaction(99L, emoji, added = true)
        assertEquals(emptyList<SourceReaction>(), mirrored)
    }
}
