package dev.licensesystem.engine.command

import dev.licensesystem.engine.config.ConfigService
import dev.licensesystem.engine.config.DiscordConfig
import dev.licensesystem.engine.license.CreateLicenseCommand
import dev.licensesystem.engine.license.LicenseRecord
import dev.licensesystem.engine.license.LicenseService
import dev.licensesystem.engine.product.ProductIntegrationInfo
import dev.licensesystem.engine.product.ProductService
import dev.licensesystem.engine.util.SecretGenerator

class LicenseCommandProcessor(
    private val licenseService: LicenseService,
    private val productService: ProductService,
    private val configService: ConfigService,
    private val restartDiscord: () -> String
) {
    fun execute(rawInput: String): String {
        val args = rawInput.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (args.isEmpty()) {
            return help()
        }

        return when (args.first().lowercase()) {
            "help", "pomoc" -> help()
            "status", "stan" -> status()
            "product", "produkt" -> handleProduct(args)
            "products", "produkty" -> handleProducts()
            "create", "generate", "utworz", "stworz", "generuj" -> handleCreate(args)
            "revoke", "cofnij" -> handleSingleRecord(args) { key -> licenseService.revoke(key) }
            "restore", "przywroc" -> handleSingleRecord(args) { key -> licenseService.restore(key) }
            "extend", "przedluz" -> handleExtend(args)
            "show", "pokaz" -> handleSingleRecord(args) { key -> licenseService.get(key) }
            "list", "lista" -> handleList(args)
            "config" -> handleConfig(args)
            "ustaw" -> handleSet(args.drop(1))
            "klucz" -> handleKeys(args)
            "discord", "bot" -> handleDiscord(args)
            else -> "Nieznana komenda. Uzyj: pomoc"
        }
    }

    private fun handleProduct(args: List<String>): String {
        if (args.size < 2) {
            return "Uzycie: produkt <utworz|pokaz> <productKey>"
        }

        return when (args[1].lowercase()) {
            "utworz", "create" -> {
                if (args.size < 3) {
                    return "Uzycie: produkt utworz <productKey>"
                }
                formatIntegrationInfo(productService.createProduct(args[2]))
            }

            "pokaz", "show" -> {
                if (args.size < 3) {
                    return "Uzycie: produkt pokaz <productKey>"
                }
                val info = productService.getProduct(args[2]) ?: return "Nie znaleziono produktu."
                formatIntegrationInfo(info)
            }

            else -> "Uzycie: produkt <utworz|pokaz> <productKey>"
        }
    }

    private fun handleProducts(): String {
        val products = productService.listProducts()
        if (products.isEmpty()) {
            return "Brak produktow."
        }

        return products.joinToString(separator = "\n") {
            "${it.productKey} | utworzono=${it.createdAt}"
        }
    }

    private fun handleCreate(args: List<String>): String {
        if (args.size < 3) {
            return "Uzycie: utworz <productKey> <owner> [dni|0=na_zawsze] [maxServers]"
        }

        val productKey = args[1]
        productService.getProduct(productKey)
            ?: return "Nie znaleziono produktu. Najpierw uzyj: produkt utworz $productKey"

        val durationDays = args.getOrNull(3)?.toIntOrNull()
        val maxServers = args.getOrNull(4)?.toIntOrNull()
        val record = licenseService.createLicense(
            CreateLicenseCommand(
                productId = productKey,
                owner = args[2],
                durationDays = durationDays,
                maxServers = maxServers
            )
        )
        return formatRecord(record)
    }

    private fun handleExtend(args: List<String>): String {
        if (args.size < 3) {
            return "Uzycie: przedluz <licenseKey> <dni>"
        }

        val days = args[2].toIntOrNull() ?: return "Liczba dni musi byc dodatnia liczba."
        val record = licenseService.extend(args[1], days)
            ?: return "Nie znaleziono licencji albo liczba dni jest niepoprawna."
        return formatRecord(record)
    }

    private fun handleSingleRecord(args: List<String>, action: (String) -> LicenseRecord?): String {
        if (args.size < 2) {
            return "Brakuje klucza licencji."
        }

        val record = action(args[1]) ?: return "Nie znaleziono licencji."
        return formatRecord(record)
    }

    private fun handleList(args: List<String>): String {
        val productKey = args.getOrNull(1)
        val records = licenseService.list(productKey)
        if (records.isEmpty()) {
            return "Brak licencji."
        }

        return records.take(20).joinToString(separator = "\n") {
            "${it.key} | ${it.productId} | ${it.owner} | ${it.status} | wygasa=${it.expiresAt ?: "bezterminowa"} | aktywacje=${it.activations.size}/${it.maxServers}"
        }
    }

    private fun handleConfig(args: List<String>): String {
        if (args.size < 2) {
            return "Uzycie: config <pokaz|ustaw|przeladuj>"
        }

        return when (args[1].lowercase()) {
            "pokaz", "show" -> configSnapshot()
            "ustaw", "set" -> handleSet(args.drop(2))
            "przeladuj", "reload" -> {
                configService.reload()
                "Config zostal przeladowany z pliku."
            }

            else -> "Uzycie: config <pokaz|ustaw|przeladuj>"
        }
    }

    private fun handleSet(args: List<String>): String {
        if (args.size < 2) {
            return settingHelp()
        }

        val field = args[0].lowercase()
        val value = args.drop(1).joinToString(" ").trim()
        if (value.isEmpty()) {
            return "Brakuje wartosci do ustawienia."
        }

        val result = when (field) {
            "api.host", "server.host" -> {
                configService.update { it.copy(server = it.server.copy(host = value)) }
                SettingChange("Ustawiono api.host=$value", restartRequired = true)
            }

            "api.port", "server.port" -> {
                val port = value.toIntOrNull() ?: return "Port musi byc liczba."
                configService.update { it.copy(server = it.server.copy(port = port)) }
                SettingChange("Ustawiono api.port=$port", restartRequired = true)
            }

            "api.public-url", "server.publicbaseurl" -> {
                configService.update { it.copy(server = it.server.copy(publicBaseUrl = value)) }
                SettingChange("Ustawiono api.public-url=$value")
            }

            "domyslne.dni", "defaults.days" -> {
                val days = value.toIntOrNull() ?: return "Liczba dni musi byc liczba."
                configService.update { it.copy(defaults = it.defaults.copy(defaultDurationDays = days)) }
                SettingChange("Ustawiono domyslne.dni=$days")
            }

            "domyslne.max-serwerow", "defaults.max-servers" -> {
                val maxServers = value.toIntOrNull() ?: return "Max serwerow musi byc liczba."
                configService.update { it.copy(defaults = it.defaults.copy(defaultMaxServers = maxServers)) }
                SettingChange("Ustawiono domyslne.max-serwerow=$maxServers")
            }

            "discord.enabled", "discord.wlaczony" -> {
                val enabled = parseBoolean(value) ?: return "Wartosc musi byc true/false albo wlacz/wylacz."
                configService.update { it.copy(discord = it.discord.copy(enabled = enabled)) }
                SettingChange("Ustawiono discord.enabled=$enabled", discordRestartRecommended = true)
            }

            "discord.prefix" -> {
                configService.update { it.copy(discord = it.discord.copy(commandPrefix = value)) }
                SettingChange("Ustawiono discord.prefix=$value")
            }

            "discord.token" -> {
                configService.update { it.copy(discord = it.discord.copy(token = value)) }
                SettingChange("Ustawiono nowy token Discord.", discordRestartRecommended = true)
            }

            "discord.guild-id" -> {
                val guildId = value.toLongOrNull() ?: return "guild-id musi byc liczba."
                configService.update { it.copy(discord = it.discord.copy(guildId = guildId)) }
                SettingChange("Ustawiono discord.guild-id=$guildId")
            }

            "discord.user-ids" -> {
                val ids = parseLongList(value) ?: return "Podaj liste ID oddzielona przecinkami albo slowo clear."
                configService.update { it.copy(discord = it.discord.copy(allowedUserIds = ids)) }
                SettingChange("Ustawiono discord.user-ids=${ids.joinToString(",")}")
            }

            "discord.role-ids" -> {
                val ids = parseLongList(value) ?: return "Podaj liste ID oddzielona przecinkami albo slowo clear."
                configService.update { it.copy(discord = it.discord.copy(allowedRoleIds = ids)) }
                SettingChange("Ustawiono discord.role-ids=${ids.joinToString(",")}")
            }

            "baza.path", "database.path" -> {
                configService.update { it.copy(database = it.database.copy(path = value)) }
                SettingChange("Ustawiono baza.path=$value", restartRequired = true)
            }

            "baza.username", "database.username" -> {
                configService.update { it.copy(database = it.database.copy(username = value)) }
                SettingChange("Ustawiono baza.username=$value", restartRequired = true)
            }

            "baza.password", "database.password" -> {
                configService.update { it.copy(database = it.database.copy(password = value)) }
                SettingChange("Ustawiono nowe haslo bazy.", restartRequired = true)
            }

            "security.panel-password", "panel.haslo" -> {
                configService.update { it.copy(security = it.security.copy(managementPanelPassword = value)) }
                SettingChange("Ustawiono nowe haslo panelu WWW.")
            }

            else -> return settingHelp()
        }

        return buildString {
            appendLine(result.message)
            if (result.discordRestartRecommended) {
                appendLine("Aby zastosowac zmiane dla bota Discord, uzyj: discord restart")
            }
            if (result.restartRequired) {
                append("Ta zmiana wymaga restartu calego silnika.")
            }
        }.trim()
    }

    private fun handleKeys(args: List<String>): String {
        if (args.size < 3 || args[2].lowercase() !in setOf("regeneruj", "generate", "reset")) {
            return "Uzycie: klucz <management|public> regeneruj"
        }

        return when (args[1].lowercase()) {
            "management" -> {
                val newKey = SecretGenerator.generateToken(36)
                configService.update { current ->
                    current.copy(security = current.security.copy(managementApiKey = newKey))
                }
                "Wygenerowano nowy management api key:\n$newKey"
            }

            "public", "publiczny" -> {
                val newKey = SecretGenerator.generateToken(36)
                configService.update { current ->
                    current.copy(security = current.security.copy(publicValidationToken = newKey))
                }
                "Wygenerowano nowy legacy public validation token:\n$newKey"
            }

            else -> "Uzycie: klucz <management|public> regeneruj"
        }
    }

    private fun handleDiscord(args: List<String>): String {
        if (args.size < 2) {
            return "Uzycie: discord <pokaz|restart>"
        }

        return when (args[1].lowercase()) {
            "pokaz", "show" -> discordSnapshot(configService.current().discord)
            "restart", "reload", "reconnect" -> restartDiscord()
            else -> "Uzycie: discord <pokaz|restart>"
        }
    }

    private fun status(): String {
        val config = configService.current()
        val productCount = productService.listProducts().size
        val licenses = licenseService.list().size
        return buildString {
            appendLine("Silnik licencji: ONLINE")
            appendLine("Produkty: $productCount")
            appendLine("Licencje w bazie: $licenses")
            appendLine("API: http://${config.server.host}:${config.server.port}/api/v1/manage")
            appendLine("Public URL backendu: ${config.server.publicBaseUrl}")
            appendLine("Domyslne dni: ${config.defaults.defaultDurationDays}")
            appendLine("Domyslne max serwerow: ${config.defaults.defaultMaxServers}")
            appendLine("Discord wlaczony: ${config.discord.enabled}")
            append("Discord prefix: ${config.discord.commandPrefix}")
        }
    }

    private fun configSnapshot(): String {
        val config = configService.current()
        return buildString {
            appendLine("api.host=${config.server.host}")
            appendLine("api.port=${config.server.port}")
            appendLine("api.public-url=${config.server.publicBaseUrl}")
            appendLine("domyslne.dni=${config.defaults.defaultDurationDays}")
            appendLine("domyslne.max-serwerow=${config.defaults.defaultMaxServers}")
            appendLine("discord.enabled=${config.discord.enabled}")
            appendLine("discord.prefix=${config.discord.commandPrefix}")
            appendLine("discord.guild-id=${config.discord.guildId}")
            appendLine("discord.user-ids=${config.discord.allowedUserIds.joinToString(",")}")
            appendLine("discord.role-ids=${config.discord.allowedRoleIds.joinToString(",")}")
            appendLine("baza.path=${config.database.path}")
            append("baza.username=${config.database.username}")
        }
    }

    private fun discordSnapshot(discord: DiscordConfig): String {
        return buildString {
            appendLine("discord.enabled=${discord.enabled}")
            appendLine("discord.prefix=${discord.commandPrefix}")
            appendLine("discord.guild-id=${discord.guildId}")
            appendLine("discord.user-ids=${discord.allowedUserIds.joinToString(",")}")
            append("discord.role-ids=${discord.allowedRoleIds.joinToString(",")}")
        }
    }

    private fun formatRecord(record: LicenseRecord): String {
        return buildString {
            appendLine("klucz=${record.key}")
            appendLine("productKey=${record.productId}")
            appendLine("wlasciciel=${record.owner}")
            appendLine("status=${record.status}")
            appendLine("utworzono=${record.createdAt}")
            appendLine("wygasa=${record.expiresAt ?: "bezterminowa"}")
            appendLine("maxSerwerow=${record.maxServers}")
            append("aktywacje=${record.activations.size}")
        }
    }

    private fun formatIntegrationInfo(info: ProductIntegrationInfo): String {
        return buildString {
            appendLine("productKey=${info.productKey}")
            append("publicKey=${info.publicKey}")
        }
    }

    private fun settingHelp(): String {
        return """
            Uzycie: ustaw <pole> <wartosc>
            Dostepne pola:
            api.host
            api.port
            api.public-url
            domyslne.dni
            domyslne.max-serwerow
            discord.enabled
            discord.prefix
            discord.token
            discord.guild-id
            discord.user-ids
            discord.role-ids
            baza.path
            baza.username
            baza.password
            security.panel-password
        """.trimIndent()
    }

    private fun help(): String {
        return """
            pomoc
            stan
            produkt utworz <productKey>
            produkt pokaz <productKey>
            produkty
            utworz <productKey> <owner> [dni|0=na_zawsze] [maxServers]
            cofnij <licenseKey>
            przywroc <licenseKey>
            przedluz <licenseKey> <dni>
            pokaz <licenseKey>
            lista [productKey]
            config pokaz
            config przeladuj
            ustaw <pole> <wartosc>
            klucz management regeneruj
            discord pokaz
            discord restart
        """.trimIndent()
    }

    private fun parseBoolean(value: String): Boolean? {
        return when (value.lowercase()) {
            "true", "1", "on", "wlacz", "wlaczony", "yes", "tak" -> true
            "false", "0", "off", "wylacz", "wylaczony", "no", "nie" -> false
            else -> null
        }
    }

    private fun parseLongList(value: String): List<Long>? {
        if (value.equals("clear", ignoreCase = true) || value.equals("pusto", ignoreCase = true)) {
            return emptyList()
        }

        return value
            .split(",")
            .map { it.trim() }
            .takeIf { it.all(String::isNotEmpty) }
            ?.map { it.toLongOrNull() ?: return null }
    }
}

private data class SettingChange(
    val message: String,
    val restartRequired: Boolean = false,
    val discordRestartRecommended: Boolean = false
)
