package dev.licensesystem.engine.license

import dev.licensesystem.engine.config.LicenseDefaults
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

class LicenseService(
    private val repository: LicenseRepository,
    private val defaultsProvider: () -> LicenseDefaults
) {
    constructor(
        repository: LicenseRepository,
        defaults: LicenseDefaults
    ) : this(repository, { defaults })

    fun createLicense(command: CreateLicenseCommand): LicenseRecord {
        require(command.productId.isNotBlank()) { "Id produktu nie moze byc puste." }
        require(command.owner.isNotBlank()) { "Wlasciciel licencji nie moze byc pusty." }

        val defaults = defaultsProvider()
        val durationDays = command.durationDays ?: defaults.defaultDurationDays
        val maxServers = command.maxServers ?: defaults.defaultMaxServers
        val now = Instant.now()

        val record = LicenseRecord(
            key = nextUniqueKey(),
            productId = command.productId.trim(),
            owner = command.owner.trim(),
            createdAt = now.toString(),
            expiresAt = durationDays.toExpiration(now),
            maxServers = maxServers,
            notes = command.notes?.trim()?.takeIf { it.isNotEmpty() }
        )

        return repository.save(record)
    }

    fun revoke(key: String): LicenseRecord? {
        val record = repository.find(key.trim()) ?: return null
        return repository.save(record.copy(status = LicenseStatus.REVOKED))
    }

    fun restore(key: String): LicenseRecord? {
        val record = repository.find(key.trim()) ?: return null
        return repository.save(record.copy(status = LicenseStatus.ACTIVE))
    }

    fun extend(key: String, days: Int): LicenseRecord? {
        if (days <= 0) {
            return null
        }

        val record = repository.find(key.trim()) ?: return null
        val baseInstant = record.expiresAt
            ?.let(Instant::parse)
            ?.takeIf { it.isAfter(Instant.now()) }
            ?: Instant.now()

        return repository.save(
            record.copy(expiresAt = baseInstant.plus(days.toLong(), ChronoUnit.DAYS).toString())
        )
    }


    fun updateLicense(key: String, command: UpdateLicenseCommand): LicenseRecord? {
        val record = repository.find(key.trim()) ?: return null
        val updated = record.copy(
            productId = command.productId?.trim()?.takeIf { it.isNotEmpty() } ?: record.productId,
            owner = command.owner?.trim()?.takeIf { it.isNotEmpty() } ?: record.owner,
            status = command.status ?: record.status,
            expiresAt = command.expiresAt.toResolvedExpiration(record.expiresAt),
            maxServers = command.maxServers ?: record.maxServers,
            notes = command.notes.toResolvedNotes(record.notes)
        )
        return repository.save(updated)
    }

    fun get(key: String): LicenseRecord? = repository.find(key.trim())

    fun list(productId: String? = null): List<LicenseRecord> = repository.list(productId?.trim())

    fun validate(command: PublicValidationCommand): LicenseValidationResult {
        val record = repository.find(command.licenseKey.trim())
            ?: return LicenseValidationResult(false, "Nie znaleziono licencji.")

        if (!record.productId.equals(command.productId.trim(), ignoreCase = true)) {
            return LicenseValidationResult(false, "Ta licencja nie nalezy do tego produktu.", record)
        }

        if (record.status == LicenseStatus.REVOKED) {
            return LicenseValidationResult(false, "Licencja zostala cofnieta.", record)
        }

        val expiresAt = record.expiresAt?.let(Instant::parse)
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            return LicenseValidationResult(false, "Licencja wygasla.", record)
        }

        val fingerprint = command.serverFingerprint.trim()
        val matchingActivation = record.activations.find { it.fingerprint == fingerprint }

        val updatedActivations = when {
            matchingActivation != null -> {
                record.activations.map {
                    if (it.fingerprint == fingerprint) {
                        it.copy(lastSeenAt = Instant.now().toString(), serverName = command.serverName.trim())
                    } else {
                        it
                    }
                }
            }

            record.maxServers > 0 && record.activations.size >= record.maxServers -> {
                return LicenseValidationResult(false, "Osiagnieto limit aktywacji dla tej licencji.", record)
            }

            else -> {
                record.activations + ServerActivation(
                    fingerprint = fingerprint,
                    serverName = command.serverName.trim(),
                    firstSeenAt = Instant.now().toString(),
                    lastSeenAt = Instant.now().toString()
                )
            }
        }

        val updatedRecord = record.copy(
            activations = updatedActivations,
            lastValidatedAt = Instant.now().toString(),
            lastPluginVersion = command.pluginVersion.trim(),
            lastMinecraftVersion = command.minecraftVersion.trim()
        )

        repository.save(updatedRecord)
        return LicenseValidationResult(true, "Licencja jest poprawna.", updatedRecord)
    }

    private fun nextUniqueKey(): String {
        while (true) {
            val candidate = LicenseKeyGenerator.generate()
            if (repository.find(candidate) == null) {
                return candidate
            }
        }
    }

    private fun Int.toExpiration(now: Instant): String? {
        return if (this <= 0) null else now.plus(this.toLong(), ChronoUnit.DAYS).toString()
    }

    private fun String?.toResolvedExpiration(current: String?): String? {
        return when (this) {
            null -> current
            "" -> null
            else -> {
                try {
                    Instant.parse(this.trim()).toString()
                } catch (_: DateTimeParseException) {
                    throw IllegalArgumentException("expiresAt musi miec format ISO-8601, np. 2026-12-31T23:59:59Z")
                }
            }
        }
    }

    private fun String?.toResolvedNotes(current: String?): String? {
        return when (this) {
            null -> current
            else -> this.trim().takeIf { it.isNotEmpty() }
        }
    }
}
