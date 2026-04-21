package dev.licensesystem.gradle

import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties

internal object LicensePublicKeyDecoder {
    private const val PREFIX = "lspub_"

    fun productKey(publicKey: String): String {
        require(publicKey.startsWith(PREFIX)) { "Nieprawidlowy publicKey. Musi zaczynac sie od lspub_." }

        val decoded = String(
            Base64.getUrlDecoder().decode(publicKey.removePrefix(PREFIX)),
            StandardCharsets.UTF_8
        )

        val properties = Properties()
        properties.load(StringReader(decoded))
        return properties.getProperty("productKey")?.trim().orEmpty().ifBlank {
            error("Brakuje productKey w publicKey.")
        }
    }
}
