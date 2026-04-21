package dev.licensesystem.sdk;

import java.util.Objects;

public record StaticLicensePluginConfiguration(
    String publicKey,
    int timeoutMs
) implements LicensePluginConfiguration {
    public StaticLicensePluginConfiguration {
        Objects.requireNonNull(publicKey, "publicKey");
        if (publicKey.isBlank()) {
            throw new IllegalArgumentException("publicKey nie moze byc pusty.");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs musi byc wiekszy od 0.");
        }
    }

    public StaticLicensePluginConfiguration(String publicKey) {
        this(publicKey, 5000);
    }
}
