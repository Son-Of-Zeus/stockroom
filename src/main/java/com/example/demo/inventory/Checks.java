package com.example.demo.inventory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class Checks {
    private Checks() {}

    public static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw new ApiException(400, field + " is required and must contain at most " + max + " characters.");
        }
        return value.strip();
    }

    public static String optional(String value, int max) {
        if (value != null && value.length() > max) {
            throw new ApiException(400, "Text is too long.");
        }
        return value == null ? "" : value.strip();
    }

    public static String normalized(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int page(int page) {
        if (page < 0 || page > 10000) throw new ApiException(400, "Page must be between 0 and 10000.");
        return page;
    }

    public static int size(int size) {
        if (size < 1 || size > 100) throw new ApiException(400, "Page size must be between 1 and 100.");
        return size;
    }
}
