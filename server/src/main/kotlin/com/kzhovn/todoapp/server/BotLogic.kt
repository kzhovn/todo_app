package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.quickadd.QuickAddParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.kzhovn.todoapp.sync.SyncJson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val DONE = "✅"
const val DELETE = "❌"
const val STAR = "⭐"
const val ADDED = "📥"
const val NOTHING = "🎉 Nothing here 🎉"
private const val MAX_REACTIONS = 20 // Discord's per-message limit on distinct reactions

// Single-codepoint emoji only: variation-selector forms don't reliably round-trip through
// Discord's reaction events. Must never contain DONE/DELETE/STAR/ADDED.
val EMOJI_POOL: List<String> = (
    "🍎🍐🍊🍋🍌🍉🍇🍓🫐🍈🍒🍑🥭🍍🥥🥝🍅🍆🥑🥦🥬🥒🌽🥕🧄🧅🥔🍠🥐🥯🍞🥖🥨🧀🥚🍳🧈🥞🧇🥓🍗🍖🌭🍔🍟🍕" +
        "🥪🌮🌯🥗🍝🍜🍲🍛🍣🍱🥟🍤🍙🍚🍘🍥🥠🍢🍡🍧🍨🍦🥧🧁🍰🎂🍮🍭🍬🍫🍿🍩🍪🌰🥜🍯" +
        "🌵🎄🌲🌳🌴🌱🌿🍀🎍🎋🍃🍂🍁🍄🐚🌾💐🌷🌹🥀🌺🌸🌼🌻🌞🌝🌛🌚🌕🌙🌎🪐" +
        "🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮🐷🐸🐵🐔🐧🐤🦆🦅🦉🦇🐺🐗🐴🦄🐝🐛🦋🐌🐞🐜🦂🐢🐍🦎🦖🦕🐙🦑🦐🦞🦀🐡🐠🐟🐬🐳🐋🦈" +
        "🐊🐅🐆🦓🦍🦧🐘🦛🦏🐪🐫🦒🦘🐃🐂🐄🐎🐖🐏🐑🦙🐐🦌🐕🐩🐈🐓🦃🦚🦜🦢🦩🐇🦝🦨🦡🦦🦥🐁🐀🦔"
    ).codePoints().toArray().map { String(Character.toChars(it)) }

@Serializable
data class ListLine(val emoji: String, val taskId: Long, val text: String)

// What the bot remembers about a message: either the `--` message a task came from, or a list it
// posted (whose emoji reactions complete tasks).
@Serializable
data class MessageLink(val taskId: Long? = null, val text: String? = null, val lines: List<ListLine> = emptyList())

data class ListChunk(val content: String, val emojis: List<String>, val lines: List<ListLine>)

sealed interface ReactionOutcome {
    data object None : ReactionOutcome
    data class EditList(val content: String) : ReactionOutcome
    data object DeleteIfBotMessage : ReactionOutcome
}

class BotLogic(private val service: TaskService, private val store: Store) {

    // `-- text` or `--folder: text`. Returns null for messages that aren't adds.
    fun parseAdd(content: String): Task? {
        if (!content.startsWith("--")) return null
        val body = content.removePrefix("--").trim()
        val prefix = body.substringBefore(':', missingDelimiterValue = "")
        val folder = prefix.takeIf { it.isNotBlank() }?.let(service::findFolder)
        val parsed = QuickAddParser.parse(if (folder != null) body.substringAfter(':') else body)
        return parsed.takeIf { it.title.isNotBlank() }?.copy(parentId = folder?.id)
    }

    fun onAdd(messageId: Long, jumpUrl: String, content: String): Boolean {
        val task = service.create(parseAdd(content) ?: return false)
        saveLink(messageId, MessageLink(taskId = task.id, text = content))
        store.setValue("src:${task.id}", jumpUrl)
        return true
    }

    // Only fields whose parse changed are written, so a typo fix doesn't clobber a star or due
    // date set in the app since.
    fun onEdit(messageId: Long, content: String) {
        val link = link(messageId) ?: return
        val taskId = link.taskId ?: return
        val old = parseAdd(link.text.orEmpty()) ?: return
        val new = parseAdd(content) ?: return
        service.update(taskId) {
            it.copy(
                title = if (old.title != new.title) new.title else it.title,
                startDate = if (old.startDate != new.startDate) new.startDate else it.startDate,
                dueDate = if (old.dueDate != new.dueDate) new.dueDate else it.dueDate,
                parentId = if (old.parentId != new.parentId) new.parentId else it.parentId
            )
        }
        saveLink(messageId, link.copy(text = content))
    }

    fun onDelete(messageId: Long) {
        link(messageId)?.taskId?.let(service::delete)
        store.setValue("msg:$messageId", null)
    }

