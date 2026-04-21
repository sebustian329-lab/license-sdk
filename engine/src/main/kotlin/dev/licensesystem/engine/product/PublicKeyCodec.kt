package dev.licensesystem.engine.product

import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties

object PublicKeyCodec {
    private const val prefix = "lspub_"

    fun encode(payload: PublicKeyPayload): String {
        val content = buildString {
            appendLine("version=1")
            appendLine("baseUrl=${payload.baseUrl.trim()}")
            appendLine("productKey=${payload.productKey.trim()}")
            appendLine("publicToken=${payload.publicToken.trim()}")
        }

        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(content.toByteArray(StandardCharsets.UTF_8))

        return prefix + encoded
    }

    fun decode(publicKey: String): PublicKeyPayload? {
        if (!publicKey.startsWith(prefix)) {
            return null
        }

        return try {
            val decoded = String(
                Base64.getUrlDecoder().decode(publicKey.removePrefix(prefix)),
                StandardCharsets.UTF_8
            )
            val properties = Properties()
            properties.load(StringReader(decoded))

            val baseUrl = properties.getProperty("baseUrl")?.trim().orEmpty()
            val productKey = properties.getProperty("productKey")?.trim().orEmpty()
            val publicToken = properties.getProperty("publicToken")?.trim().orEmpty()

            if (baseUrl.isBlank() || productKey.isBlank() || publicToken.isBlank()) {
                null
            } else {
                PublicKeyPayload(
                    baseUrl = baseUrl,
                    productKey = productKey,
                    publicToken = publicToken
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
