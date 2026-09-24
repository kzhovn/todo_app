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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Thin JDA adapter: translates Discord events into BotLogic calls. Everything from anyone but the
// owner is ignored. JDA runs listeners on one event thread, so BotLogic never sees concurrent events.
class Bot private constructor(private val logic: BotLogic, private val ownerId: Long) : ListenerAdapter() {

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.idLong != ownerId) return
        val message = event.message
        val content = message.contentRaw.trim()
        if (logic.onAdd(message.idLong, message.jumpUrl, content)) {
            message.addReaction(Emoji.fromUnicode(ADDED)).queue()
            return
        }
        val result = logic.command(content) ?: return
        result.fold(
            onSuccess = { postList(event.channel, it) },
            onFailure = { event.channel.sendMessage(it.message.orEmpty()).queue() }
        )
    }

    override fun onMessageUpdate(event: MessageUpdateEvent) {
        if (event.author.idLong == ownerId) logic.onEdit(event.messageIdLong, event.message.contentRaw.trim())
    }

    override fun onMessageDelete(event: MessageDeleteEvent) = logic.onDelete(event.messageIdLong)

    override fun onMessageReactionAdd(event: MessageReactionAddEvent) = react(event, added = true)

    override fun onMessageReactionRemove(event: MessageReactionRemoveEvent) = react(event, added = false)

    private fun react(event: GenericMessageReactionEvent, added: Boolean) {
        if (event.userIdLong != ownerId) return
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
                logic.recordList(sent.idLong, chunk)
                chunk.emojis.forEach { sent.addReaction(Emoji.fromUnicode(it)).queue() }
            }
        }
    }

    companion object {
        fun start(token: String, service: TaskService, store: Store, ownerId: Long, digestChannelId: Long?, digestTime: String?): JDA {
            val bot = Bot(BotLogic(service, store), ownerId)
            val jda = JDABuilder.createLight(
                token,
                GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.DIRECT_MESSAGE_REACTIONS
            ).addEventListeners(bot).build()
            if (digestChannelId != null && digestTime != null) {
                scheduleDigest(LocalTime.parse(digestTime)) {
                    val doing = service.doing()
                    val channel = jda.getChannelById(MessageChannel::class.java, digestChannelId)
                    if (doing.isNotEmpty() && channel != null) bot.postList(channel, doing)
                }
            }
            return jda
        }

        // Recomputes the delay each day in the default (owner's) timezone, so DST shifts are handled.
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
