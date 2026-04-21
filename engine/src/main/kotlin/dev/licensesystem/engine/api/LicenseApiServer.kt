package dev.licensesystem.engine.api

import dev.licensesystem.engine.config.ConfigService
import dev.licensesystem.engine.config.DiscordConfig
import dev.licensesystem.engine.config.EngineJson
import dev.licensesystem.engine.license.CreateLicenseCommand
import dev.licensesystem.engine.license.LicenseService
import dev.licensesystem.engine.license.PublicValidationCommand
import dev.licensesystem.engine.license.UpdateLicenseCommand
import dev.licensesystem.engine.product.ProductService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.BindException
import java.security.MessageDigest

class LicenseApiServer(
    private val configService: ConfigService,
    private val licenseService: LicenseService,
    private val productService: ProductService
) {
    private val engine: ApplicationEngine = embeddedServer(
        Netty,
        host = configService.current().server.host,
        port = configService.current().server.port
    ) {
        configureHttp(configService, licenseService, productService)
    }

    fun start(wait: Boolean) {
        try {
            engine.start(wait = wait)
        } catch (e: Exception) {
            val bind = e as? BindException ?: e.cause as? BindException
            if (bind != null) {
                val host = configService.current().server.host
                System.err.println(
                    "Nie można zbindować $host:${configService.current().server.port} " +
                        "(${bind.message}). " +
                        "Ustaw server.host na 0.0.0.0 (nasłuch na wszystkich interfejsach). " +
                        "Publiczny adres klientów zostaw w server.publicBaseUrl."
                )
            }
            throw e
        }
    }

    fun stop() {
        engine.stop(1_000, 3_000)
    }
}

private fun Application.configureHttp(
    configService: ConfigService,
    licenseService: LicenseService,
    productService: ProductService
) {
    install(ContentNegotiation) {
        json(EngineJson.compact)
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiMessage(cause.message ?: "Nieprawidlowe zadanie"))
        }
        exception<Throwable> { call, cause ->
            cause.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, ApiMessage(cause.message ?: "Wewnetrzny blad serwera"))
        }
    }

    routing {
        get("/") {
            call.respondText(managementConsoleHtml(), ContentType.Text.Html)
        }

        get("/manage") {
            call.respondText(managementConsoleHtml(), ContentType.Text.Html)
        }

        get("/api") {
            call.respond(
                ApiInfoResponse(
                    service = "Silnik LicenseSystem",
                    managementPath = "/api/v1/manage",
                    validationPath = "/api/v1/public/validate"
                )
            )
        }

        route("/api/v1/public") {
            get("/validate") {
                val config = configService.current()
                val licenseKey = call.request.queryParameters["licenseKey"]?.trim().orEmpty()
                val legacyProductId = call.request.queryParameters["productId"]?.trim().orEmpty()
                val productKey = call.request.queryParameters["productKey"]?.trim().orEmpty()
                val publicKey = call.request.queryParameters["publicKey"]?.trim().orEmpty()
                val serverFingerprint = call.request.queryParameters["serverFingerprint"]?.trim().orEmpty()
                val serverName = call.request.queryParameters["serverName"]?.trim().orEmpty()
                val pluginVersion = call.request.queryParameters["pluginVersion"]?.trim().orEmpty()
                val minecraftVersion = call.request.queryParameters["minecraftVersion"]?.trim().orEmpty()

                if (licenseKey.isBlank() || serverFingerprint.isBlank()) {
                    call.respondText(
                        text = encodeValidationPayload(
                            valid = false,
                            message = "Brakuje licenseKey albo serverFingerprint.",
                            productId = productKey.ifBlank { legacyProductId },
                            licenseKey = licenseKey
                        ),
                        contentType = ContentType.Text.Plain,
                        status = HttpStatusCode.BadRequest
                    )
                    return@get
                }

                val resolvedProductKey = when {
                    publicKey.isNotBlank() && productKey.isNotBlank() -> {
                        val product = productService.resolveProduct(publicKey, productKey)
                            ?: run {
                                call.respondText(
                                    text = encodeValidationPayload(
                                        valid = false,
                                        message = "Nieprawidlowy publicKey albo productKey.",
                                        productId = productKey,
                                        licenseKey = licenseKey
                                    ),
                                    contentType = ContentType.Text.Plain,
                                    status = HttpStatusCode.Unauthorized
                                )
                                return@get
                            }
                        product.productKey
                    }

                    legacyProductId.isNotBlank() && call.request.headers["X-License-Token"] == config.security.publicValidationToken -> {
                        legacyProductId
                    }

                    else -> {
                        call.respondText(
                            text = encodeValidationPayload(
                                valid = false,
                                message = "Brakuje poprawnych danych integracyjnych.",
                                productId = productKey.ifBlank { legacyProductId },
                                licenseKey = licenseKey
                            ),
                            contentType = ContentType.Text.Plain,
                            status = HttpStatusCode.Unauthorized
                        )
                        return@get
                    }
                }

                val result = licenseService.validate(
                    PublicValidationCommand(
                        licenseKey = licenseKey,
                        productId = resolvedProductKey,
                        serverFingerprint = serverFingerprint,
                        serverName = serverName.ifBlank { "nieznany-serwer" },
                        pluginVersion = pluginVersion.ifBlank { "nieznana" },
                        minecraftVersion = minecraftVersion.ifBlank { "nieznana" }
                    )
                )

                call.respondText(
                    text = encodeValidationPayload(
                        valid = result.valid,
                        message = result.message,
                        productId = resolvedProductKey,
                        licenseKey = licenseKey,
                        owner = result.record?.owner.orEmpty(),
                        expiresAt = result.record?.expiresAt.orEmpty(),
                        maxServers = result.record?.maxServers?.toString().orEmpty(),
                        activeServers = result.record?.activations?.size?.toString().orEmpty()
                    ),
                    contentType = ContentType.Text.Plain
                )
            }
        }

        route("/api/v1/manage") {
            get("/auth/status") {
                call.respond(PanelAuthStatusResponse(authenticated = call.hasManagementAccess(configService)))
            }

            post("/auth/login") {
                val request = call.receive<PanelLoginHttpRequest>()
                val configuredPassword = configService.current().security.managementPanelPassword
                if (request.password != configuredPassword) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Nieprawidlowe haslo do panelu"))
                    return@post
                }

                call.response.headers.append(HttpHeaders.SetCookie, buildPanelAuthCookie(configuredPassword))
                call.respond(ApiMessage("Zalogowano do panelu"))
            }

            post("/auth/logout") {
                call.response.headers.append(HttpHeaders.SetCookie, expirePanelAuthCookie())
                call.respond(ApiMessage("Wylogowano z panelu"))
            }

            get("/health") {
                if (!call.hasManagementAccess(configService)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Brak autoryzacji panelu albo nieprawidlowy management api key"))
                    return@get
                }

                call.respond(ApiMessage("ok"))
            }

            get("/config/discord") {
                if (!call.hasManagementAccess(configService)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Brak autoryzacji panelu albo nieprawidlowy management api key"))
                    return@get
                }

                call.respond(maskDiscord(configService.current().discord))
            }

            get("/products") {
                if (!call.hasManagementAccess(configService)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Brak autoryzacji panelu albo nieprawidlowy management api key"))
                    return@get
                }

                call.respond(
                    productService.listProducts().map {
                        ProductInfoResponse(
                            productKey = it.productKey,
                            publicKey = productService.getProduct(it.productKey)?.publicKey.orEmpty()
                        )
                    }
                )
            }

            post("/products") {
                if (!call.hasManagementAccess(configService)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Brak autoryzacji panelu albo nieprawidlowy management api key"))
                    return@post
                }

                val request = call.receive<CreateProductHttpRequest>()
                val created = productService.createProduct(request.productKey)
                call.respond(
                    HttpStatusCode.Created,
                    ProductInfoResponse(
                        productKey = created.productKey,
                        publicKey = created.publicKey
                    )
                )
            }

            post("/licenses") {
                if (!call.hasManagementAccess(configService)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Brak autoryzacji panelu albo nieprawidlowy management api key"))
                    return@post
                }

                val request = call.receive<CreateLicenseHttpRequest>()
                val productKey = request.productKey?.trim().orEmpty().ifBlank {
                    request.productId?.trim().orEmpty()
                }
                val created = licenseService.createLicense(
                    CreateLicenseCommand(
                        productId = productKey,
                        owner = request.owner,
                        durationDays = request.durationDays,
                        maxServers = request.maxServers,
                        notes = request.notes
                    )
                )

                call.respond(HttpStatusCode.Created, created)
            }

            get("/licenses") {
                if (!call.hasManagementAccess(configService)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Brak autoryzacji panelu albo nieprawidlowy management api key"))
                    return@get
                }

                val productId = call.request.queryParameters["productId"]?.trim()?.takeIf { it.isNotEmpty() }
                call.respond(licenseService.list(productId))
            }

            get("/licenses/{key}") {
                if (!call.hasManagementAccess(configService)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Brak autoryzacji panelu albo nieprawidlowy management api key"))
                    return@get
                }

                val key = call.parameters["key"].orEmpty()
                val record = licenseService.get(key)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ApiMessage("Nie znaleziono licencji"))
                        return@get
                    }

                call.respond(record)
            }

            post("/licenses/{key}/update") {
                if (!call.hasManagementAccess(configService)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Brak autoryzacji panelu albo nieprawidlowy management api key"))
                    return@post
                }

                val request = call.receive<UpdateLicenseHttpRequest>()
                val key = call.parameters["key"].orEmpty()
                val updated = licenseService.updateLicense(
                    key,
                    UpdateLicenseCommand(
                        productId = request.productKey?.trim().orEmpty().ifBlank {
                            request.productId?.trim().orEmpty()
                        }.takeIf { it.isNotEmpty() },
                        owner = request.owner,
                        status = request.status,
                        expiresAt = request.expiresAt,
                        maxServers = request.maxServers,
                        notes = request.notes
                    )
                ) ?: run {
                    call.respond(HttpStatusCode.NotFound, ApiMessage("Nie znaleziono licencji"))
                    return@post
                }

                call.respond(updated)
            }

            post("/licenses/{key}/revoke") {
                if (!call.hasManagementAccess(configService)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Brak autoryzacji panelu albo nieprawidlowy management api key"))
                    return@post
                }

                val key = call.parameters["key"].orEmpty()
                val record = licenseService.revoke(key)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ApiMessage("Nie znaleziono licencji"))
                        return@post
                    }

                call.respond(record)
            }

            post("/licenses/{key}/restore") {
                if (!call.hasManagementAccess(configService)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Brak autoryzacji panelu albo nieprawidlowy management api key"))
                    return@post
                }

                val key = call.parameters["key"].orEmpty()
                val record = licenseService.restore(key)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ApiMessage("Nie znaleziono licencji"))
                        return@post
                    }

                call.respond(record)
            }

            post("/licenses/{key}/extend") {
                if (!call.hasManagementAccess(configService)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiMessage("Brak autoryzacji panelu albo nieprawidlowy management api key"))
                    return@post
                }

                val request = call.receive<ExtendLicenseHttpRequest>()
                val key = call.parameters["key"].orEmpty()
                val record = licenseService.extend(key, request.days)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ApiMessage("Nie znaleziono licencji albo liczba dni jest niepoprawna"))
                        return@post
                    }

                call.respond(record)
            }
        }
    }
}

