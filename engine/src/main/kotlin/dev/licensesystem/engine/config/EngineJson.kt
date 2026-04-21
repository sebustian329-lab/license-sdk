package dev.licensesystem.engine.config

import kotlinx.serialization.json.Json

object EngineJson {
    val pretty: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    val compact: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
