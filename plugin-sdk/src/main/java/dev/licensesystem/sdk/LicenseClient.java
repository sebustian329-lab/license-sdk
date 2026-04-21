package dev.licensesystem.sdk;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

public final class LicenseClient {
    private final LicenseSdkSettings settings;
    private final HttpClient httpClient;

    public LicenseClient() {
        this(LicenseSdkSettings.load());
    }

    public LicenseClient(LicenseSdkSettings settings) {
        this.settings = settings;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(settings.timeoutMs()))
            .build();
    }

    public LicenseClient(LicensePluginConfiguration configuration) {
        this(LicenseSdkSettings.fromPluginConfiguration(configuration));
    }

    public LicenseValidationResult validate(
        String licenseKey,
        String serverFingerprint,
        String serverName,
        String pluginVersion,
        String minecraftVersion
    ) {
        try {
            String query = "licenseKey=" + encode(licenseKey)
                + "&productKey=" + encode(settings.productKey())
                + "&publicKey=" + encode(settings.publicKey())
                + "&serverFingerprint=" + encode(serverFingerprint)
                + "&serverName=" + encode(serverName)
                + "&pluginVersion=" + encode(pluginVersion)
                + "&minecraftVersion=" + encode(minecraftVersion);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(settings.baseUrl().replaceAll("/$", "") + "/api/v1/public/validate?" + query))
                .timeout(Duration.ofMillis(settings.timeoutMs()))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            LicenseValidationResult parsed = parseResponse(response.body());
            if (response.statusCode() >= 400) {
                return LicenseValidationResult.invalid(parsed.message());
            }
            return parsed;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return LicenseValidationResult.invalid("Nie udało się połączyć z serwerem licencji: " + exception.getMessage());
        } catch (IOException exception) {
            return LicenseValidationResult.invalid("Nie udało się połączyć z serwerem licencji: " + exception.getMessage());
        } catch (Exception exception) {
            return LicenseValidationResult.invalid("Nie udało się połączyć z serwerem licencji: " + exception.getMessage());
        }
    }

    private LicenseValidationResult parseResponse(String body) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(body));

        return new LicenseValidationResult(
            Boolean.parseBoolean(properties.getProperty("valid", "false")),
            properties.getProperty("message", "Nieznana odpowiedź serwera."),
            properties.getProperty("owner", ""),
            properties.getProperty("expiresAt", ""),
            parseInt(properties.getProperty("maxServers", "0")),
            parseInt(properties.getProperty("activeServers", "0"))
        );
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value == null || value.isBlank() ? "0" : value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
