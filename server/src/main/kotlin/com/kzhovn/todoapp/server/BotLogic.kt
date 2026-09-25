package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.nextRollover
import com.kzhovn.todoapp.data.hasTime
import com.kzhovn.todoapp.quickadd.QuickAddParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.kzhovn.todoapp.sync.SyncJson
import com.kzhovn.todoapp.sync.SyncRow
import com.kzhovn.todoapp.sync.TASKS
import com.kzhovn.todoapp.sync.toTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val DONE = "✅"
const val DELETE = "❌"
const val STAR = "⭐"
const val MOVE_OUT = "🔽" // on a nudge: unstar the task (no variation selector, so reactions match)
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val NUDGE_AFTER = 3 * DAY_MS
const val NOTHING = "🎉 Nothing here 🎉"

val HELP = """
**Adding**
`-- call mom` a task (starred, in Personal)
`--work: send report` into the folder Work
`--d: shower` just for today (gone at day rollover)
`-- x -d fri` / `due fri 5pm` / `due 3pm` due date and time
`-- x -s tomorrow` / `start mon 9am` start date
`-- x?` a maybe (hidden from Active, never starred)
Reply to a todo with a todo: the first waits for the new one.

**On a todo's message**
✅ complete · ❌ delete · ⭐ star (remove the reaction to undo)
Editing the message updates the task; deleting it deletes the task.

**Lists**
`.doing` / `.list` Doing · `.active` Active · `.rand` one random active task
`.list work` open tasks in a folder; `.doing work` / `.active work` filter by folder
Tap an item's emoji to complete it (un-tap to undo).

**Nudges**
With the morning digest, anything in Doing for 3+ days gets a message (again every 3 days):
🔽 moves it out (unstars it); reply with `-- step` lines to break it into subtasks.
""".trimIndent()
// Adds without a `folder:` prefix land here, if a folder with this name exists.
const val DEFAULT_FOLDER = "Personal"
// `--d: shower` (or rusabot's `--daily:`) makes a task that expires at the next day rollover.
private val DAILY_PREFIXES = setOf("d", "daily")
private const val MAX_REACTIONS = 20 // Discord's per-message limit on distinct reactions
private const val MAX_CHARS = 2000 // Discord's message length limit

// Single-codepoint emoji only: variation-selector forms don't reliably round-trip through
// Discord's reaction events. Must never contain DONE/DELETE/STAR.
val EMOJI_POOL: List<String> = (
    "🍎🍐🍊🍋🍌🍉🍇🍓🫐🍈🍒🍑🥭🍍🥥🥝🍅🍆🥑🥦🥬🥒🌽🥕🧄🧅🥔🍠🥐🥯🍞🥖🥨🧀🥚🍳🧈🥞🧇🥓🍗🍖🌭🍔🍟🍕" +
        "🥪🌮🌯🥗🍝🍜🍲🍛🍣🍱🥟🍤🍙🍚🍘🍥🥠🍢🍡🍧🍨🍦🥧🧁🍰🎂🍮🍭🍬🍫🍿🍩🍪🌰🥜🍯" +
        "🌵🎄🌲🌳🌴🌱🌿🍀🎍🎋🍃🍂🍁🍄🐚🌾💐🌷🌹🥀🌺🌸🌼🌻🌞🌝🌛🌚🌕🌙🌎🪐" +
        "🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮🐷🐸🐵🐔🐧🐤🦆🦅🦉🦇🐺🐗🐴🦄🐝🐛🦋🐌🐞🐜🦂🐢🐍🦎🦖🦕🐙🦑🦐🦞🦀🐡🐠🐟🐬🐳🐋🦈" +
        "🐊🐅🐆🦓🦍🦧🐘🦛🦏🐪🐫🦒🦘🐃🐂🐄🐎🐖🐏🐑🦙🐐🦌🐕🐩🐈🐓🦃🦚🦜🦢🦩🐇🦝🦨🦡🦦🦥🐁🐀🦔"
    ).codePoints().toArray().map { String(Character.toChars(it)) }

