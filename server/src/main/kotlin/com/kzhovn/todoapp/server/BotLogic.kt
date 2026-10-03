package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.data.hasTime
import com.kzhovn.todoapp.quickadd.QuickAdd
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
private const val NUDGE_SLACK = 60L * 60 * 1000
const val NOTHING = "🎉 Nothing here 🎉"

val HELP = """
**Adding**
`-- call mom` a task (starred, in Personal)
`--work: send report` into the folder Work (or a project)
`--groceries: milk, eggs` items into the checklist Groceries
`-- packing [passport, charger]` a new checklist
`--d: shower` today only (gone at day rollover)
`-- x -d fri` / `due fri 5pm` / `due 3pm` due date and time
`-- x -s tomorrow` / `start mon 9am` start date
Dates: `today`, `mon`, `next fri`, `+3d`, `in 2 weeks`, `+2h`, `oct 12`, `next week`, `weekend`
`-- x every mon, thu` / `every 2 weeks` / `every 1st sat` repeats
`-- x every 4 days after done` repeats after completion
`-- x due fri 3pm remind 30m` a reminder
`-- x @home` a context
`-- x ~30m` / `-- 30m of x` a timed task
`-- x*` starred · `-- x?` a maybe (hidden from Active, never starred)
`-- x -p` pin it · `-- x -f` focus on it
`-- call bank // ask about fees` everything after // (or after the first line) is the note
`✅ fixed the sink` logs something already done (a completed task, for Review)
Reply to a todo with a todo: the first depends on the new one.

**On a todo's message**
✅ complete · ❌ delete · ⭐ star (remove the reaction to undo)
Editing the message updates the task; deleting it deletes the task.

**Lists**
`.doing` / `.list` Doing · `.active` Active · `.rand` one random active task
`.list work` open tasks in a folder (or a checklist's items); `.doing work` / `.active work` filter by folder
Tap an item's emoji to complete it (un-tap to undo).

**Nudges**
With the morning digest, anything in Doing for 3+ days gets a message (again every 3 days):
🔽 moves it out (unstars it); reply with `-- step` lines to break it into subtasks.
""".trimIndent()
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

// A reaction the bot should add to (or remove from) a message: a task's source message, or a list.
data class SourceReaction(val channelId: Long, val messageId: Long, val emoji: String, val add: Boolean)

sealed interface ReactionOutcome {
    data object None : ReactionOutcome
    data class EditList(val content: String) : ReactionOutcome
    data object DeleteIfBotMessage : ReactionOutcome
}

class BotLogic(private val service: TaskService, private val store: Store) {

    // `-- text` with the quick-add syntax every device shares (`--work: text` into a folder,
    // `--groceries: milk, eggs` into a checklist...). Returns null for messages that aren't adds. A bare
    // add is starred so it lands in Doing; any start/due date or folder means the user placed it
    // deliberately, and a trailing "?" (a maybe) is never starred.
    // `✅ did the laundry` logs something already done: the same syntax, never starred (onAdd completes it).
    fun planAdd(content: String): QuickAdd? {
        val logged = content.startsWith(DONE)
        if (!content.startsWith("--") && !logged) return null
        val add = service.planQuickAdd(content.removePrefix("--").removePrefix(DONE).trim()).takeUnless { it.isEmpty } ?: return null
        val task = add.task ?: return add.takeUnless { logged }
        if (logged) return add.copy(task = task.copy(parentId = task.parentId ?: service.defaultFolderId(), isStarred = false), pin = false, focus = false)
        val bare = task.parentId == null && task.startDate == null && task.dueDate == null && !task.isMaybe
        return add.copy(task = task.copy(parentId = task.parentId ?: service.defaultFolderId(), isStarred = task.isStarred || bare))
    }

    // The new task alone (null for items added to a checklist).
    fun parseAdd(content: String): Task? = planAdd(content)?.task

    // Replying to another todo's `--` message with a new todo makes the replied-to task wait for
    // the new one ("-- hang mirror" <- reply "-- move mirror upstairs").
    fun onAdd(messageId: Long, jumpUrl: String, content: String, replyToMessageId: Long? = null): Boolean = fromDiscord {
        replyToMessageId?.let(::link)?.nudgeFor?.let { return breakUp(it, content) }
        val add = planAdd(content) ?: return false
        // Items into a checklist: nothing to link, since the message isn't one task.
        if (add.task == null) return service.add(add, defaultParent = null) != null
        val task = service.add(add, defaultParent = null)!!
        saveLink(messageId, MessageLink(taskId = task.id, text = content))
        store.setValue("src:${task.id}", jumpUrl)
        if (content.startsWith(DONE)) service.completeWithDescendants(task.id)
        else replyToMessageId?.let(::link)?.taskId?.let { waiting -> service.addDependency(waiting, task.id) }
        return true
    }

