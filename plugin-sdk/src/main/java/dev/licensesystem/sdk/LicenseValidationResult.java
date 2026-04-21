package dev.licensesystem.sdk;

public record LicenseValidationResult(
    boolean valid,
    String message,
    String owner,
    String expiresAt,
    int maxServers,
    int activeServers
) {
    public static LicenseValidationResult invalid(String message) {
        return new LicenseValidationResult(false, message, "", "", 0, 0);
    }
}