// Null emoji: the task came from a `--` message, so the line links there and ✅ on it completes it.
@Serializable
data class ListLine(val emoji: String? = null, val taskId: Long, val text: String)

// What the bot remembers about a message: either the `--` message a task came from, or a list it
// posted (whose emoji reactions complete tasks).
@Serializable
data class MessageLink(
    val taskId: Long? = null,
    val text: String? = null,
    val lines: List<ListLine> = emptyList(),
    val nudgeFor: Long? = null // a "stuck in Doing" nudge about this task
)

data class ListChunk(val content: String, val emojis: List<String>, val lines: List<ListLine>)

// A reaction the bot should add to (or remove from) a task's source message.
data class SourceReaction(val channelId: Long, val messageId: Long, val emoji: String, val add: Boolean)

sealed interface ReactionOutcome {
    data object None : ReactionOutcome
    data class EditList(val content: String) : ReactionOutcome
    data object DeleteIfBotMessage : ReactionOutcome
}

class BotLogic(private val service: TaskService, private val store: Store) {

    // `-- text` or `--folder: text`. Returns null for messages that aren't adds. A bare add is
    // starred so it lands in Doing; any start/due date or folder means the user placed it
    // deliberately, and a trailing "?" (a maybe) is never starred.
    fun parseAdd(content: String): Task? {
        if (!content.startsWith("--")) return null
        val body = content.removePrefix("--").trim()
        val prefix = body.substringBefore(':', missingDelimiterValue = "")
        val daily = prefix.trim().lowercase() in DAILY_PREFIXES
        val explicitFolder = if (daily) null else prefix.takeIf { it.isNotBlank() }?.let(service::findFolder)
        val parsed = QuickAddParser.parse(if (daily || explicitFolder != null) body.substringAfter(':') else body)
        val bare = explicitFolder == null && parsed.startDate == null && parsed.dueDate == null && !parsed.isMaybe
        val folder = explicitFolder ?: service.findFolder(DEFAULT_FOLDER)
        val expiresAt = if (daily) nextRollover(service.now(), service.rolloverHour()) else null
        return parsed.takeIf { it.title.isNotBlank() }?.copy(parentId = folder?.id, isStarred = bare, expiresAt = expiresAt)
    }

