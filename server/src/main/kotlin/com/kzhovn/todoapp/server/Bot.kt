package com.kzhovn.todoapp.server

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
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Thin JDA adapter: translates Discord events into BotLogic calls. Everything from anyone but the
// allowlisted users is ignored. JDA runs listeners on one event thread, so BotLogic never sees concurrent
// events; list refreshes only read, from their own thread (Store serialises access).
class Bot private constructor(val logic: BotLogic, private val allowedUserIds: Set<Long>) : ListenerAdapter() {

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.idLong !in allowedUserIds) return
        val message = event.message
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
        if (event.author.idLong in allowedUserIds) logic.onEdit(event.messageIdLong, event.message.contentRaw.trim())
    }

    override fun onMessageDelete(event: MessageDeleteEvent) = logic.onDelete(event.messageIdLong)

    override fun onMessageReactionAdd(event: MessageReactionAddEvent) = react(event, added = true)

    override fun onMessageReactionRemove(event: MessageReactionRemoveEvent) = react(event, added = false)

    private fun react(event: GenericMessageReactionEvent, added: Boolean) {
        if (event.userIdLong !in allowedUserIds) return
        when (val outcome = logic.onReaction(event.messageIdLong, event.emoji.name, added)) {
            is ReactionOutcome.EditList ->
                event.channel.editMessageById(event.messageIdLong, outcome.content).setSuppressEmbeds(true).queue()
            ReactionOutcome.DeleteIfBotMessage -> event.retrieveMessage().queue { message ->
                if (message.author.idLong == event.jda.selfUser.idLong) message.delete().queue()
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
        fun start(token: String, service: TaskService, store: Store, allowedUserIds: Set<Long>, digestChannelId: Long?, digestTime: String?): JDA {
            val bot = Bot(BotLogic(service, store), allowedUserIds)
            val jda = JDABuilder.createLight(
                token,
                GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.DIRECT_MESSAGE_REACTIONS
            ).addEventListeners(bot).build()
            // ponytail: DM channels aren't cached by createLight, so this only mirrors onto server channels.
            store.onChange = { before, after ->
                bot.logic.sourceReactions(before, after).forEach { r ->
                    jda.getChannelById(MessageChannel::class.java, r.channelId)?.let { channel ->
                        val emoji = Emoji.fromUnicode(r.emoji)
                        (if (r.add) channel.addReactionById(r.messageId, emoji) else channel.removeReactionById(r.messageId, emoji)).queue()
                    }
                }
                bot.logic.listToRefresh(before, after)?.let { bot.refreshSoon(jda, it) }
            }
            if (digestChannelId != null && digestTime != null) {
                scheduleDigest(LocalTime.parse(digestTime)) {
                    service.purgeExpired()
                    val doing = service.doing()
                    val channel = jda.getChannelById(MessageChannel::class.java, digestChannelId)
                    if (doing.isNotEmpty() && channel != null) bot.postList(channel, doing)
                    val nudges = bot.logic.dueNudges()
                    if (channel != null) nudges.forEach { (task, days) ->
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
