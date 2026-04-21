package dev.licensesystem.sdk;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

public final class LicensePublicKeyCodec {
    private static final String PREFIX = "lspub_";

    private LicensePublicKeyCodec() {
    }

    public static String encode(LicensePublicKeyData payload) {
        String content = ""
            + "version=1\n"
            + "baseUrl=" + payload.baseUrl().trim() + "\n"
            + "productKey=" + payload.productKey().trim() + "\n"
            + "publicToken=" + payload.publicToken().trim() + "\n";

        String encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(content.getBytes(StandardCharsets.UTF_8));

        return PREFIX + encoded;
    }

    public static LicensePublicKeyData decode(String publicKey) {
        if (publicKey == null || !publicKey.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Nieprawidlowy publicKey.");
        }

        try {
            String decoded = new String(
                Base64.getUrlDecoder().decode(publicKey.substring(PREFIX.length())),
                StandardCharsets.UTF_8
            );

            Properties properties = new Properties();
            properties.load(new StringReader(decoded));

            String baseUrl = required(properties, "baseUrl");
            String productKey = required(properties, "productKey");
            String publicToken = required(properties, "publicToken");

            return new LicensePublicKeyData(baseUrl, productKey, publicToken);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Nie udalo sie odczytac publicKey.", exception);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Brakuje pola " + key + " w publicKey.");
        }
        return value.trim();
    }
}