    fun onReaction(messageId: Long, emoji: String, added: Boolean): ReactionOutcome {
        val link = link(messageId)
        val sourceTask = link?.taskId
        if (sourceTask != null) {
            when (emoji) {
                DONE -> if (added) service.complete(sourceTask) else service.uncomplete(sourceTask)
                DELETE -> if (added) service.delete(sourceTask) else service.restore(sourceTask)
                STAR -> service.setStarred(sourceTask, added)
            }
            return ReactionOutcome.None
        }
        val line = link?.lines?.firstOrNull { it.emoji == emoji }
        if (line != null) {
            if (added) service.complete(line.taskId) else service.uncomplete(line.taskId)
            return ReactionOutcome.EditList(render(link.lines))
        }
        return if (emoji == DELETE && added) ReactionOutcome.DeleteIfBotMessage else ReactionOutcome.None
    }

    // Returns the chunks to post; call recordList with each posted message's id.
    fun listChunks(tasks: List<Task>): List<ListChunk> {
        if (tasks.isEmpty()) return listOf(ListChunk(NOTHING, emptyList(), emptyList()))
        val emojis = assignEmoji(tasks)
        return tasks.map { ListLine(emojis.getValue(it.id), it.id, describe(it)) }
            .chunked(MAX_REACTIONS)
            .map { lines -> ListChunk(render(lines), lines.map { it.emoji }, lines) }
    }

    fun recordList(messageId: Long, chunk: ListChunk) {
        if (chunk.lines.isNotEmpty()) saveLink(messageId, MessageLink(lines = chunk.lines))
    }

    // `.doing`, `.list`, `.active`, `.rand` with an optional folder name. Returns null for
    // non-commands, or a plain reply for errors.
    fun command(content: String): Result<List<Task>>? {
        val name = content.substringBefore(' ').lowercase()
        if (name !in setOf(".doing", ".list", ".active", ".rand")) return null
        val arg = content.substringAfter(' ', "").trim()
        val folder = if (arg.isEmpty()) null else service.findFolder(arg)
            ?: return Result.failure(IllegalArgumentException("No folder named \"$arg\"."))
        val tasks = when (name) {
            ".doing" -> service.doing(folder?.id)
            ".list" -> if (folder == null) service.doing() else service.openInFolder(folder.id)
            ".active" -> service.active(folder?.id)
            else -> listOfNotNull(service.active(folder?.id).randomOrNull())
        }
        return Result.success(tasks)
    }

    // Struck lines are ones whose task is now done or gone; rendering from live state keeps an old
    // list message correct however many times its reactions are toggled.
    private fun render(lines: List<ListLine>): String = lines.joinToString("\n") { line ->
        val task = service.get(line.taskId)
        val done = task == null || task.isComplete
        "${line.emoji} " + if (done) "~~${line.text}~~" else line.text
    }

    private fun describe(task: Task): String {
        val title = task.title.take(120)
        val due = service.effectiveDueDate(task)?.let { " · due " + SimpleDateFormat("EEE d MMM", Locale.US).format(Date(it)) }.orEmpty()
        val star = if (task.isStarred) " $STAR" else ""
        val link = store.getValue("src:${task.id}")?.let { " [↗](<$it>)" }.orEmpty()
        return title + star + due + link
    }

    // Sticky: an open task keeps its emoji across listings. New assignments take the free emoji
    // that was assigned longest ago, so a just-completed task's emoji isn't immediately reused.
    // ponytail: with more open listed tasks than the pool (~230), the oldest assignment gets stolen.
    private fun assignEmoji(tasks: List<Task>): Map<Long, String> = store.transaction {
        val openIds = service.tasks().filter { !it.isComplete }.map { it.id }.toSet()
        val assigned = store.valuesWithPrefix("emoji:")
            .mapKeys { it.key.removePrefix("emoji:").toLong() }
            .filterKeys { it in openIds }
            .toMutableMap()
        val lastUsed = store.valuesWithPrefix("assigned:").mapKeys { it.key.removePrefix("assigned:") }
        val now = System.currentTimeMillis()
        val result = mutableMapOf<Long, String>()
        for (task in tasks) {
            val emoji = assigned[task.id]?.takeIf { it !in result.values }
                ?: EMOJI_POOL.filter { it !in assigned.values && it !in result.values }
                    .minByOrNull { lastUsed[it]?.toLong() ?: 0L }
                ?: EMOJI_POOL.filter { it !in result.values }.minBy { lastUsed[it]?.toLong() ?: 0L }
            if (assigned[task.id] != emoji) {
                store.setValue("emoji:${task.id}", emoji)
                store.setValue("assigned:$emoji", now.toString())
                assigned[task.id] = emoji
            }
            result[task.id] = emoji
        }
        result
    }

    private fun link(messageId: Long): MessageLink? =
        store.getValue("msg:$messageId")?.let { SyncJson.decodeFromString<MessageLink>(it) }

    private fun saveLink(messageId: Long, link: MessageLink) =
        store.setValue("msg:$messageId", SyncJson.encodeToString(link))
}
