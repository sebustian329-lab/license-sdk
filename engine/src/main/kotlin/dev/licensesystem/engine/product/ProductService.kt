package dev.licensesystem.engine.product

import dev.licensesystem.engine.util.SecretGenerator

class ProductService(
    private val repository: ProductRepository,
    private val publicBaseUrlProvider: () -> String
) {
    fun createProduct(productKey: String): ProductIntegrationInfo {
        val normalizedKey = normalizeProductKey(productKey)
        val existing = repository.find(normalizedKey)
        val record = existing ?: repository.create(normalizedKey, SecretGenerator.generateToken(32))
        return toIntegrationInfo(record)
    }

    fun getProduct(productKey: String): ProductIntegrationInfo? {
        val normalizedKey = normalizeProductKey(productKey)
        return repository.find(normalizedKey)?.let(::toIntegrationInfo)
    }

    fun listProducts(): List<ProductRecord> = repository.list()

    fun resolveProduct(publicKey: String, productKey: String): ProductRecord? {
        val payload = PublicKeyCodec.decode(publicKey) ?: return null
        val normalizedKey = normalizeProductKey(productKey)
        if (payload.productKey != normalizedKey) {
            return null
        }

        val product = repository.find(normalizedKey) ?: return null
        return product.takeIf { it.publicToken == payload.publicToken }
    }

    private fun toIntegrationInfo(record: ProductRecord): ProductIntegrationInfo {
        val payload = PublicKeyPayload(
            baseUrl = publicBaseUrlProvider().removeSuffix("/"),
            productKey = record.productKey,
            publicToken = record.publicToken
        )
        return ProductIntegrationInfo(
            productKey = record.productKey,
            publicKey = PublicKeyCodec.encode(payload)
        )
    }

    private fun normalizeProductKey(productKey: String): String {
        val normalized = productKey.trim()
        require(normalized.isNotEmpty()) { "productKey nie moze byc pusty." }

        val decodedPublicKey = PublicKeyCodec.decode(normalized)
        if (decodedPublicKey != null) {
            return decodedPublicKey.productKey
        }

        require(normalized.matches(Regex("[a-zA-Z0-9._-]+"))) {
            "productKey moze zawierac tylko litery, cyfry, kropke, myslnik i podkreslenie."
        }
        return normalized
    }
}
