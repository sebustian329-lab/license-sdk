package dev.licensesystem.engine.config

import java.nio.file.Files
import java.nio.file.Path

class ConfigManager(
    private val runtimeDirectory: Path
) {
    private val configPath: Path = runtimeDirectory.resolve("config.json")

    fun load(): LoadedConfig {
        if (Files.notExists(configPath)) {
            Files.createDirectories(configPath.parent)
            val config = EngineConfig()
            save(config)
            return LoadedConfig(config, true, configPath)
        }

        val config = EngineJson.pretty.decodeFromString(EngineConfig.serializer(), Files.readString(configPath))
        return LoadedConfig(config, false, configPath)
    }

    fun save(config: EngineConfig) {
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, EngineJson.pretty.encodeToString(EngineConfig.serializer(), config))
    }
}

data class LoadedConfig(
    val config: EngineConfig,
    val created: Boolean,
    val path: Path
)
