package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.sync.SyncJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.io.File

// Kira's dev notes for Claude's backlog worker (docs/superpowers/specs/2026-10-18-dev-workflow-design.md):
// every DM an allowlisted user sends the bot is one. Kept apart from tasks and never synced, as JSON in kv
// ("dev:<id>"), with "devmsg:<Discord message id>" pointing each DM and bot message at its note.

@Serializable
data class DevReply(val text: String, val fromKira: Boolean, val at: Long)

@Serializable
data class DevNote(
    val id: Long,
    val project: String,
    val text: String,
    val state: String,
    val createdAt: Long,
    val updatedAt: Long,
    val tag: String? = null, // a Friday-run proposal's kind: hygiene, refactor, deps, vm
    val replies: List<DevReply> = emptyList(),
    val attachments: List<String> = emptyList(), // file names under attachmentsDir/<id>/
    val userId: Long? = null, // whose DM conversation it lives in
    val messageId: Long? = null // Kira's DM, or the bot's message for a proposal: where its state shows
)

const val DEFAULT_PROJECT = "raspberry"
const val SKIP = "⏸"
const val APPROVE = "👍"
// open: to do; working: a run has it; question: waiting on Kira; skip: Kira's "not now"; proposed: a
// Friday-run item waiting for her 👍 or a reply; deleted: she deleted the DM.
val DEV_STATES = setOf("open", "working", "done", "question", "skip", "proposed", "deleted")
private val STATE_EMOJI = mapOf("working" to "👀", "done" to "✅", "question" to "❓")
private const val MAX_CHARS = 2000 // Discord's message length limit

// What a change made through the API does in Discord; Bot provides it. `sent` gets each message's id.
interface DevDiscord {
    fun react(userId: Long, messageId: Long, add: String?, remove: Collection<String>)
    fun send(userId: Long, replyTo: Long?, text: String, sent: (Long) -> Unit)
}

class DevNotes(
    private val store: Store,
    private val projects: Set<String> = emptySet(), // prefixes besides raspberry ("roulette: …")
    val attachmentsDir: File = File("dev-attachments"),
    private val defaultUserId: Long? = null, // where proposals and plain messages go
    private val now: () -> Long = System::currentTimeMillis
) {
    @Volatile
    var discord: DevDiscord? = null

    fun get(id: Long): DevNote? = store.getValue("dev:$id")?.let { SyncJson.decodeFromString(DevNote.serializer(), it) }

    fun list(project: String?): List<DevNote> = store.valuesWithPrefix("dev:").values
        .map { SyncJson.decodeFromString(DevNote.serializer(), it) }
        .filter { project == null || it.project == project }
        .sortedBy { it.id }

    private fun noteFor(messageId: Long): DevNote? = store.getValue("devmsg:$messageId")?.toLongOrNull()?.let(::get)

    private fun save(note: DevNote) = store.setValue("dev:${note.id}", SyncJson.encodeToString(DevNote.serializer(), note))

    private fun create(project: String, text: String, state: String, userId: Long?, messageId: Long?, tag: String? = null): DevNote = store.transaction {
        val id = (store.getValue("devNextId")?.toLongOrNull() ?: 1)
        store.setValue("devNextId", (id + 1).toString())
        DevNote(id, project, text, state, now(), now(), tag, userId = userId, messageId = messageId).also {
            save(it)
            messageId?.let { m -> store.setValue("devmsg:$m", id.toString()) }
        }
    }

    private fun update(id: Long, change: (DevNote) -> DevNote?): DevNote? = store.transaction {
        val note = get(id) ?: return@transaction null
        change(note)?.copy(updatedAt = now())?.also(::save)
    }

    // A DM. A reply to one of the note's messages adds to that note (and answers its question); anything else
    // is a new note. Returns the note's id, for its attachments.
    fun onDm(userId: Long, messageId: Long, text: String, replyTo: Long?): Long {
        replyTo?.let(::noteFor)?.let { note ->
            store.setValue("devmsg:$messageId", note.id.toString())
            val answered = update(note.id) {
                it.copy(replies = it.replies + DevReply(text, fromKira = true, at = now()), state = if (it.state in setOf("question", "proposed")) "open" else it.state)
            }
            answered?.takeIf { it.state != note.state }?.let(::mirror)
            return note.id
        }
        val prefix = Regex("^([A-Za-z][\\w-]*):\\s*").find(text)?.takeIf { it.groupValues[1].lowercase() in projects }
        val project = prefix?.groupValues?.get(1)?.lowercase() ?: DEFAULT_PROJECT
        return create(project, prefix?.let { text.removeRange(it.range) } ?: text, "open", userId, messageId).id
    }

    fun addAttachment(id: Long, name: String) = update(id) { it.copy(attachments = it.attachments + name) }

    fun onEdit(messageId: Long, text: String) {
        val note = noteFor(messageId)?.takeIf { it.messageId == messageId } ?: return
        update(note.id) { it.copy(text = text) }
    }

    fun onDelete(messageId: Long) {
        val note = noteFor(messageId)?.takeIf { it.messageId == messageId } ?: return
        update(note.id) { it.copy(state = "deleted") }
    }

    // Kira's reactions on a note's message: ⏸ skips it (removing it unskips), 👍 approves a proposal.
    fun onReaction(messageId: Long, emoji: String, added: Boolean) {
        val note = noteFor(messageId)?.takeIf { it.messageId == messageId } ?: return
        val state = when {
            emoji == SKIP && added && note.state == "open" -> "skip"
            emoji == SKIP && !added && note.state == "skip" -> "open"
            emoji == APPROVE && added && note.state == "proposed" -> "open"
            else -> return
        }
        update(note.id) { it.copy(state = state) }
    }

    fun setState(id: Long, state: String): DevNote? {
        require(state in DEV_STATES) { "unknown state $state" }
        return update(id) { it.copy(state = state) }?.also(::mirror)
    }

    // Shows the state as the one matching reaction on the note's message.
    private fun mirror(note: DevNote) {
        val (userId, messageId) = (note.userId ?: return) to (note.messageId ?: return)
        val add = STATE_EMOJI[note.state]
        discord?.react(userId, messageId, add, STATE_EMOJI.values - setOfNotNull(add))
    }

    // From a run: a question or reply on a note (a Discord reply to it, recorded on the note), or with no note a
    // plain DM (the digest). Kira's reply to any of these messages lands on the note.
    fun message(id: Long?, text: String): Boolean {
        val note = id?.let { get(it) ?: return false }
        val userId = note?.userId ?: defaultUserId ?: return false
        note?.let { update(it.id) { n -> n.copy(replies = n.replies + DevReply(text, fromKira = false, at = now())) } }
        // ponytail: split at a fixed length, mid-word if need be; questions and digests rarely run that long.
        text.chunked(MAX_CHARS).forEachIndexed { i, chunk ->
            discord?.send(userId, note?.messageId.takeIf { i == 0 }, chunk) { sent ->
                note?.let { store.setValue("devmsg:$sent", it.id.toString()) }
            }
        }
        return true
    }

    // A Friday-run item for Kira: a note of its own, DM'd to her; it waits for her 👍 or reply.
    fun propose(project: String, tag: String, text: String): DevNote? {
        val userId = defaultUserId ?: return null
        val note = create(project, text, "proposed", userId, null, tag)
        discord?.send(userId, null, "[$tag] $text".take(MAX_CHARS)) { sent ->
            store.setValue("devmsg:$sent", note.id.toString())
            update(note.id) { it.copy(messageId = sent) }
        }
        return note
    }
}