private const val panelAuthCookieName = "ls_manage_auth"

private fun io.ktor.server.application.ApplicationCall.hasManagementAccess(configService: ConfigService): Boolean {
    val security = configService.current().security
    val headerAccess = request.headers["X-Api-Key"] == security.managementApiKey
    val cookieAccess = request.cookies[panelAuthCookieName] == panelCookieValue(security.managementPanelPassword)
    return headerAccess || cookieAccess
}

private fun buildPanelAuthCookie(password: String): String {
    val value = panelCookieValue(password)
    return "$panelAuthCookieName=$value; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000"
}

private fun expirePanelAuthCookie(): String {
    return "$panelAuthCookieName=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
}

private fun panelCookieValue(password: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("license-system-panel|$password".toByteArray(Charsets.UTF_8))
    return bytes.joinToString(separator = "") { "%02x".format(it) }
}

private fun maskDiscord(discordConfig: DiscordConfig): DiscordInfoResponse {
    return DiscordInfoResponse(
        enabled = discordConfig.enabled,
        guildId = discordConfig.guildId,
        commandPrefix = discordConfig.commandPrefix,
        allowedUserIds = discordConfig.allowedUserIds,
        allowedRoleIds = discordConfig.allowedRoleIds
    )
}

private fun encodeValidationPayload(
    valid: Boolean,
    message: String,
    productId: String,
    licenseKey: String,
    owner: String = "",
    expiresAt: String = "",
    maxServers: String = "",
    activeServers: String = ""
): String {
    val fields = linkedMapOf(
        "valid" to valid.toString(),
        "message" to message,
        "productId" to productId,
        "licenseKey" to licenseKey,
        "owner" to owner,
        "expiresAt" to expiresAt,
        "maxServers" to maxServers,
        "activeServers" to activeServers
    )

    return fields.entries.joinToString(separator = "\n") { (key, value) ->
        "${escapeProperties(key)}=${escapeProperties(value)}"
    }
}

private fun escapeProperties(value: String): String {
    return buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '=' -> append("\\=")
                ':' -> append("\\:")
                else -> append(character)
            }
        }
    }
}