    // Replying to another todo's `--` message with a new todo makes the replied-to task wait for
    // the new one ("-- hang mirror" <- reply "-- move mirror upstairs").
    fun onAdd(messageId: Long, jumpUrl: String, content: String, replyToMessageId: Long? = null): Boolean {
        replyToMessageId?.let(::link)?.nudgeFor?.let { return breakUp(it, content) }
        val task = service.create(parseAdd(content) ?: return false)
        saveLink(messageId, MessageLink(taskId = task.id, text = content))
        store.setValue("src:${task.id}", jumpUrl)
        replyToMessageId?.let(::link)?.taskId?.let { waiting -> service.addDependency(waiting, task.id) }
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

    // Mirrors a completion/deletion made in the app onto the task's `--` message: ✅/❌ appear when
    // it's completed/deleted and disappear when undone.
    fun sourceReactions(before: SyncRow?, after: SyncRow): List<SourceReaction> {
        if (after.table != TASKS || before == null) return emptyList()
        val url = store.getValue("src:${after.id}") ?: return emptyList()
        val (channelId, messageId) = url.split('/').takeLast(2).map { it.toLongOrNull() ?: return emptyList() }
        return listOfNotNull(
            (after.toTask().isComplete).takeIf { it != before.toTask().isComplete }?.let { SourceReaction(channelId, messageId, DONE, it) },
            after.isDeleted.takeIf { it != before.isDeleted }?.let { SourceReaction(channelId, messageId, DELETE, it) }
        )
    }

    // Replying to a nudge with `--` lines turns each into a subtask of the stuck task. They aren't
    // starred, so breaking a task up doesn't flood Doing.
    private fun breakUp(taskId: Long, content: String): Boolean {
        val steps = content.lines().map { it.trim() }.mapNotNull(::parseAdd)
        steps.forEach { service.create(it.copy(parentId = taskId, isStarred = false)) }
        return steps.isNotEmpty()
    }

    // Run with each morning digest. A task counts as entering Doing at the first digest that sees it
    // there (leaving resets it); it's nudged once it's been there 3 days, then every 3 days after.
    fun dueNudges(): List<Pair<Task, Int>> = store.transaction {
        val now = service.now()
        val doing = service.doing().associateBy { it.id }
        val since = store.valuesWithPrefix("doingSince:").mapKeys { it.key.removePrefix("doingSince:").toLong() }
        since.keys.filter { it !in doing }.forEach {
            store.setValue("doingSince:$it", null)
            store.setValue("nudged:$it", null)
        }
        doing.values.mapNotNull { task ->
            val entered = since[task.id]?.toLong() ?: now.also { store.setValue("doingSince:${task.id}", it.toString()) }
            val lastNudge = store.getValue("nudged:${task.id}")?.toLong() ?: entered
            if (now - lastNudge < NUDGE_AFTER) return@mapNotNull null
            store.setValue("nudged:${task.id}", now.toString())
            task to ((now - entered) / DAY_MS).toInt()
        }
    }

    fun nudgeText(task: Task, days: Int) =
        "“${task.title}” has been in Doing for $days days. React $MOVE_OUT to move it out, or reply with `-- step` lines to break it up."

    fun recordNudge(messageId: Long, taskId: Long) = saveLink(messageId, MessageLink(nudgeFor = taskId))

    fun onReaction(messageId: Long, emoji: String, added: Boolean): ReactionOutcome {
        val link = link(messageId)
        link?.nudgeFor?.let { stuck ->
            if (emoji == MOVE_OUT) service.setStarred(stuck, !added) // un-reacting puts it back
            return ReactionOutcome.None
        }
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
        val emojis = assignEmoji(tasks.filter { store.getValue("src:${it.id}") == null })
        val chunks = mutableListOf<MutableList<ListLine>>()
        var chars = 0
        for (task in tasks) {
            val line = ListLine(emojis[task.id], task.id, describe(task))
            // Sized as if struck, so striking lines later can't push an edit past the limit.
            val size = renderLine(line, done = true).length + 1
            val current = chunks.lastOrNull()
            val full = current == null || chars + size > MAX_CHARS ||
                (line.emoji != null && current.count { it.emoji != null } == MAX_REACTIONS)
            if (full) {
                chunks += mutableListOf<ListLine>()
                chars = 0
            }
            chunks.last().add(line)
            chars += size
        }
        return chunks.map { lines -> ListChunk(render(lines), lines.mapNotNull { it.emoji }, lines) }
    }

    fun recordList(messageId: Long, chunk: ListChunk) {
        if (chunk.lines.isNotEmpty()) saveLink(messageId, MessageLink(lines = chunk.lines))
    }

    // `.doing`, `.list`, `.active`, `.rand` with an optional folder name. Returns null for
    // non-commands, or a plain reply for errors.
    fun command(content: String): Result<List<Task>>? {
        val name = content.substringBefore(' ').lowercase()
        if (name !in setOf(".doing", ".list", ".active", ".rand")) return null
        service.purgeExpired()
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
        renderLine(line, done = task == null || task.isComplete)
    }

    // A masked link keeps lines short; `<>` around the URL suppresses its embed.
    private fun renderLine(line: ListLine, done: Boolean): String {
        val tail = line.emoji ?: store.getValue("src:${line.taskId}")?.let { "[↗](<$it>)" }
        return "- " + (if (done) "~~${line.text}~~" else line.text) + tail?.let { " $it" }.orEmpty()
    }

    private fun describe(task: Task): String {
        val title = task.title.take(120) + if (task.isMaybe) " ?" else ""
        val due = service.effectiveDueDate(task)?.let {
            " · due " + SimpleDateFormat(if (hasTime(it)) "EEE d MMM h:mm a" else "EEE d MMM", Locale.US).format(Date(it))
        }.orEmpty()
        return title + due
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
