package dev.licensesystem.sdk;

import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class PaperServerFingerprint {
    private PaperServerFingerprint() {
    }

    public static String create(JavaPlugin plugin) {
        Server server = plugin.getServer();
        String raw = String.join("|",
            safe(server.getIp(), "0.0.0.0"),
            Integer.toString(server.getPort()),
            safe(server.getName(), "paper"),
            safe(server.getVersion(), "unknown"),
            plugin.getDataFolder().getAbsolutePath(),
            server.getWorldContainer().getAbsolutePath(),
            safe(resolveHostName(), "localhost")
        );

        return sha256(raw);
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "localhost";
        }
    }

    private static String safe(String input, String fallback) {
        return input == null || input.isBlank() ? fallback : input;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing SHA-256 support.", exception);
        }
    }
}
