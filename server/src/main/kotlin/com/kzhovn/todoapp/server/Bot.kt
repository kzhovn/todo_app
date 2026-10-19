package com.kzhovn.todoapp.server

import java.util.EnumSet
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.utils.messages.MessageRequest
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import net.dv8tion.jda.api.events.message.react.GenericMessageReactionEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import java.io.File
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Thin JDA adapter: translates Discord events into BotLogic calls (DMs into DevNotes). Everything from anyone but the
// allowlisted users is ignored. JDA runs listeners on one event thread, so BotLogic never sees concurrent
// events; list refreshes only read, from their own thread (Store serialises access).
class Bot private constructor(val logic: BotLogic, private val dev: DevNotes, private val allowedUserIds: Set<Long>) : ListenerAdapter() {

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.idLong !in allowedUserIds) return
        val message = event.message
        // Every DM is a dev note for Claude's backlog worker; tasks and commands come from server channels.
        if (!event.isFromGuild) {
            val id = dev.onDm(event.author.idLong, message.idLong, message.contentRaw.trim(), message.messageReference?.messageIdLong)
            message.attachments.forEachIndexed { i, attachment ->
                // Discord's attachment links expire, so the file is kept. Prefixed: two can share a name.
                val name = "${message.idLong}-$i-" + attachment.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val file = File(File(dev.attachmentsDir, id.toString()).apply { mkdirs() }, name)
                attachment.proxy.downloadToFile(file).thenRun { dev.addAttachment(id, name) }
                    .exceptionally { it.printStackTrace(); null }
            }
            return
        }
        val content = message.contentRaw.trim()
        if (logic.onAdd(message.idLong, message.jumpUrl, content, message.messageReference?.messageIdLong)) return
        if (content.equals(".help", ignoreCase = true)) return event.channel.sendMessage(HELP).queue()
        val result = logic.command(content) ?: return
        result.fold(
            onSuccess = { postList(event.channel, it) },
            onFailure = { event.channel.sendMessage(it.message.orEmpty()).queue() }
        )
    }

    override fun onMessageUpdate(event: MessageUpdateEvent) {
        if (event.author.idLong !in allowedUserIds) return
        if (event.isFromGuild) logic.onEdit(event.messageIdLong, event.message.contentRaw.trim())
        else dev.onEdit(event.messageIdLong, event.message.contentRaw.trim())
    }

    override fun onMessageDelete(event: MessageDeleteEvent) =
        if (event.isFromGuild) logic.onDelete(event.messageIdLong) else dev.onDelete(event.messageIdLong)

    override fun onMessageReactionAdd(event: MessageReactionAddEvent) = react(event, added = true)

    override fun onMessageReactionRemove(event: MessageReactionRemoveEvent) = react(event, added = false)

    private fun react(event: GenericMessageReactionEvent, added: Boolean) {
        if (event.userIdLong !in allowedUserIds) return
        if (!event.isFromGuild) return dev.onReaction(event.messageIdLong, event.emoji.name, added)
        when (val outcome = logic.onReaction(event.messageIdLong, event.emoji.name, added)) {
            is ReactionOutcome.EditList ->
                event.channel.editMessageById(event.messageIdLong, outcome.content).setSuppressEmbeds(true).queue()
            ReactionOutcome.DeleteIfBotMessage -> event.retrieveMessage().queue { message ->
                if (message.author.idLong == event.jda.selfUser.idLong) message.delete().queue()
            }
            is ReactionOutcome.Ask -> event.retrieveMessage().queue { message ->
                message.reply(outcome.text).queue { sent ->
                    logic.recordAsk(sent.idLong, outcome.taskId)
                    listOf(DONE, MOVE_OUT).forEach { sent.addReaction(Emoji.fromUnicode(it)).queue() }
                }
            }
            ReactionOutcome.None -> Unit
        }
    }

    private fun postList(channel: MessageChannel, tasks: List<com.kzhovn.todoapp.data.Task>) {
        logic.listChunks(tasks).forEach { chunk ->
            channel.sendMessage(chunk.content).setSuppressEmbeds(true).queue { sent ->
                logic.recordList(channel.idLong, sent.idLong, chunk)
                chunk.emojis.forEach { sent.addReaction(Emoji.fromUnicode(it)).queue() }
            }
        }
    }

    // Batched and slightly delayed, so a sync completing several tasks edits each list once, after
    // the change has committed.
    private val refresher = Executors.newSingleThreadScheduledExecutor()
    private val pendingRefresh = ConcurrentHashMap.newKeySet<Pair<Long, Long>>()

    private fun refreshSoon(jda: JDA, list: Pair<Long, Long>) {
        if (!pendingRefresh.add(list)) return
        refresher.schedule({
            pendingRefresh.remove(list)
            runCatching {
                val content = logic.renderList(list.second) ?: return@runCatching
                jda.getChannelById(MessageChannel::class.java, list.first)?.editMessageById(list.second, content)?.setSuppressEmbeds(true)?.queue()
            }.onFailure { it.printStackTrace() }
        }, 1, TimeUnit.SECONDS)
    }

    companion object {
        const val EMPTY_DIGEST = "Nothing on the list - add something?"

        fun start(token: String, service: TaskService, store: Store, dev: DevNotes, allowedUserIds: Set<Long>, digestChannelId: Long?, digestTime: String?): JDA {
            val bot = Bot(BotLogic(service, store), dev, allowedUserIds)
            // The bot reposts task titles; one with "@everyone" or a role in it mustn't ping anyone.
            MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType::class.java))
            val jda = JDABuilder.createLight(
                token,
                GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.DIRECT_MESSAGE_REACTIONS
            ).addEventListeners(bot).build()
            // DM channels aren't cached by createLight, so each change opens the user's DM channel.
            dev.discord = object : DevDiscord {
                override fun react(userId: Long, messageId: Long, add: String?, remove: Collection<String>) {
                    jda.retrieveUserById(userId).flatMap { it.openPrivateChannel() }.queue({ channel ->
                        remove.forEach { channel.removeReactionById(messageId, Emoji.fromUnicode(it)).queue(null) { } }
                        add?.let { channel.addReactionById(messageId, Emoji.fromUnicode(it)).queue() }
                    }, Throwable::printStackTrace)
                }

                override fun send(userId: Long, replyTo: Long?, text: String, sent: (Long) -> Unit) {
                    jda.retrieveUserById(userId).flatMap { it.openPrivateChannel() }
                        .flatMap { channel -> channel.sendMessage(text).setSuppressEmbeds(true).apply { replyTo?.let { setMessageReference(it).failOnInvalidReply(false) } } }
                        .queue({ sent(it.idLong) }, Throwable::printStackTrace)
                }
            }
            // ponytail: DM channels aren't cached by createLight, so this only mirrors onto server channels.
            store.onChange = { before, after ->
                (bot.logic.sourceReactions(before, after) + bot.logic.listReactions(before, after)).forEach { r ->
                    jda.getChannelById(MessageChannel::class.java, r.channelId)?.let { channel ->
                        val emoji = Emoji.fromUnicode(r.emoji)
                        (if (r.add) channel.addReactionById(r.messageId, emoji) else channel.removeReactionById(r.messageId, emoji)).queue()
                    }
                }
                bot.logic.listToRefresh(before, after)?.let { bot.refreshSoon(jda, it) }
            }
            // Logged either way, so a missing digest can be traced in the journal (journalctl -u todo).
            if (digestChannelId == null || digestTime == null) println("Digest off: set DIGEST_CHANNEL_ID and DIGEST_TIME to turn it on")
            else {
                println("Digest scheduled daily at $digestTime ${java.util.TimeZone.getDefault().id}")
                scheduleDigest(LocalTime.parse(digestTime)) {
                    service.purgeExpired()
                    val doing = service.doing(null) // the digest covers everything, whatever the mode
                    // DM channels aren't cached by createLight, so a DM's id finds nothing here.
                    val channel = jda.getChannelById(MessageChannel::class.java, digestChannelId)
                    println("Digest: ${doing.size} in Doing, channel ${if (channel == null) "$digestChannelId NOT FOUND (a server channel the bot can see?)" else channel.name}")
                    // Posts even when empty, so a quiet morning looks different from a broken bot. Turned off in
                    // Settings, only the nudges post.
                    if (!service.digestOn()) println("Digest: turned off in Settings")
                    else if (channel != null) {
                        if (doing.isEmpty()) channel.sendMessage(EMPTY_DIGEST).queue() else bot.postList(channel, doing)
                    }
                    // Only with somewhere to post: working them out marks them as sent.
                    if (channel != null) bot.logic.dueNudges().forEach { (task, days) ->
                        channel.sendMessage(bot.logic.nudgeText(task, days)).queue { sent ->
                            bot.logic.recordNudge(sent.idLong, task.id)
                            sent.addReaction(Emoji.fromUnicode(MOVE_OUT)).queue()
                        }
                    }
                }
            }
            return jda
        }

        // Recomputes the delay each day in the default (server-configured) timezone, so DST shifts are handled.
        private fun scheduleDigest(at: LocalTime, post: () -> Unit) {
            val executor = Executors.newSingleThreadScheduledExecutor()
            fun scheduleNext() {
                val now = ZonedDateTime.now()
                var next = now.with(at)
                if (!next.isAfter(now)) next = next.plusDays(1)
                executor.schedule({
                    runCatching(post).onFailure { it.printStackTrace() }
                    scheduleNext()
                }, Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            }
            scheduleNext()
        }
    }
}
