package dev.licensesystem.sdk;

public interface LicensePluginConfiguration {
    String publicKey();

    default int timeoutMs() {
        return 5000;
    }

    static LicensePluginConfiguration of(String publicKey) {
        return new StaticLicensePluginConfiguration(publicKey, 5000);
    }

    static LicensePluginConfiguration of(String publicKey, int timeoutMs) {
        return new StaticLicensePluginConfiguration(publicKey, timeoutMs);
    }
}
