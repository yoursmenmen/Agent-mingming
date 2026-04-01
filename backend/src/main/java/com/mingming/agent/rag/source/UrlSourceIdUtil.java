package com.mingming.agent.rag.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class UrlSourceIdUtil {

    private UrlSourceIdUtil() {}

    public static String toSourceId(String name, String url) {
        String safeName = (name == null || name.isBlank() ? "unnamed" : name.strip())
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        String safeUrl = url == null ? "" : url.strip();
        String hash = sha256Hex(safeUrl);
        String suffix = hash.length() >= 10 ? hash.substring(0, 10) : hash;
        return "url:" + safeName + ":" + suffix;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