// Bearer-token protected like /sync; tools/dev_notes.sh is the client.
fun Route.devRoutes(dev: DevNotes, hasToken: (RoutingContext) -> Boolean) {
    get("/api/dev") {
        if (!hasToken(this)) return@get call.respond(HttpStatusCode.Unauthorized)
        call.respond(dev.list(call.request.queryParameters["project"]))
    }
    // Only names the note lists, so no path from the request reaches the file system.
    get("/api/dev/{id}/attachments/{name}") {
        if (!hasToken(this)) return@get call.respond(HttpStatusCode.Unauthorized)
        val note = call.parameters["id"]?.toLongOrNull()?.let(dev::get) ?: return@get call.respond(HttpStatusCode.NotFound)
        val name = call.parameters["name"]?.takeIf { it in note.attachments } ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondFile(File(File(dev.attachmentsDir, note.id.toString()), name))
    }
    post("/api/dev/{id}/state") {
        if (!hasToken(this)) return@post call.respond(HttpStatusCode.Unauthorized)
        val state = call.receiveParameters()["state"]?.takeIf { it in DEV_STATES } ?: return@post call.respond(HttpStatusCode.BadRequest)
        val note = call.parameters["id"]?.toLongOrNull()?.let { dev.setState(it, state) } ?: return@post call.respond(HttpStatusCode.NotFound)
        call.respond(note)
    }
    // ?id= a note to reply on; without, a plain DM (the digest).
    post("/api/dev/message") {
        if (!hasToken(this)) return@post call.respond(HttpStatusCode.Unauthorized)
        val params = call.receiveParameters()
        val text = params["text"]?.takeIf { it.isNotBlank() } ?: return@post call.respond(HttpStatusCode.BadRequest)
        call.respond(if (dev.message(params["id"]?.toLongOrNull(), text)) HttpStatusCode.OK else HttpStatusCode.NotFound)
    }
    post("/api/dev/propose") {
        if (!hasToken(this)) return@post call.respond(HttpStatusCode.Unauthorized)
        val params = call.receiveParameters()
        val text = params["text"]?.takeIf { it.isNotBlank() } ?: return@post call.respond(HttpStatusCode.BadRequest)
        val note = dev.propose(params["project"] ?: DEFAULT_PROJECT, params["tag"] ?: "hygiene", text)
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
        call.respond(note)
    }
}
