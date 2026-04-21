package dev.licensesystem.engine.discord

import dev.licensesystem.engine.command.LicenseCommandProcessor
import dev.licensesystem.engine.config.ConfigService
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class DiscordBotManager(
    private val configService: ConfigService,
    private val commandProcessor: LicenseCommandProcessor
) {
    private var jda: JDA? = null

    fun start() {
        val config = configService.current().discord
        if (!config.enabled) {
            println("Bot Discord jest wylaczony w configu.")
            return
        }

        if (config.token == "PUT_DISCORD_BOT_TOKEN_HERE" || config.token.isBlank()) {
            println("Bot Discord jest wlaczony, ale brakuje tokenu. Pomijam uruchomienie.")
            return
        }

        jda = JDABuilder.createDefault(config.token)
            .enableIntents(
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT
            )
            .addEventListeners(DiscordCommandListener(configService, commandProcessor))
            .build()
            .also {
                it.awaitReady()
                println("Bot Discord polaczony.")
            }
    }

    fun reload(): String {
        stop()
        start()
        return "Bot Discord zostal przeladowany."
    }

    fun stop() {
        jda?.shutdown()
        jda = null
    }
}

private class DiscordCommandListener(
    private val configService: ConfigService,
    private val commandProcessor: LicenseCommandProcessor
) : ListenerAdapter() {
    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot || !event.isFromGuild) {
            return
        }

        val config = configService.current().discord
        if (config.guildId != 0L && event.guild.idLong != config.guildId) {
            return
        }

        val content = event.message.contentRaw.trim()
        if (!content.startsWith(config.commandPrefix)) {
            return
        }

        if (!hasAccess(event)) {
            event.message.reply("Brak uprawnien do uzycia komend licencyjnych.").queue()
            return
        }

        val payload = content.removePrefix(config.commandPrefix).trim().ifEmpty { "pomoc" }
        val response = commandProcessor.execute(payload)
        event.message.reply(response.toDiscordMessage()).queue()
    }

    private fun hasAccess(event: MessageReceivedEvent): Boolean {
        val member = event.member ?: return false
        val config = configService.current().discord

        if (config.allowedUserIds.isNotEmpty() && member.idLong in config.allowedUserIds) {
            return true
        }

        if (config.allowedRoleIds.isNotEmpty() && member.roles.any { it.idLong in config.allowedRoleIds }) {
            return true
        }

        if (config.allowedUserIds.isEmpty() && config.allowedRoleIds.isEmpty()) {
            return member.hasPermission(Permission.ADMINISTRATOR)
        }

        return false
    }
}

private fun String.toDiscordMessage(): String {
    val trimmed = take(DISCORD_LIMIT)
    return "```text\n$trimmed\n```"
}

private const val DISCORD_LIMIT = Message.MAX_CONTENT_LENGTH - 16
