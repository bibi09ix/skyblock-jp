package local.skyblockjp;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Stable identities shared by request deduplication, local lookup and diagnostics. */
final class TranslationFingerprint {
    private TranslationFingerprint() {}

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String context(Map<String, Object> context) {
        var sorted = new TreeMap<String, Object>();
        if (context != null)
            context.forEach(
                    (key, value) -> {
                        if (value instanceof String
                                || value instanceof Number
                                || value instanceof Boolean) sorted.put(key, value);
                    });
        return new Gson().toJson(sorted);
    }
}