    // Only fields whose parse changed are written, so a typo fix doesn't clobber a star or due
    // date set in the app since.
    fun onEdit(messageId: Long, content: String) = fromDiscord {
        val link = link(messageId) ?: return
        val taskId = link.taskId ?: return
        val old = parseAdd(link.text.orEmpty()) ?: return
        val new = parseAdd(content) ?: return
        service.update(taskId) {
            it.copy(
                title = if (old.title != new.title) new.title else it.title,
                startDate = if (old.startDate != new.startDate) new.startDate else it.startDate,
                dueDate = if (old.dueDate != new.dueDate) new.dueDate else it.dueDate,
                parentId = if (old.parentId != new.parentId) new.parentId else it.parentId,
                notes = if (old.notes != new.notes) new.notes else it.notes
            )
        }
        saveLink(messageId, link.copy(text = content))
    }

    fun onDelete(messageId: Long) = fromDiscord {
        link(messageId)?.taskId?.let(service::delete)
        store.setValue("msg:$messageId", null)
    }

    // Mirrors a completion/deletion made in the app or on the web onto the task's `--` message: ✅/❌
    // appear when it's completed/deleted and disappear when undone.
    fun sourceReactions(before: SyncRow?, after: SyncRow): List<SourceReaction> {
        if (after.table != TASKS || before == null || handlingDiscord.get()) return emptyList()
        val url = store.getValue("src:${after.id}") ?: return emptyList()
        val (channelId, messageId) = url.split('/').takeLast(2).map { it.toLongOrNull() ?: return emptyList() }
        return listOfNotNull(
            (after.toTask().isComplete).takeIf { it != before.toTask().isComplete }?.let { SourceReaction(channelId, messageId, DONE, it) },
            after.isDeleted.takeIf { it != before.isDeleted }?.let { SourceReaction(channelId, messageId, DELETE, it) }
        )
    }

    // Replying to a nudge with `--` lines turns each into a subtask of the stuck task. The task now
    // waits on them, so the first step is starred to take its place in Doing; the rest aren't, so
    // breaking a task up doesn't flood Doing.
    private fun breakUp(taskId: Long, content: String): Boolean {
        val steps = content.lines().map { it.trim() }.filter { it.startsWith("--") }
            .mapIndexedNotNull { i, line -> service.addTyped(line.removePrefix("--").trim(), under = taskId, star = i == 0) }
        return steps.isNotEmpty()
    }

    // Run with each morning digest. A task counts as entering Doing at the first digest that sees it
    // there (leaving resets it); it's nudged once it's been there 3 days, then every 3 days after.
    // Contexts don't count as leaving: the digest's 8am isn't work hours, or home. Days are digest to
    // digest, and a digest can fire a few ms earlier than the last, hence the slack.
    fun dueNudges(): List<Pair<Task, Int>> = store.transaction {
        val now = service.now()
        val doing = service.doingIgnoringContexts().associateBy { it.id }
        val since = store.valuesWithPrefix("doingSince:").mapKeys { it.key.removePrefix("doingSince:").toLong() }
        since.keys.filter { it !in doing }.forEach {
            store.setValue("doingSince:$it", null)
            store.setValue("nudged:$it", null)
        }
        doing.values.mapNotNull { task ->
            val entered = since[task.id]?.toLong() ?: now.also { store.setValue("doingSince:${task.id}", it.toString()) }
            val lastNudge = store.getValue("nudged:${task.id}")?.toLong() ?: entered
            if (now - lastNudge < NUDGE_AFTER - NUDGE_SLACK) return@mapNotNull null
            store.setValue("nudged:${task.id}", now.toString())
            task to ((now - entered + NUDGE_SLACK) / DAY_MS).toInt()
        }
    }

    fun nudgeText(task: Task, days: Int) =
        "“${task.title}” has been in Doing for $days days. React $MOVE_OUT to move it out, or reply with `-- step` lines to break it up."

    fun recordNudge(messageId: Long, taskId: Long) = saveLink(messageId, MessageLink(nudgeFor = taskId))

