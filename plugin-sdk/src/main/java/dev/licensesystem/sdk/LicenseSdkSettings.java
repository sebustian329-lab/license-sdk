package dev.licensesystem.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

public record LicenseSdkSettings(
    String baseUrl,
    String productKey,
    String publicToken,
    String publicKey,
    int timeoutMs
) {
    public static LicenseSdkSettings load() {
        Properties properties = new Properties();
        try (InputStream inputStream = LicenseSdkSettings.class.getClassLoader().getResourceAsStream("license-sdk.properties")) {
            if (inputStream == null) {
                throw new IllegalStateException("Brak pliku license-sdk.properties. Uzyj overloadu PaperLicenseGuard.verifyOrDisable(plugin, config) albo skonfiguruj plugin Gradle dev.licensesystem.plugin-sdk.");
            }
            properties.load(inputStream);
        } catch (IOException exception) {
            throw new IllegalStateException("Nie udało się wczytać license-sdk.properties.", exception);
        }

        String explicitPublicKey = properties.getProperty("license.public-key", "").trim();
        String explicitProductKey = properties.getProperty("license.product-key", "").trim();
        int timeoutMs = Integer.parseInt(properties.getProperty("license.timeout-ms", "5000"));

        if (!explicitPublicKey.isEmpty() && !explicitProductKey.isEmpty()) {
            LicensePublicKeyData decoded = LicensePublicKeyCodec.decode(explicitPublicKey);
            if (!decoded.productKey().equals(explicitProductKey)) {
                throw new IllegalStateException("productKey nie zgadza sie z przekazanym publicKey.");
            }
            return new LicenseSdkSettings(
                decoded.baseUrl(),
                explicitProductKey,
                decoded.publicToken(),
                explicitPublicKey,
                timeoutMs
            );
        }

        String baseUrl = require(properties, "license.base-url");
        String productKey = require(properties, "license.product-id");
        String publicToken = require(properties, "license.public-token");
        String generatedPublicKey = LicensePublicKeyCodec.encode(
            new LicensePublicKeyData(baseUrl, productKey, publicToken)
        );

        return new LicenseSdkSettings(
            baseUrl,
            productKey,
            publicToken,
            generatedPublicKey,
            timeoutMs
        );
    }

    public static LicenseSdkSettings fromPluginConfiguration(LicensePluginConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        LicensePublicKeyData decoded = LicensePublicKeyCodec.decode(configuration.publicKey().trim());
        return new LicenseSdkSettings(
            decoded.baseUrl(),
            decoded.productKey(),
            decoded.publicToken(),
            configuration.publicKey().trim(),
            configuration.timeoutMs()
        );
    }

    public static LicenseSdkSettings fromPublicKey(String publicKey) {
        return fromPluginConfiguration(LicensePluginConfiguration.of(publicKey));
    }

    public static LicenseSdkSettings fromPublicKey(String publicKey, int timeoutMs) {
        return fromPluginConfiguration(LicensePluginConfiguration.of(publicKey, timeoutMs));
    }

    private static String require(Properties properties, String key) {
        return Objects.requireNonNull(properties.getProperty(key), "Brakuje właściwości: " + key).trim();
    }
}
