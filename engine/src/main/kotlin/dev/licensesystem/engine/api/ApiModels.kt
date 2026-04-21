package dev.licensesystem.engine.api

import dev.licensesystem.engine.license.LicenseStatus
import kotlinx.serialization.Serializable

@Serializable
data class CreateLicenseHttpRequest(
    val productId: String? = null,
    val productKey: String? = null,
    val owner: String,
    val durationDays: Int? = null,
    val maxServers: Int? = null,
    val notes: String? = null
)

@Serializable
data class CreateProductHttpRequest(
    val productKey: String
)

@Serializable
data class ExtendLicenseHttpRequest(
    val days: Int
)

@Serializable
data class PanelLoginHttpRequest(
    val password: String
)

@Serializable
data class PanelAuthStatusResponse(
    val authenticated: Boolean
)

@Serializable
data class UpdateLicenseHttpRequest(
    val productId: String? = null,
    val productKey: String? = null,
    val owner: String? = null,
    val status: LicenseStatus? = null,
    val expiresAt: String? = null,
    val maxServers: Int? = null,
    val notes: String? = null
)

@Serializable
data class ApiMessage(
    val message: String
)

@Serializable
data class ApiInfoResponse(
    val service: String,
    val managementPath: String,
    val validationPath: String
)

@Serializable
data class DiscordInfoResponse(
    val enabled: Boolean,
    val guildId: Long,
    val commandPrefix: String,
    val allowedUserIds: List<Long>,
    val allowedRoleIds: List<Long>
)

@Serializable
data class ProductInfoResponse(
    val productKey: String,
    val publicKey: String
)
