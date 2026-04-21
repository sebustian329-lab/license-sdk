package dev.licensesystem.engine.config

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ConfigService(
    private val configManager: ConfigManager,
    initialConfig: EngineConfig
) {
    private val lock = ReentrantLock()
    private var currentConfig: EngineConfig = initialConfig

    fun current(): EngineConfig = lock.withLock { currentConfig }

    fun reload(): EngineConfig = lock.withLock {
        val reloaded = configManager.load().config
        currentConfig = reloaded
        reloaded
    }

    fun update(transform: (EngineConfig) -> EngineConfig): EngineConfig = lock.withLock {
        val updated = transform(currentConfig)
        configManager.save(updated)
        currentConfig = updated
        updated
    }
}