private fun managementConsoleHtml(): String {
    return """
<!doctype html>
<html lang="pl">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>LicenseSystem Panel</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #0b1020;
      --panel: rgba(17, 24, 39, 0.88);
      --panel-2: rgba(15, 23, 42, 0.95);
      --border: rgba(148, 163, 184, 0.20);
      --text: #e5eefb;
      --muted: #9fb3d9;
      --accent: #60a5fa;
      --accent-2: #22c55e;
      --danger: #ef4444;
      --warn: #f59e0b;
      --shadow: 0 30px 80px rgba(0,0,0,.35);
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background:
        radial-gradient(circle at top left, rgba(96,165,250,.22), transparent 30%),
        radial-gradient(circle at top right, rgba(34,197,94,.16), transparent 25%),
        linear-gradient(180deg, #0b1020 0%, #08101b 100%);
      color: var(--text);
      min-height: 100vh;
    }
    .shell {
      width: min(1400px, calc(100% - 32px));
      margin: 24px auto 40px;
      display: grid;
      gap: 18px;
    }
    .hero, .panel {
      background: var(--panel);
      backdrop-filter: blur(16px);
      border: 1px solid var(--border);
      border-radius: 24px;
      box-shadow: var(--shadow);
    }
    .hero {
      padding: 24px;
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 20px;
      flex-wrap: wrap;
    }
    .hero h1 { margin: 0 0 10px; font-size: 30px; }
    .hero p { margin: 0; color: var(--muted); max-width: 820px; line-height: 1.55; }
    .grid {
      display: grid;
      gap: 18px;
      grid-template-columns: 340px minmax(0, 1fr);
    }
    .panel { padding: 20px; }
    .panel h2, .panel h3 { margin: 0 0 14px; }
    .muted { color: var(--muted); }
    .stack { display: grid; gap: 12px; }
    label { display: grid; gap: 8px; font-size: 14px; color: var(--muted); }
    input, select, textarea {
      width: 100%;
      border-radius: 14px;
      border: 1px solid rgba(148,163,184,.22);
      background: rgba(15,23,42,.75);
      color: var(--text);
      padding: 12px 14px;
      font: inherit;
      outline: none;
    }
    textarea { min-height: 100px; resize: vertical; }
    .copy-group {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 10px;
      align-items: center;
    }
    input:focus, select:focus, textarea:focus { border-color: rgba(96,165,250,.8); box-shadow: 0 0 0 3px rgba(96,165,250,.18); }
    .row { display: grid; gap: 12px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .row-3 { display: grid; gap: 12px; grid-template-columns: repeat(3, minmax(0, 1fr)); }
    button {
      border: 0;
      border-radius: 14px;
      padding: 12px 14px;
      font: inherit;
      font-weight: 700;
      cursor: pointer;
      background: linear-gradient(135deg, #3b82f6, #2563eb);
      color: white;
      transition: transform .15s ease, opacity .15s ease;
    }
    button.secondary { background: rgba(30,41,59,.95); }
    button.success { background: linear-gradient(135deg, #22c55e, #16a34a); }
    button.warn { background: linear-gradient(135deg, #f59e0b, #d97706); }
    button.danger { background: linear-gradient(135deg, #ef4444, #dc2626); }
    button:hover { transform: translateY(-1px); }
    button:disabled { opacity: .55; cursor: wait; transform: none; }
    .actions { display: flex; gap: 10px; flex-wrap: wrap; }
    .chips { display: flex; gap: 10px; flex-wrap: wrap; }
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      border-radius: 999px;
      padding: 8px 12px;
      background: rgba(15,23,42,.9);
      border: 1px solid var(--border);
      color: var(--muted);
      font-size: 13px;
    }
    .table-wrap { overflow: auto; border-radius: 18px; border: 1px solid var(--border); }
    table { width: 100%; border-collapse: collapse; min-width: 1080px; }
    th, td { padding: 14px; text-align: left; border-bottom: 1px solid rgba(148,163,184,.12); vertical-align: top; }
    th { position: sticky; top: 0; background: var(--panel-2); font-size: 13px; color: var(--muted); }
    tr:hover td { background: rgba(59,130,246,.05); }
    .toolbar {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
      margin-bottom: 14px;
      align-items: center;
    }
    .toolbar > * { flex: 1 1 190px; }
    .status {
      padding: 12px 14px;
      border-radius: 14px;
      border: 1px solid var(--border);
      background: rgba(15,23,42,.7);
      color: var(--muted);
      white-space: pre-wrap;
    }
    .status.ok { border-color: rgba(34,197,94,.35); color: #bbf7d0; }
    .status.err { border-color: rgba(239,68,68,.35); color: #fecaca; }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
    .license-form {
      display: grid;
      gap: 12px;
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .license-form .full { grid-column: 1 / -1; }
    .helper { font-size: 12px; color: var(--muted); }
    .small { font-size: 12px; }
    .snippet-grid {
      display: grid;
      gap: 12px;
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
    .snippet-grid-2 {
      display: grid;
      gap: 12px;
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .snippet-box {
      border: 1px solid rgba(148,163,184,.14);
      border-radius: 18px;
      padding: 14px;
      background: rgba(15,23,42,.42);
      display: grid;
      gap: 10px;
    }
    .snippet-box textarea {
      min-height: 150px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 12px;
      line-height: 1.55;
    }
    [hidden] { display: none !important; }
    .login-screen {
      max-width: 520px;
      margin: 0 auto 24px;
    }
    .login-screen .panel {
      backdrop-filter: blur(14px);
    }
    .app-topbar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
      margin-bottom: 18px;
    }
    .app-topbar .status {
      flex: 1 1 320px;
      margin: 0;
    }
    .app-topbar .actions {
      flex: 0 0 auto;
    }

    @media (max-width: 1100px) {
      .grid { grid-template-columns: 1fr; }
      .license-form, .row, .row-3, .snippet-grid, .snippet-grid-2 { grid-template-columns: 1fr; }
    }
  </style>
</head>
<body>
  <div class="shell">
    <section id="heroSection" class="hero">
      <div>
        <div class="chips" style="margin-bottom: 14px;">
          <span class="chip">GUI pod tym samym IP</span>
          <span class="chip">REST API + panel</span>
          <span class="chip">Licencje / produkty</span>
        </div>
        <h1>LicenseSystem Management</h1>
        <p id="heroDescription">Zaloguj się hasłem do panelu, a potem zarządzaj produktami i licencjami z przeglądarki. Panel działa pod <span class="mono">/</span> i <span class="mono">/manage</span>, a API zostaje pod <span class="mono">/api/v1/manage</span> i nadal może używać <span class="mono">X-Api-Key</span>.</p>
      </div>
      <div class="chip">IP / domena: ten sam host i port co backend</div>
    </section>

    <section id="loginScreen" class="login-screen">
      <div class="panel stack">
        <div>
          <h2>Logowanie do panelu</h2>
          <p class="muted">Najpierw zaloguj się hasłem. Dopiero po poprawnej sesji pokaże się cały panel zarządzania licencjami i produktami.</p>
        </div>

        <label>
          Hasło panelu
          <input id="panelPassword" type="password" placeholder="Wpisz hasło do panelu" autocomplete="current-password" />
        </label>

        <div class="actions">
          <button id="loginBtn">Zaloguj</button>
          <button id="healthBtn" class="secondary">Sprawdź API</button>
        </div>

        <div id="statusBox" class="status">Zaloguj się, żeby wejść do panelu.</div>
      </div>
    </section>

    <div id="appScreen" class="grid" hidden>
      <aside class="panel stack">
        <div>
          <h2>Sesja panelu</h2>
          <p class="muted">Jesteś zalogowany do GUI. Integracje REST dalej mogą używać <span class="mono">managementApiKey</span>.</p>
        </div>

        <div class="actions">
          <button id="logoutBtn" class="secondary">Wyloguj</button>
          <button id="healthBtnApp" class="secondary">Sprawdź API</button>
        </div>

        <div id="appStatusBox" class="status ok">Panel aktywny. Możesz zarządzać produktami i licencjami.</div>

        <hr style="border:0;border-top:1px solid rgba(148,163,184,.12);width:100%;margin:4px 0;">

        <div>
          <h3>Nowy produkt</h3>
          <p class="muted small">Możesz wkleić normalny <span class="mono">productKey</span> albo nawet omyłkowo <span class="mono">publicKey</span> — backend sam z niego wyciągnie właściwy klucz produktu.</p>
        </div>
        <label>
          productKey
          <input id="newProductKey" placeholder="np. my-plugin" />
        </label>
        <button id="createProductBtn" class="success">Utwórz produkt</button>

        <div>
          <h3>Nowa licencja</h3>
        </div>
        <label>
          Produkt
          <select id="createLicenseProduct"></select>
        </label>
        <label>
          publicKey do pluginu
          <div class="copy-group">
            <input id="createLicensePublicKey" class="mono" readonly placeholder="Wybierz produkt, aby zobaczyć publicKey" />
            <button id="copyCreatePublicKeyBtn" type="button" class="secondary">Kopiuj</button>
          </div>
        </label>
        <label>
          Owner
          <input id="createLicenseOwner" placeholder="np. klient123" />
        </label>
        <div class="row">
          <label>
            Dni (0 = bezterminowo)
            <input id="createLicenseDays" type="number" min="0" placeholder="30" />
          </label>
          <label>
            Max serwerów
            <input id="createLicenseMaxServers" type="number" min="1" placeholder="1" />
          </label>
        </div>
        <label>
          Notatki
          <textarea id="createLicenseNotes" placeholder="Opcjonalne notatki"></textarea>
        </label>
        <button id="createLicenseBtn">Utwórz licencję</button>
      </aside>

      <main class="panel">
        <div class="app-topbar">
          <div id="mainStatusBox" class="status ok">Panel aktywny. Dane załadują się po zalogowaniu.</div>
          <div class="actions">
            <button id="refreshBtnTop" class="secondary">Odśwież wszystko</button>
          </div>
        </div>

        <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap; margin-bottom: 10px;">
          <div>
            <h2>Licencje</h2>
            <p class="muted">Kliknij wiersz, żeby załadować dane do edycji.</p>
          </div>
          <div class="actions">
            <button id="refreshBtn" class="secondary">Odśwież</button>
          </div>
        </div>

        <div class="toolbar">
          <input id="filterText" placeholder="Szukaj po kluczu, ownerze lub produkcie" />
          <select id="filterProduct"></select>
        </div>

        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>License key</th>
                <th>Produkt</th>
                <th>Owner</th>
                <th>Status</th>
                <th>Wygasa</th>
                <th>Aktywacje</th>
                <th>Plugin / MC</th>
              </tr>
            </thead>
            <tbody id="licensesTableBody"></tbody>
          </table>
        </div>

        <div style="height:18px"></div>

        <div>
          <h2>Edycja licencji</h2>
          <p class="muted">Puste <span class="mono">expiresAt</span> oznacza licencję bezterminową. Format daty: <span class="mono">2026-12-31T23:59:59Z</span>.</p>
        </div>

        <div class="license-form" style="margin-top:14px;">
          <label>
            License key
            <input id="editKey" readonly />
          </label>
          <label>
            Produkt
            <select id="editProduct"></select>
          </label>
          <label class="full">
            licensePublicKey / publicKey do pluginu
            <div class="copy-group">
              <input id="editLicensePublicKey" class="mono" readonly placeholder="Wybierz licencję lub produkt, aby zobaczyć publicKey" />
              <button id="copyEditPublicKeyBtn" type="button" class="secondary">Kopiuj</button>
            </div>
          </label>
          <label>
            Owner
            <input id="editOwner" />
          </label>
          <label>
            Status
            <select id="editStatus">
              <option value="ACTIVE">ACTIVE</option>
              <option value="REVOKED">REVOKED</option>
            </select>
          </label>
          <label>
            Expires at (UTC / ISO)
            <input id="editExpiresAt" placeholder="2026-12-31T23:59:59Z" />
          </label>
          <label>
            Max serwerów
            <input id="editMaxServers" type="number" min="1" />
          </label>
          <label class="full">
            Notatki
            <textarea id="editNotes"></textarea>
          </label>
        </div>

        <div class="actions" style="margin-top: 14px;">
          <button id="saveLicenseBtn" class="success">Zapisz zmiany</button>
          <button id="revokeBtn" class="danger">Cofnij</button>
          <button id="restoreBtn" class="warn">Przywróć</button>
          <button id="extendBtn" class="secondary">Przedłuż o 30 dni</button>
        </div>

        <div id="selectionInfo" class="status" style="margin-top: 14px;">Nie wybrano licencji.</div>

        <div style="height:18px"></div>

        <div>
          <h2>Snippet JitPack do pluginu</h2>
          <p class="muted">Wybierz produkt albo licencję, ustaw repozytorium GitHub i skopiuj gotowy snippet do pluginu. Domyślnie podstawiony jest Twój GitHub <span class="mono">sebustian329-lab</span>.</p>
        </div>

        <div class="snippet-grid" style="margin-top:14px;">
          <label>
            Produkt do snippetu
            <select id="snippetProduct"></select>
          </label>
          <label>
            GitHub owner
            <input id="snippetGithubOwner" value="sebustian329-lab" placeholder="sebustian329-lab" />
          </label>
          <label>
            GitHub repo
            <input id="snippetGithubRepo" value="LicenseSystem" placeholder="LicenseSystem" />
          </label>
        </div>

        <div class="snippet-grid-2" style="margin-top:12px;">
          <label>
            Tag / release
            <input id="snippetGithubTag" value="1.0.0" placeholder="1.0.0" />
          </label>
          <label>
            Pakiet klasy w pluginie
            <input id="snippetPackageName" value="your.plugin.license" placeholder="twoj.plugin.license" />
          </label>
        </div>

        <div class="snippet-grid-2" style="margin-top:12px;">
          <label>
            Nazwa klasy
            <input id="snippetClassName" value="MyPluginLicense" placeholder="MyPluginLicense" />
          </label>
          <label>
            publicKey produktu
            <input id="snippetPublicKey" class="mono" readonly placeholder="Wybierz produkt, aby zobaczyć publicKey" />
          </label>
        </div>

        <div class="snippet-box" style="margin-top:14px;">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;">
            <div>
              <h3 style="margin:0;">build.gradle.kts</h3>
              <div class="helper">JitPack + zależność do wspólnego SDK</div>
            </div>
            <button id="copySnippetKtsBtn" type="button" class="secondary">Kopiuj Kotlin DSL</button>
          </div>
          <textarea id="snippetGradleKts" readonly></textarea>
        </div>

        <div class="snippet-box" style="margin-top:12px;">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;">
            <div>
              <h3 style="margin:0;">build.gradle</h3>
              <div class="helper">Groovy DSL, jeśli plugin nie używa <span class="mono">build.gradle.kts</span></div>
            </div>
            <button id="copySnippetGroovyBtn" type="button" class="secondary">Kopiuj Groovy DSL</button>
          </div>
          <textarea id="snippetGradleGroovy" readonly></textarea>
        </div>

        <div class="snippet-box" style="margin-top:12px;">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;">
            <div>
              <h3 style="margin:0;">Klasa licencji</h3>
              <div class="helper">Jedna klasa z <span class="mono">publicKey</span>, bez trzymania danych w <span class="mono">build.gradle</span></div>
            </div>
            <button id="copySnippetClassBtn" type="button" class="secondary">Kopiuj klasę</button>
          </div>
          <textarea id="snippetJavaClass" readonly></textarea>
        </div>

        <div class="snippet-box" style="margin-top:12px;">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;">
            <div>
              <h3 style="margin:0;">onEnable()</h3>
              <div class="helper">Najprostszy start walidacji w pluginie Paper</div>
            </div>
            <button id="copySnippetEnableBtn" type="button" class="secondary">Kopiuj onEnable</button>
          </div>
          <textarea id="snippetOnEnable" readonly></textarea>
        </div>
      </main>
    </div>
  </div>

  <script>
    const state = {
      products: [],
      licenses: [],
      selectedKey: null,
    };

    const els = {
      loginScreen: document.getElementById('loginScreen'),
      appScreen: document.getElementById('appScreen'),
      heroDescription: document.getElementById('heroDescription'),
      panelPassword: document.getElementById('panelPassword'),
      loginBtn: document.getElementById('loginBtn'),
      logoutBtn: document.getElementById('logoutBtn'),
      healthBtn: document.getElementById('healthBtn'),
      healthBtnApp: document.getElementById('healthBtnApp'),
      refreshBtnTop: document.getElementById('refreshBtnTop'),
      statusBox: document.getElementById('statusBox'),
      appStatusBox: document.getElementById('appStatusBox'),
      mainStatusBox: document.getElementById('mainStatusBox'),
      newProductKey: document.getElementById('newProductKey'),
      createProductBtn: document.getElementById('createProductBtn'),
      createLicenseProduct: document.getElementById('createLicenseProduct'),
      createLicenseOwner: document.getElementById('createLicenseOwner'),
      createLicensePublicKey: document.getElementById('createLicensePublicKey'),
      copyCreatePublicKeyBtn: document.getElementById('copyCreatePublicKeyBtn'),
      createLicenseDays: document.getElementById('createLicenseDays'),
      createLicenseMaxServers: document.getElementById('createLicenseMaxServers'),
      createLicenseNotes: document.getElementById('createLicenseNotes'),
      createLicenseBtn: document.getElementById('createLicenseBtn'),
      refreshBtn: document.getElementById('refreshBtn'),
      filterText: document.getElementById('filterText'),
      filterProduct: document.getElementById('filterProduct'),
      licensesTableBody: document.getElementById('licensesTableBody'),
      editKey: document.getElementById('editKey'),
      editProduct: document.getElementById('editProduct'),
      editLicensePublicKey: document.getElementById('editLicensePublicKey'),
      copyEditPublicKeyBtn: document.getElementById('copyEditPublicKeyBtn'),
      editOwner: document.getElementById('editOwner'),
      editStatus: document.getElementById('editStatus'),
      editExpiresAt: document.getElementById('editExpiresAt'),
      editMaxServers: document.getElementById('editMaxServers'),
      editNotes: document.getElementById('editNotes'),
      saveLicenseBtn: document.getElementById('saveLicenseBtn'),
      revokeBtn: document.getElementById('revokeBtn'),
      restoreBtn: document.getElementById('restoreBtn'),
      extendBtn: document.getElementById('extendBtn'),
      selectionInfo: document.getElementById('selectionInfo'),
      snippetProduct: document.getElementById('snippetProduct'),
      snippetGithubOwner: document.getElementById('snippetGithubOwner'),
      snippetGithubRepo: document.getElementById('snippetGithubRepo'),
      snippetGithubTag: document.getElementById('snippetGithubTag'),
      snippetPackageName: document.getElementById('snippetPackageName'),
      snippetClassName: document.getElementById('snippetClassName'),
      snippetPublicKey: document.getElementById('snippetPublicKey'),
      snippetGradleKts: document.getElementById('snippetGradleKts'),
      snippetGradleGroovy: document.getElementById('snippetGradleGroovy'),
      snippetJavaClass: document.getElementById('snippetJavaClass'),
      snippetOnEnable: document.getElementById('snippetOnEnable'),
      copySnippetKtsBtn: document.getElementById('copySnippetKtsBtn'),
      copySnippetGroovyBtn: document.getElementById('copySnippetGroovyBtn'),
      copySnippetClassBtn: document.getElementById('copySnippetClassBtn'),
      copySnippetEnableBtn: document.getElementById('copySnippetEnableBtn'),
    };

    function panelPassword() {
      return els.panelPassword.value.trim();
    }

    function setStatus(message, ok = true, target = null) {
      const resolvedTarget = target || (els.appScreen.hidden ? els.statusBox : els.appStatusBox);
      resolvedTarget.textContent = message;
      resolvedTarget.className = 'status ' + (ok ? 'ok' : 'err');
    }

    function setAuthenticated(authenticated) {
      els.loginScreen.hidden = authenticated;
      els.appScreen.hidden = !authenticated;
      els.heroDescription.innerHTML = authenticated
        ? 'Sesja panelu jest aktywna. Zarządzaj produktami i licencjami z przeglądarki. Panel działa pod <span class="mono">/</span> i <span class="mono">/manage</span>, a API zostaje pod <span class="mono">/api/v1/manage</span> i nadal może używać <span class="mono">X-Api-Key</span>.'
        : 'Zaloguj się hasłem do panelu, a potem zarządzaj produktami i licencjami z przeglądarki. Panel działa pod <span class="mono">/</span> i <span class="mono">/manage</span>, a API zostaje pod <span class="mono">/api/v1/manage</span> i nadal może używać <span class="mono">X-Api-Key</span>.';
      if (authenticated) {
        setStatus('Zalogowano do panelu.', true, els.appStatusBox);
        setStatus('Panel aktywny. Możesz zarządzać produktami i licencjami.', true, els.mainStatusBox);
      }
    }

    async function api(path, options = {}) {
      const headers = new Headers(options.headers || {});
      headers.set('Accept', 'application/json');
      if (options.body && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
      }

      const response = await fetch(path, { ...options, headers, credentials: 'same-origin' });
      const text = await response.text();
      let data = null;
      try { data = text ? JSON.parse(text) : null; } catch (_) { data = text; }
      if (!response.ok) {
        const message = data && typeof data === 'object' && data.message ? data.message : `§{response.status} §{response.statusText}`;
        throw new Error(message);
      }
      return data;
    }

    async function login() {
      if (!panelPassword()) {
        throw new Error('Podaj hasło do panelu.');
      }
      const result = await api('/api/v1/manage/auth/login', {
        method: 'POST',
        body: JSON.stringify({ password: panelPassword() })
      });
      els.panelPassword.value = '';
      setAuthenticated(true);
      await refreshAll();
      setStatus(result.message || 'Zalogowano do panelu.');
      setStatus(result.message || 'Zalogowano do panelu.', true, els.appStatusBox);
      setStatus('Panel aktywny. Dane zostały załadowane.', true, els.mainStatusBox);
    }

    async function logout() {
      const result = await api('/api/v1/manage/auth/logout', { method: 'POST' });
      state.products = [];
      state.licenses = [];
      state.selectedKey = null;
      setAuthenticated(false);
      els.licensesTableBody.innerHTML = `<tr><td colspan="7" class="muted">Zaloguj się, aby pobrać dane.</td></tr>`;
      els.editKey.value = '';
      els.editLicensePublicKey.value = '';
      els.createLicensePublicKey.value = '';
      els.snippetPublicKey.value = '';
      renderJitPackSnippets();
      setStatus(result.message || 'Wylogowano z panelu.', true, els.statusBox);
      setStatus('Nie wybrano licencji.', true, els.selectionInfo);
    }

    async function checkAuthStatus() {
      const result = await api('/api/v1/manage/auth/status');
      return !!result.authenticated;
    }

    function populateProductSelect(select, includeAll = false) {
      const current = select.value;
      const options = [];
      if (includeAll) {
        options.push(`<option value="">Wszystkie produkty</option>`);
      }
      if (!includeAll) {
        options.push(`<option value="">Wybierz produkt</option>`);
      }
      for (const product of state.products) {
        options.push(`<option value="§{escapeHtml(product.productKey)}">§{escapeHtml(product.productKey)}</option>`);
      }
      select.innerHTML = options.join('');
      if ([...select.options].some(o => o.value === current)) {
        select.value = current;
      }
    }

    function productPublicKey(productKey) {
      const normalized = String(productKey || '').trim();
      if (!normalized) return '';
      return state.products.find(product => product.productKey === normalized)?.publicKey || '';
    }

    function setPublicKeyPreview(input, productKey) {
      const value = productPublicKey(productKey);
      input.value = value;
      input.title = value || 'Brak publicKey dla wybranego produktu';
    }

    function updateCreatePublicKeyPreview() {
      setPublicKeyPreview(els.createLicensePublicKey, els.createLicenseProduct.value);
    }

    function updateEditPublicKeyPreview() {
      setPublicKeyPreview(els.editLicensePublicKey, els.editProduct.value);
    }

    function trimOrFallback(value, fallback) {
      const normalized = String(value || '').trim();
      return normalized || fallback;
    }

    function packagePathToFolder(packageName) {
      return trimOrFallback(packageName, 'your.plugin.license')
        .split('.')
        .map(part => part.trim())
        .filter(Boolean)
        .join('/');
    }

    function packageStatement(packageName) {
      const normalized = trimOrFallback(packageName, 'your.plugin.license');
      return normalized ? `package §{normalized};

` : '';
    }

    function currentSnippetProductKey() {
      return trimOrFallback(els.snippetProduct.value, els.editProduct.value || els.createLicenseProduct.value || '');
    }

    function currentSnippetPublicKey() {
      const current = trimOrFallback(els.snippetPublicKey.value, '');
      if (current) return current;
      return productPublicKey(currentSnippetProductKey());
    }

    function snippetValues() {
      const productKey = currentSnippetProductKey();
      const publicKey = productPublicKey(productKey);
      const owner = trimOrFallback(els.snippetGithubOwner.value, 'sebustian329-lab');
      const repo = trimOrFallback(els.snippetGithubRepo.value, 'LicenseSystem');
      const tag = trimOrFallback(els.snippetGithubTag.value, '1.0.0');
      const packageName = trimOrFallback(els.snippetPackageName.value, 'your.plugin.license');
      const className = trimOrFallback(els.snippetClassName.value, 'MyPluginLicense');
      const packageLine = packageStatement(packageName);
      const folder = packagePathToFolder(packageName);
      return { productKey, publicKey, owner, repo, tag, packageName, className, packageLine, folder };
    }

    function updateSnippetProductPreview() {
      const key = currentSnippetProductKey();
      els.snippetPublicKey.value = productPublicKey(key);
      els.snippetPublicKey.title = els.snippetPublicKey.value || 'Brak publicKey dla wybranego produktu';
    }

    function renderJitPackSnippets() {
      const { productKey, publicKey, owner, repo, tag, packageName, className, packageLine, folder } = snippetValues();
      const effectivePublicKey = publicKey || 'lspub_TU_WKLEJ_PUBLIC_KEY';
      const gradleKts = `repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("com.github.§{owner}.§{repo}:plugin-sdk:§{tag}")
}`;
      const gradleGroovy = `repositories {
    mavenCentral()
    maven { url "https://jitpack.io" }
}

dependencies {
    implementation "com.github.§{owner}.§{repo}:plugin-sdk:§{tag}"
}`;
      const javaClass = `// src/main/java/§{folder}/§{className}.java
§{packageLine}import dev.licensesystem.sdk.LicensePluginConfiguration;

public final class §{className} {
    public static final LicensePluginConfiguration CONFIG =
        LicensePluginConfiguration.of("§{effectivePublicKey}", 5000);

    private §{className}() {
    }
}`;
      const onEnable = `import dev.licensesystem.sdk.PaperLicenseGuard;

@Override
public void onEnable() {
    PaperLicenseGuard.verifyOrDisable(this, §{className}.CONFIG);
}`;
      els.snippetGradleKts.value = gradleKts;
      els.snippetGradleGroovy.value = gradleGroovy;
      els.snippetJavaClass.value = javaClass;
      els.snippetOnEnable.value = onEnable;
      updateSnippetProductPreview();
      const hint = productKey ? `Gotowy snippet dla produktu §{productKey}.` : 'Wybierz produkt, żeby wkleić właściwy publicKey do klasy.';
      setStatus(hint + `
JitPack: com.github.§{owner}.§{repo}:plugin-sdk:§{tag}`, !!productKey, els.selectionInfo);
    }

    async function copyTextAreaValue(textArea, successMessage) {
      const value = textArea.value.trim();
      if (!value) {
        throw new Error('Brak snippetu do skopiowania.');
      }
      await navigator.clipboard.writeText(value);
      setStatus(successMessage);
    }

    async function copyInputValue(input, successMessage) {
      const value = input.value.trim();
      if (!value) {
        throw new Error('Brak publicKey do skopiowania.');
      }
      await navigator.clipboard.writeText(value);
      setStatus(successMessage);
    }

    function escapeHtml(value) {
      return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
    }

    function formatDate(value) {
      if (!value) return 'bezterminowa';
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) return value;
      return `§{date.toLocaleDateString('pl-PL')} §{date.toLocaleTimeString('pl-PL')}`;
    }

    function currentFilteredLicenses() {
      const text = els.filterText.value.trim().toLowerCase();
      const product = els.filterProduct.value.trim();
      return state.licenses.filter(item => {
        if (product && item.productId !== product) {
          return false;
        }
        if (!text) {
          return true;
        }
        const haystack = [item.key, item.productId, item.owner, item.status, item.notes || '']
          .join(' ')
          .toLowerCase();
        return haystack.includes(text);
      });
    }

    function renderLicenses() {
      const rows = currentFilteredLicenses().map(item => {
        const selected = item.key === state.selectedKey;
        const activations = `§{item.activations?.length || 0}/§{item.maxServers}`;
        const versions = `§{item.lastPluginVersion || '-'} / §{item.lastMinecraftVersion || '-'}`;
        return `
          <tr data-key="§{escapeHtml(item.key)}" style="cursor:pointer;§{selected ? 'outline:1px solid rgba(96,165,250,.45); outline-offset:-1px;' : ''}">
            <td class="mono">§{escapeHtml(item.key)}</td>
            <td>§{escapeHtml(item.productId)}</td>
            <td>§{escapeHtml(item.owner)}</td>
            <td>§{escapeHtml(item.status)}</td>
            <td>§{escapeHtml(formatDate(item.expiresAt))}</td>
            <td>§{escapeHtml(activations)}</td>
            <td>§{escapeHtml(versions)}</td>
          </tr>
        `;
      });
      els.licensesTableBody.innerHTML = rows.join('') || `<tr><td colspan="7" class="muted">Brak licencji do wyświetlenia.</td></tr>`;

      for (const row of els.licensesTableBody.querySelectorAll('tr[data-key]')) {
        row.addEventListener('click', () => selectLicense(row.dataset.key));
      }
    }

    function selectLicense(key) {
      state.selectedKey = key;
      const item = state.licenses.find(entry => entry.key === key);
      if (!item) return;
      els.editKey.value = item.key;
      els.editProduct.value = item.productId;
      updateEditPublicKeyPreview();
      els.editOwner.value = item.owner || '';
      els.editStatus.value = item.status || 'ACTIVE';
      if ([...els.snippetProduct.options].some(option => option.value === item.productId)) {
        els.snippetProduct.value = item.productId;
      }
      renderJitPackSnippets();
      els.editExpiresAt.value = item.expiresAt || '';
      els.editMaxServers.value = item.maxServers || 1;
      els.editNotes.value = item.notes || '';
      setStatus(`Wybrano licencję §{item.key}. Możesz edytować i zapisać.`, true, els.selectionInfo);
      renderLicenses();
    }

    async function refreshProducts() {
      state.products = await api('/api/v1/manage/products');
      populateProductSelect(els.createLicenseProduct, false);
      populateProductSelect(els.editProduct, false);
      populateProductSelect(els.filterProduct, true);
      populateProductSelect(els.snippetProduct, false);
      updateCreatePublicKeyPreview();
      updateEditPublicKeyPreview();
      if (!els.snippetProduct.value && els.createLicenseProduct.value) {
        els.snippetProduct.value = els.createLicenseProduct.value;
      }
      updateSnippetProductPreview();
      renderJitPackSnippets();
    }

    async function refreshLicenses() {
      const product = els.filterProduct.value.trim();
      const suffix = product ? `?productId=§{encodeURIComponent(product)}` : '';
      state.licenses = await api(`/api/v1/manage/licenses§{suffix}`);
      if (state.selectedKey && !state.licenses.some(item => item.key === state.selectedKey)) {
        state.selectedKey = null;
        els.editKey.value = '';
      }
      renderLicenses();
      if (state.selectedKey) {
        selectLicense(state.selectedKey);
      }
    }

    async function refreshAll() {
      await refreshProducts();
      await refreshLicenses();
      const message = `Załadowano §{state.products.length} produktów i §{state.licenses.length} licencji.`;
      setStatus(message);
      setStatus(message, true, els.appStatusBox);
      setStatus(message, true, els.mainStatusBox);
      renderJitPackSnippets();
    }

    async function withBusy(button, task) {
      const prev = button.textContent;
      button.disabled = true;
      button.textContent = 'Przetwarzam...';
      try {
        await task();
      } finally {
        button.disabled = false;
        button.textContent = prev;
      }
    }

    els.loginBtn.addEventListener('click', () => withBusy(els.loginBtn, login).catch(error => setStatus(error.message, false)));
    els.logoutBtn.addEventListener('click', () => withBusy(els.logoutBtn, logout).catch(error => setStatus(error.message, false)));
    els.panelPassword.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); els.loginBtn.click(); } });

    els.healthBtn.addEventListener('click', () => withBusy(els.healthBtn, async () => {
      const result = await api('/api/v1/manage/health');
      setStatus(`API odpowiada: §{result.message}`);
    }).catch(error => setStatus(error.message, false)));

    els.healthBtnApp.addEventListener('click', () => withBusy(els.healthBtnApp, async () => {
      const result = await api('/api/v1/manage/health');
      setStatus(`API odpowiada: §{result.message}`, true, els.appStatusBox);
      setStatus(`API odpowiada: §{result.message}`, true, els.mainStatusBox);
    }).catch(error => {
      setStatus(error.message, false, els.appStatusBox);
      setStatus(error.message, false, els.mainStatusBox);
    }));

    els.createProductBtn.addEventListener('click', () => withBusy(els.createProductBtn, async () => {
      const productKey = els.newProductKey.value.trim();
      if (!productKey) throw new Error('Podaj productKey.');
      const created = await api('/api/v1/manage/products', {
        method: 'POST',
        body: JSON.stringify({ productKey })
      });
      els.newProductKey.value = '';
      await refreshProducts();
      els.snippetProduct.value = created.productKey;
      renderJitPackSnippets();
      setStatus(`Produkt gotowy: §{created.productKey}. Snippet JitPack został od razu uzupełniony.`);
    }).catch(error => setStatus(error.message, false)));

    els.createLicenseBtn.addEventListener('click', () => withBusy(els.createLicenseBtn, async () => {
      const productKey = els.createLicenseProduct.value.trim();
      const owner = els.createLicenseOwner.value.trim();
      if (!productKey) throw new Error('Wybierz produkt.');
      if (!owner) throw new Error('Podaj ownera.');
      const daysRaw = els.createLicenseDays.value.trim();
      const maxRaw = els.createLicenseMaxServers.value.trim();
      const payload = {
        productKey,
        owner,
        durationDays: daysRaw === '' ? null : Number(daysRaw),
        maxServers: maxRaw === '' ? null : Number(maxRaw),
        notes: els.createLicenseNotes.value.trim() || null
      };
      const created = await api('/api/v1/manage/licenses', {
        method: 'POST',
        body: JSON.stringify(payload)
      });
      els.createLicenseOwner.value = '';
      els.createLicenseDays.value = '';
      els.createLicenseMaxServers.value = '';
      els.createLicenseNotes.value = '';
      await refreshLicenses();
      if ([...els.snippetProduct.options].some(option => option.value === productKey)) {
        els.snippetProduct.value = productKey;
      }
      renderJitPackSnippets();
      selectLicense(created.key);
      setStatus(`Licencja utworzona: §{created.key}. publicKey do pluginu i snippet JitPack są gotowe do skopiowania.`);
    }).catch(error => setStatus(error.message, false)));

    els.refreshBtn.addEventListener('click', () => withBusy(els.refreshBtn, refreshAll).catch(error => {
      setStatus(error.message, false, els.appStatusBox);
      setStatus(error.message, false, els.mainStatusBox);
    }));
    els.refreshBtnTop.addEventListener('click', () => withBusy(els.refreshBtnTop, refreshAll).catch(error => {
      setStatus(error.message, false, els.appStatusBox);
      setStatus(error.message, false, els.mainStatusBox);
    }));
    els.filterText.addEventListener('input', renderLicenses);
    els.filterProduct.addEventListener('change', () => withBusy(els.refreshBtn, refreshLicenses).catch(error => setStatus(error.message, false)));
    els.createLicenseProduct.addEventListener('change', () => {
      updateCreatePublicKeyPreview();
      if (!els.snippetProduct.value) {
        els.snippetProduct.value = els.createLicenseProduct.value;
      }
      renderJitPackSnippets();
    });
    els.editProduct.addEventListener('change', () => {
      updateEditPublicKeyPreview();
      if (els.editProduct.value) {
        els.snippetProduct.value = els.editProduct.value;
      }
      renderJitPackSnippets();
    });
    els.snippetProduct.addEventListener('change', renderJitPackSnippets);
    els.snippetGithubOwner.addEventListener('input', renderJitPackSnippets);
    els.snippetGithubRepo.addEventListener('input', renderJitPackSnippets);
    els.snippetGithubTag.addEventListener('input', renderJitPackSnippets);
    els.snippetPackageName.addEventListener('input', renderJitPackSnippets);
    els.snippetClassName.addEventListener('input', renderJitPackSnippets);
    els.copyCreatePublicKeyBtn.addEventListener('click', () => withBusy(els.copyCreatePublicKeyBtn, () => copyInputValue(els.createLicensePublicKey, 'Skopiowano publicKey produktu do schowka.')).catch(error => setStatus(error.message, false)));
    els.copyEditPublicKeyBtn.addEventListener('click', () => withBusy(els.copyEditPublicKeyBtn, () => copyInputValue(els.editLicensePublicKey, 'Skopiowano licensePublicKey do schowka.')).catch(error => setStatus(error.message, false)));
    els.copySnippetKtsBtn.addEventListener('click', () => withBusy(els.copySnippetKtsBtn, () => copyTextAreaValue(els.snippetGradleKts, 'Skopiowano build.gradle.kts do schowka.')).catch(error => setStatus(error.message, false)));
    els.copySnippetGroovyBtn.addEventListener('click', () => withBusy(els.copySnippetGroovyBtn, () => copyTextAreaValue(els.snippetGradleGroovy, 'Skopiowano build.gradle do schowka.')).catch(error => setStatus(error.message, false)));
    els.copySnippetClassBtn.addEventListener('click', () => withBusy(els.copySnippetClassBtn, () => copyTextAreaValue(els.snippetJavaClass, 'Skopiowano klasę licencji do schowka.')).catch(error => setStatus(error.message, false)));
    els.copySnippetEnableBtn.addEventListener('click', () => withBusy(els.copySnippetEnableBtn, () => copyTextAreaValue(els.snippetOnEnable, 'Skopiowano onEnable do schowka.')).catch(error => setStatus(error.message, false)));

    els.saveLicenseBtn.addEventListener('click', () => withBusy(els.saveLicenseBtn, async () => {
      const key = els.editKey.value.trim();
      if (!key) throw new Error('Najpierw wybierz licencję z tabeli.');
      const payload = {
        productKey: els.editProduct.value.trim(),
        owner: els.editOwner.value.trim(),
        status: els.editStatus.value,
        expiresAt: els.editExpiresAt.value.trim(),
        maxServers: Number(els.editMaxServers.value || 1),
        notes: els.editNotes.value
      };
      const updated = await api(`/api/v1/manage/licenses/§{encodeURIComponent(key)}/update`, {
        method: 'POST',
        body: JSON.stringify(payload)
      });
      await refreshLicenses();
      selectLicense(updated.key);
      setStatus(`Zapisano zmiany dla §{updated.key}.`, true, els.selectionInfo);
      setStatus(`Licencja §{updated.key} została zaktualizowana.`);
    }).catch(error => { setStatus(error.message, false); setStatus(error.message, false, els.selectionInfo); }));

    els.revokeBtn.addEventListener('click', () => withBusy(els.revokeBtn, async () => {
      const key = els.editKey.value.trim();
      if (!key) throw new Error('Najpierw wybierz licencję.');
      const updated = await api(`/api/v1/manage/licenses/§{encodeURIComponent(key)}/revoke`, { method: 'POST' });
      await refreshLicenses();
      selectLicense(updated.key);
      setStatus(`Licencja §{updated.key} cofnięta.`);
    }).catch(error => setStatus(error.message, false)));

    els.restoreBtn.addEventListener('click', () => withBusy(els.restoreBtn, async () => {
      const key = els.editKey.value.trim();
      if (!key) throw new Error('Najpierw wybierz licencję.');
      const updated = await api(`/api/v1/manage/licenses/§{encodeURIComponent(key)}/restore`, { method: 'POST' });
      await refreshLicenses();
      selectLicense(updated.key);
      setStatus(`Licencja §{updated.key} przywrócona.`);
    }).catch(error => setStatus(error.message, false)));

    els.extendBtn.addEventListener('click', () => withBusy(els.extendBtn, async () => {
      const key = els.editKey.value.trim();
      if (!key) throw new Error('Najpierw wybierz licencję.');
      const updated = await api(`/api/v1/manage/licenses/§{encodeURIComponent(key)}/extend`, {
        method: 'POST',
        body: JSON.stringify({ days: 30 })
      });
      await refreshLicenses();
      selectLicense(updated.key);
      setStatus(`Licencja §{updated.key} przedłużona o 30 dni.`);
    }).catch(error => setStatus(error.message, false)));

    setAuthenticated(false);

    checkAuthStatus()
      .then(authenticated => {
        setAuthenticated(authenticated);
        if (authenticated) {
          return refreshAll();
        }
        renderJitPackSnippets();
        setStatus('Zaloguj się hasłem do panelu i kliknij „Zaloguj”.', false);
      })
      .catch(error => setStatus(error.message, false));
  </script>
</body>
</html>
    """.trimIndent().replace("§", "$")
}
