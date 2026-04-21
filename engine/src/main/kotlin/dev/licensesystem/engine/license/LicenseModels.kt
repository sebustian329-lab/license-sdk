package dev.licensesystem.engine.license

import kotlinx.serialization.Serializable

@Serializable
data class LicenseRecord(
    val key: String,
    val productId: String,
    val owner: String,
    val status: LicenseStatus = LicenseStatus.ACTIVE,
    val createdAt: String,
    val expiresAt: String? = null,
    val maxServers: Int = 1,
    val notes: String? = null,
    val activations: List<ServerActivation> = emptyList(),
    val lastValidatedAt: String? = null,
    val lastPluginVersion: String? = null,
    val lastMinecraftVersion: String? = null
)

@Serializable
enum class LicenseStatus {
    ACTIVE,
    REVOKED
}

@Serializable
data class ServerActivation(
    val fingerprint: String,
    val serverName: String,
    val firstSeenAt: String,
    val lastSeenAt: String
)

data class CreateLicenseCommand(
    val productId: String,
    val owner: String,
    val durationDays: Int? = null,
    val maxServers: Int? = null,
    val notes: String? = null
)

data class UpdateLicenseCommand(
    val productId: String? = null,
    val owner: String? = null,
    val status: LicenseStatus? = null,
    val expiresAt: String? = null,
    val maxServers: Int? = null,
    val notes: String? = null
)

data class PublicValidationCommand(
    val licenseKey: String,
    val productId: String,
    val serverFingerprint: String,
    val serverName: String,
    val pluginVersion: String,
    val minecraftVersion: String
)

data class LicenseValidationResult(
    val valid: Boolean,
    val message: String,
    val record: LicenseRecord? = null
)
