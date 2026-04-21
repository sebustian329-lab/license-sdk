package dev.licensesystem.engine.config

import dev.licensesystem.engine.util.SecretGenerator
import kotlinx.serialization.Serializable

@Serializable
data class EngineConfig(
    val server: ServerConfig = ServerConfig(),
    val security: SecurityConfig = SecurityConfig(),
    val discord: DiscordConfig = DiscordConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val defaults: LicenseDefaults = LicenseDefaults()
)

@Serializable
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val publicBaseUrl: String = "http://127.0.0.1:8080"
)

@Serializable
data class SecurityConfig(
    val managementApiKey: String = SecretGenerator.generateToken(36),
    val publicValidationToken: String = SecretGenerator.generateToken(36),
    val managementPanelPassword: String = "3^PY5r1J2J>X_^!gLcM6-2aR8F"
)

@Serializable
data class DiscordConfig(
    val enabled: Boolean = false,
    val token: String = "PUT_DISCORD_BOT_TOKEN_HERE",
    val guildId: Long = 0,
    val commandPrefix: String = "!license",
    val allowedUserIds: List<Long> = emptyList(),
    val allowedRoleIds: List<Long> = emptyList()
)

@Serializable
data class DatabaseConfig(
    val path: String = "data/license-system",
    val username: String = "sa",
    val password: String = ""
)

@Serializable
data class LicenseDefaults(
    val defaultDurationDays: Int = 30,
    val defaultMaxServers: Int = 1
)
