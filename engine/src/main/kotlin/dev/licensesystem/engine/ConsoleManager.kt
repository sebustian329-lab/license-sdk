package dev.licensesystem.engine

import dev.licensesystem.engine.command.LicenseCommandProcessor
import kotlin.concurrent.thread

class ConsoleManager(
    private val commandProcessor: LicenseCommandProcessor
) {
    fun start() {
        thread(name = "license-console", isDaemon = true) {
            while (true) {
                val line = readlnOrNull() ?: return@thread
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    continue
                }

                println(commandProcessor.execute(trimmed))
            }
        }
    }
}