    fun onReaction(messageId: Long, emoji: String, added: Boolean): ReactionOutcome = fromDiscord {
        val link = link(messageId)
        link?.nudgeFor?.let { stuck ->
            if (emoji == MOVE_OUT) service.setStarred(stuck, !added) // un-reacting puts it back
            return ReactionOutcome.None
        }
        val sourceTask = link?.taskId
        if (sourceTask != null) {
            when (emoji) {
                // No room for the subtask question here: ✅ completes everything under it, like the widget.
                DONE -> if (added) service.completeWithDescendants(sourceTask) else service.uncomplete(sourceTask)
                DELETE -> if (added) service.delete(sourceTask) else service.restore(sourceTask)
                STAR -> service.setStarred(sourceTask, added)
            }
            return ReactionOutcome.None
        }
        val line = link?.lines?.firstOrNull { it.emoji == emoji }
        if (line != null) {
            if (added) service.completeWithDescendants(line.taskId) else service.uncomplete(line.taskId)
            // The task's newest list is refreshed through listToRefresh; an older one reacted on isn't.
            return if (newestList(line.taskId)?.second == messageId) ReactionOutcome.None else ReactionOutcome.EditList(render(link.lines))
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

    // Also remembers it as the newest list each of its tasks appears in.
    fun recordList(channelId: Long, messageId: Long, chunk: ListChunk) {
        if (chunk.lines.isEmpty()) return
        saveLink(messageId, MessageLink(lines = chunk.lines))
        chunk.lines.forEach { store.setValue("list:${it.taskId}", "$channelId/$messageId") }
    }

    // A task completed, deleted or undone from anywhere (app, web, Discord): the newest list showing
    // it, as (channel id, message id), needs re-rendering to strike or unstrike its line.
    fun listToRefresh(before: SyncRow?, after: SyncRow): Pair<Long, Long>? {
        if (after.table != TASKS || before == null) return null
        if (before.toTask().isComplete == after.toTask().isComplete && before.isDeleted == after.isDeleted) return null
        return newestList(after.id)
    }

    // Completed or deleted in the app or on the web: the newest list showing the task drops the bot's
    // reaction for its emoji, so only open tasks can be tapped. The struck line keeps the emoji. Undoing
    // puts the reaction back. (Done from Discord, the user's own tap already shows it.)
    fun listReactions(before: SyncRow?, after: SyncRow): List<SourceReaction> {
        if (after.table != TASKS || before == null || handlingDiscord.get()) return emptyList()
        val done = after.isDeleted || after.toTask().isComplete
        if (done == (before.isDeleted || before.toTask().isComplete)) return emptyList()
        val (channelId, messageId) = newestList(after.id) ?: return emptyList()
        val emoji = link(messageId)?.lines?.firstOrNull { it.taskId == after.id }?.emoji ?: return emptyList()
        return listOf(SourceReaction(channelId, messageId, emoji, add = !done))
    }

    fun renderList(messageId: Long): String? = link(messageId)?.lines?.takeIf { it.isNotEmpty() }?.let(::render)

    private fun newestList(taskId: Long): Pair<Long, Long>? =
        store.getValue("list:$taskId")?.split('/')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    // `.doing`, `.list`, `.active`, `.rand` with an optional folder name (folder mode's by default). Returns null for
    // non-commands, or a plain reply for errors.
    fun command(content: String): Result<List<Task>>? {
        val name = content.substringBefore(' ').lowercase()
        if (name !in setOf(".doing", ".list", ".active", ".rand")) return null
        service.purgeExpired()
        val arg = content.substringAfter(' ', "").trim()
        // `.list groceries`: a checklist's open items, ticked off with their emoji like any list.
        if (name == ".list" && arg.isNotEmpty() && service.findFolder(arg) == null) service.findChecklist(arg)?.let { list ->
            return Result.success(checklistItems(list.id, service.tasks()).filterNot { it.isComplete })
        }
        // No folder named: the mode's, if there is one.
        val folder = if (arg.isEmpty()) service.modeFolder() else service.findFolder(arg)
            ?: return Result.failure(IllegalArgumentException("No folder${if (name == ".list") " or checklist" else ""} named \"$arg\"."))
        val tasks = when (name) {
            ".doing" -> service.doing(folder?.id)
            ".list" -> if (arg.isEmpty() || folder == null) service.doing() else service.openInFolder(folder.id)
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
        val title = task.title.take(120) + (if (task.isMaybe) " ?" else "") + (if (!task.notes.isNullOrBlank()) " 📝" else "")
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

    // Set while handling a Discord event, whose changes already show in Discord and so aren't
    // mirrored back. Thread-local because web requests change tasks concurrently.
    private val handlingDiscord = ThreadLocal.withInitial { false }

    private inline fun <T> fromDiscord(block: () -> T): T {
        handlingDiscord.set(true)
        try {
            return block()
        } finally {
            handlingDiscord.set(false)
        }
    }

    private fun link(messageId: Long): MessageLink? =
        store.getValue("msg:$messageId")?.let { SyncJson.decodeFromString<MessageLink>(it) }

    private fun saveLink(messageId: Long, link: MessageLink) =
        store.setValue("msg:$messageId", SyncJson.encodeToString(link))
}
