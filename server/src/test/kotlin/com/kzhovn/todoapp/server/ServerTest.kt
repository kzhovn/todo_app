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
import com.kzhovn.todoapp.sync.taskFields
import com.kzhovn.todoapp.sync.toTask
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
        assertEquals(java.util.Calendar.getInstance().startOfDay(), due.dueDate)
    }

    @Test
    fun `app-side completion and deletion of a Discord task mirror onto its message`() {
        val mirrored = mutableListOf<SourceReaction>()
        store.onSyncedChange = { before, after -> mirrored += logic.sourceReactions(before, after) }
        logic.onAdd(55L, "https://discord.com/channels/1/22/55", "-- call mom")
        val id = service.tasks().single().id

        fun push(change: (Task) -> Task, now: Long) {
            val base = store.get(TASKS, id)!!
            store.sync(SyncRequest(0, listOf(diff(TASKS, id, base, taskFields(change(base.toTask()), emptySet(), emptySet()), now)!!)))
        }
        push({ it.copy(isComplete = true) }, now + 10)
        push({ it.copy(isComplete = false) }, now + 20)
        logic.onReaction(55L, DONE, added = true) // completed from Discord itself: no mirror

        assertEquals(listOf(SourceReaction(22, 55, DONE, true), SourceReaction(22, 55, DONE, false)), mirrored)
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
    fun `deleting the source message soft-deletes the task`() {
        logic.onAdd(1L, "u", "-- call mom")
        logic.onDelete(1L)
        assertTrue(service.tasks().isEmpty())
    }

    @Test
    fun `source-linked lines end in their link, app lines in their reaction emoji`() {
        logic.onAdd(1L, "https://discord.com/channels/@me/2/1", "-- call mom")
        val app = service.create(Task(title = "Buy milk", isStarred = true, dueDate = now))

        val chunk = logic.listChunks(service.doing()).single()
        val emoji = chunk.lines.single { it.taskId == app.id }.emoji!!
        assertEquals(listOf(emoji), chunk.emojis)
        val lines = chunk.content.lines()
        assertTrue("- call mom [↗](<https://discord.com/channels/@me/2/1>)" in lines)
        assertTrue(lines.any { Regex("- Buy milk · due \\w{3} \\d+ \\w{3} $emoji").matches(it) })
    }

    @Test
    fun `list emoji are sticky, unique, and toggling one completes and strikes its line`() {
        val a = service.create(Task(title = "A", isStarred = true))
        val b = service.create(Task(title = "B", isStarred = true))
        logic.onAdd(1L, "u", "-- C")

        val chunk = logic.listChunks(service.doing()).single()
        assertEquals(2, chunk.emojis.distinct().size)
        assertEquals(chunk.emojis, logic.listChunks(service.doing()).single().emojis)
        logic.recordList(99L, chunk)

        logic.onReaction(1L, DONE, added = true)
        val aEmoji = chunk.lines.single { it.taskId == a.id }.emoji!!
        val outcome = logic.onReaction(99L, aEmoji, added = true) as ReactionOutcome.EditList
        assertTrue(service.get(a.id)!!.isComplete)
        val lines = outcome.content.lines()
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
        assertTrue(EMOJI_POOL.none { it in setOf(DONE, DELETE, STAR) })
        assertTrue(EMOJI_POOL.all { it.codePointCount(0, it.length) == 1 && it.codePointAt(0) > 0x2000 })
    }
}
