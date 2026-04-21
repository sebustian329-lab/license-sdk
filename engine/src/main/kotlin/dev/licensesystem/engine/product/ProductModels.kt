package dev.licensesystem.engine.product

data class ProductRecord(
    val productKey: String,
    val publicToken: String,
    val createdAt: String
)

data class PublicKeyPayload(
    val baseUrl: String,
    val productKey: String,
    val publicToken: String
)

data class ProductIntegrationInfo(
    val productKey: String,
    val publicKey: String
)
