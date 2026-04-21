package dev.licensesystem.sdk;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperLicenseGuard {
    private PaperLicenseGuard() {
    }

    public static LicenseValidationResult verifyOrDisable(JavaPlugin plugin) {
        return verifyOrDisable(plugin, new LicenseClient());
    }

    public static LicenseValidationResult verifyOrDisable(JavaPlugin plugin, LicensePluginConfiguration configuration) {
        return verifyOrDisable(plugin, new LicenseClient(configuration));
    }

    public static LicenseValidationResult verifyOrDisable(JavaPlugin plugin, String publicKey) {
        return verifyOrDisable(plugin, LicensePluginConfiguration.of(publicKey));
    }

    public static LicenseValidationResult verifyOrDisable(JavaPlugin plugin, String publicKey, int timeoutMs) {
        return verifyOrDisable(plugin, LicensePluginConfiguration.of(publicKey, timeoutMs));
    }

    private static LicenseValidationResult verifyOrDisable(JavaPlugin plugin, LicenseClient client) {
        plugin.saveDefaultConfig();

        String licenseKey = plugin.getConfig().getString("license", "").trim();
        if (licenseKey.isBlank()) {
            LicenseValidationResult result = LicenseValidationResult.invalid("Brakuje wpisu 'license' w configu pluginu.");
            disable(plugin, result.message());
            return result;
        }

        try {
            LicenseValidationResult result = client.validate(
                licenseKey,
                PaperServerFingerprint.create(plugin),
                plugin.getServer().getName() + ":" + plugin.getServer().getPort(),
                plugin.getDescription().getVersion(),
                Bukkit.getMinecraftVersion()
            );

            if (!result.valid()) {
                disable(plugin, result.message());
            } else {
                plugin.getLogger().info("Licencja poprawna. Właściciel: " + result.owner() + ".");
            }

            return result;
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "Nieznany błąd licencji." : exception.getMessage();
            LicenseValidationResult result = LicenseValidationResult.invalid(message);
            disable(plugin, result.message());
            return result;
        }
    }

    private static void disable(JavaPlugin plugin, String reason) {
        plugin.getLogger().severe("Weryfikacja licencji nie powiodła się: " + reason);
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        pluginManager.disablePlugin(plugin);
    }
}
