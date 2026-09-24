package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.RecurrenceType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
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
    fun `source-message reactions complete, delete, star, and undo`() {
        assertTrue(logic.onAdd(1L, "https://discord.com/channels/@me/2/1", "-- call mom"))
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
    fun `list emoji are sticky, unique, and toggling one completes and strikes its line`() {
        val a = service.create(Task(title = "A", isStarred = true))
        val b = service.create(Task(title = "B", isStarred = true))

        val chunk = logic.listChunks(service.doing()).single()
        assertEquals(2, chunk.emojis.distinct().size)
        assertEquals(chunk.emojis, logic.listChunks(service.doing()).single().emojis)
        logic.recordList(99L, chunk)

        val aEmoji = chunk.lines.single { it.taskId == a.id }.emoji
        val outcome = logic.onReaction(99L, aEmoji, added = true) as ReactionOutcome.EditList
        assertTrue(service.get(a.id)!!.isComplete)
        assertTrue(outcome.content.contains("~~A"))
        assertFalse(outcome.content.contains("~~B"))

        logic.onReaction(99L, aEmoji, added = false)
        assertFalse(service.get(a.id)!!.isComplete)
        assertFalse(service.get(b.id)!!.isComplete)
    }

    @Test
    fun `long lists split at Discord's reaction limit`() {
        repeat(25) { service.create(Task(title = "T$it", isStarred = true)) }
        assertEquals(listOf(20, 5), logic.listChunks(service.doing()).map { it.emojis.size })
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
        assertTrue(EMOJI_POOL.none { it in setOf(DONE, DELETE, STAR, ADDED) })
        assertTrue(EMOJI_POOL.all { it.codePointCount(0, it.length) == 1 && it.codePointAt(0) > 0x2000 })
    }
}
