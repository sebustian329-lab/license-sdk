package dev.licensesystem.engine.license

import dev.licensesystem.engine.config.LicenseDefaults
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LicenseServiceTest {
    @Test
    fun `create and validate binds first server`() {
        val service = createService()
        val license = service.createLicense(
            CreateLicenseCommand(
                productId = "test-plugin",
                owner = "seba",
                durationDays = 30,
                maxServers = 1
            )
        )

        val result = service.validate(
            PublicValidationCommand(
                licenseKey = license.key,
                productId = "test-plugin",
                serverFingerprint = "server-a",
                serverName = "paper-1",
                pluginVersion = "1.0.0",
                minecraftVersion = "1.21.1"
            )
        )

        assertTrue(result.valid)
        val updated = assertNotNull(service.get(license.key))
        assertEquals(1, updated.activations.size)
        assertEquals("server-a", updated.activations.first().fingerprint)
    }

    @Test
    fun `validation rejects second server when max server limit is reached`() {
        val service = createService()
        val license = service.createLicense(
            CreateLicenseCommand(
                productId = "test-plugin",
                owner = "seba",
                durationDays = 30,
                maxServers = 1
            )
        )

        service.validate(
            PublicValidationCommand(
                licenseKey = license.key,
                productId = "test-plugin",
                serverFingerprint = "server-a",
                serverName = "paper-1",
                pluginVersion = "1.0.0",
                minecraftVersion = "1.21.1"
            )
        )

        val secondAttempt = service.validate(
            PublicValidationCommand(
                licenseKey = license.key,
                productId = "test-plugin",
                serverFingerprint = "server-b",
                serverName = "paper-2",
                pluginVersion = "1.0.1",
                minecraftVersion = "1.21.1"
            )
        )

        assertFalse(secondAttempt.valid)
        assertEquals("Osiagnieto limit aktywacji dla tej licencji.", secondAttempt.message)
    }

    @Test
    fun `revoked license is rejected`() {
        val service = createService()
        val license = service.createLicense(
            CreateLicenseCommand(
                productId = "test-plugin",
                owner = "seba",
                durationDays = 30,
                maxServers = 1
            )
        )

        service.revoke(license.key)

        val result = service.validate(
            PublicValidationCommand(
                licenseKey = license.key,
                productId = "test-plugin",
                serverFingerprint = "server-a",
                serverName = "paper-1",
                pluginVersion = "1.0.0",
                minecraftVersion = "1.21.1"
            )
        )

        assertFalse(result.valid)
        assertEquals("Licencja zostala cofnieta.", result.message)
    }

    private fun createService(): LicenseService {
        val repository = LicenseRepository(
            jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            username = "sa",
            password = ""
        )
        return LicenseService(
            repository,
            LicenseDefaults(defaultDurationDays = 30, defaultMaxServers = 1)
        )
    }
}
