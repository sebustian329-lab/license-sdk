package dev.licensesystem.engine

import dev.licensesystem.engine.api.LicenseApiServer
import dev.licensesystem.engine.command.LicenseCommandProcessor
import dev.licensesystem.engine.config.ConfigManager
import dev.licensesystem.engine.config.ConfigService
import dev.licensesystem.engine.config.EngineConfig
import dev.licensesystem.engine.discord.DiscordBotManager
import dev.licensesystem.engine.license.LicenseRepository
import dev.licensesystem.engine.license.LicenseService
import dev.licensesystem.engine.product.ProductRepository
import dev.licensesystem.engine.product.ProductService
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val runtimeDirectory = Path.of(System.getProperty("user.dir"))
    val configManager = ConfigManager(runtimeDirectory)
    val loadedConfig = configManager.load()
    val configService = ConfigService(configManager, loadedConfig.config)
    val initialConfig = configService.current()

    val databasePath = runtimeDirectory.resolve(initialConfig.database.path).toAbsolutePath().normalize()
    Files.createDirectories(databasePath.parent)

    val repository = LicenseRepository(
        jdbcUrl = buildJdbcUrl(databasePath),
        username = initialConfig.database.username,
        password = initialConfig.database.password
    )
    val productRepository = ProductRepository(
        jdbcUrl = buildJdbcUrl(databasePath),
        username = initialConfig.database.username,
        password = initialConfig.database.password
    )
    val productService = ProductService(productRepository) { configService.current().server.publicBaseUrl }
    val licenseService = LicenseService(repository) { configService.current().defaults }

    lateinit var discordBotManager: DiscordBotManager
    val commandProcessor = LicenseCommandProcessor(
        licenseService = licenseService,
        productService = productService,
        configService = configService,
        restartDiscord = { discordBotManager.reload() }
    )

    val apiServer = LicenseApiServer(configService, licenseService, productService)
    discordBotManager = DiscordBotManager(configService, commandProcessor)
    val consoleManager = ConsoleManager(commandProcessor)

    printStartupSummary(loadedConfig.created, loadedConfig.path, databasePath, initialConfig)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            discordBotManager.stop()
            apiServer.stop()
        }
    )

    consoleManager.start()
    discordBotManager.start()
    apiServer.start(wait = true)
}

private fun printStartupSummary(
    createdDefaultConfig: Boolean,
    configPath: Path,
    databasePath: Path,
    config: EngineConfig
) {
    println("Silnik licencji uruchomiony.")
    println("Konfiguracja: $configPath")
    println("Baza H2: $databasePath.mv.db")
    println("API zarzadzania: http://${config.server.host}:${config.server.port}/api/v1/manage/*")
    println("Publiczny endpoint walidacji: ${config.server.publicBaseUrl.removeSuffix("/")}/api/v1/public/validate")
    if (createdDefaultConfig) {
        println("Utworzono nowy config.json z wygenerowanymi kluczami API i wylaczonym botem Discord.")
    }
    println("Komendy konsoli: pomoc, stan, produkt, produkty, utworz, cofnij, przywroc, przedluz, pokaz, lista, config, ustaw, klucz, discord")
}

private fun buildJdbcUrl(databasePath: Path): String {
    val normalized = databasePath.toString().replace("\\", "/")
    return "jdbc:h2:file:$normalized;AUTO_SERVER=TRUE"
}
