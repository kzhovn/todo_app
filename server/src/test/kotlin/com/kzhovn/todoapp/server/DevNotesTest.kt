package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.sync.SyncJson
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DevNotesTest {
    private val store = Store(File.createTempFile("todo", ".db").apply { deleteOnExit() }.path)
    private val dir = Files.createTempDirectory("dev").toFile()
    private val dev = DevNotes(store, projects = setOf("roulette"), attachmentsDir = dir, defaultUserId = KIRA) { 1000L }

    // What the bot would do in Discord; each sent message gets the next id from 900.
    private val reactions = mutableListOf<Pair<Long, String?>>()
    private val sent = mutableListOf<Triple<Long?, String, Long>>()

    init {
        dev.discord = object : DevDiscord {
            override fun react(userId: Long, messageId: Long, add: String?, remove: Collection<String>) {
                reactions += messageId to add
            }

            override fun send(userId: Long, replyTo: Long?, text: String, sent: (Long) -> Unit) {
                val id = 900L + this@DevNotesTest.sent.size
                this@DevNotesTest.sent += Triple(replyTo, text, id)
                sent(id)
            }
        }
    }

    @Test
    fun `a DM becomes an open note, and a known prefix picks the project`() {
        val a = dev.get(dev.onDm(KIRA, 1, "the widget flickers", null))!!
        assertEquals("raspberry" to "the widget flickers", a.project to a.text)
        assertEquals("open", a.state)
        val b = dev.get(dev.onDm(KIRA, 2, "roulette: skip weekends", null))!!
        assertEquals("roulette" to "skip weekends", b.project to b.text)
        // An unknown prefix is just text.
        assertEquals("raspberry", dev.get(dev.onDm(KIRA, 3, "note: buy milk", null))!!.project)
        assertEquals(listOf(a.id, b.id), dev.list(null).map { it.id }.take(2))
        assertEquals(listOf(b.id), dev.list("roulette").map { it.id })
    }

    @Test
    fun `a question goes out as a reply, and Kira's reply to it reopens the note`() {
        val id = dev.onDm(KIRA, 10, "make sync faster", null)
        dev.setState(id, "question")
        assertEquals(10L to "❓", reactions.last())
        dev.message(id, "Which screen feels slow?")
        val (replyTo, _, botMessage) = sent.single()
        assertEquals(10L, replyTo)

        assertEquals(id, dev.onDm(KIRA, 11, "the All list", botMessage))
        val note = dev.get(id)!!
        assertEquals("open", note.state)
        assertEquals(listOf(false, true), note.replies.map { it.fromKira })
        assertEquals("the All list", note.replies.last().text)
        assertEquals(10L to null, reactions.last()) // the ❓ is taken off
    }

    @Test
    fun `reactions skip and unskip a note, and approve a proposal`() {
        val id = dev.onDm(KIRA, 20, "dark mode", null)
        dev.onReaction(20, SKIP, added = true)
        assertEquals("skip", dev.get(id)!!.state)
        dev.onReaction(20, SKIP, added = false)
        assertEquals("open", dev.get(id)!!.state)

        val proposal = dev.propose("raspberry", "refactor", "merge the two planners")!!
        assertEquals("proposed", proposal.state)
        val (_, text, botMessage) = sent.single()
        assertEquals("[refactor] merge the two planners", text)
        assertEquals(botMessage, dev.get(proposal.id)!!.messageId)
        dev.onReaction(botMessage, APPROVE, added = true)
        assertEquals("open", dev.get(proposal.id)!!.state)
    }

    @Test
    fun `editing a DM updates its note and deleting it marks it deleted`() {
        val id = dev.onDm(KIRA, 30, "typo in labls", null)
        dev.onEdit(30, "typo in labels")
        assertEquals("typo in labels", dev.get(id)!!.text)
        dev.onDelete(30)
        assertEquals("deleted", dev.get(id)!!.state)
    }

    @Test
    fun `a plain message goes to Kira with no note, and a long one is split`() {
        assertTrue(dev.message(null, "x".repeat(2500)))
        assertEquals(listOf(2000, 500), sent.map { it.second.length })
        assertEquals(listOf<Long?>(null, null), sent.map { it.first })
        assertEquals(false, dev.message(999, "no such note"))
    }

    @Test
    fun `the api needs the token, lists notes, sets state and serves only listed attachments`() = testApplication {
        application { api(store, "secret", dev = dev) }
        val id = dev.onDm(KIRA, 40, "crash on rotate", null)
        File(File(dir, "$id").apply { mkdirs() }, "40-0-shot.png").writeText("png")
        dev.addAttachment(id, "40-0-shot.png")
        File(dir, "secret.txt").writeText("not yours")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/dev").status)
        val auth = "Bearer secret"
        val list = client.get("/api/dev?project=raspberry") { header("Authorization", auth) }.bodyAsText()
        assertEquals("crash on rotate", SyncJson.decodeFromString(ListSerializer(DevNote.serializer()), list).single().text)

        val working = client.submitForm("/api/dev/$id/state", parameters { append("state", "working") }) { header("Authorization", auth) }
        assertEquals(HttpStatusCode.OK, working.status)
        assertEquals("working", dev.get(id)!!.state)
        assertEquals(HttpStatusCode.BadRequest, client.submitForm("/api/dev/$id/state", parameters { append("state", "bogus") }) { header("Authorization", auth) }.status)

        assertEquals("png", client.get("/api/dev/$id/attachments/40-0-shot.png") { header("Authorization", auth) }.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/dev/$id/attachments/..%2Fsecret.txt") { header("Authorization", auth) }.status)
    }

    @Test
    fun `without a user to send to, a proposal is refused`() {
        val alone = DevNotes(store, attachmentsDir = dir)
        assertNull(alone.propose("raspberry", "hygiene", "x"))
    }

    private companion object {
        const val KIRA = 7L
    }
}
