package dev.ccassistant.provider;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Where keys may be sent: https anywhere, plain http only to loopback addresses (local model servers). */
public final class UrlRules {
    private UrlRules() {
    }

    /** Header names a profile may not set, because they carry credentials or decide routing. */
    public static final Set<String> FORBIDDEN_HEADERS = Set.of(
        "authorization", "x-api-key", "host", "cookie", "content-length", "proxy-authorization", "connection",
        "transfer-encoding", "upgrade", "expect");

    /** Returns the base URL without a trailing slash, or throws with a message suitable for players. */
    public static String checkBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Base URL is empty");
        var trimmed = raw.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("Base URL is not a valid URL");
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        var host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("Base URL has no host");
        if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("Base URL must not contain a user name or password");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Base URL must not contain ? or #");
        }
        switch (scheme) {
            case "https" -> {
            }
            case "http" -> {
                if (!isLoopback(host)) {
                    throw new IllegalArgumentException("Plain http is only allowed for local servers (localhost); use https");
                }
            }
            default -> throw new IllegalArgumentException("Base URL must start with https://");
        }
        return trimmed;
    }

    public static boolean isLoopback(String host) {
        var h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.equals("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) return true;
        var parts = h.split("\\.");
        if (parts.length != 4 || !parts[0].equals("127")) return false;
        for (var p : parts) {
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit) || Integer.parseInt(p) > 255) return false;
        }
        return true;
    }

    public static void checkHeaderName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")) {
            throw new IllegalArgumentException("Invalid header name");
        }
        if (FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Header " + name + " can't be set here");
        }
    }

    public static void checkHeaderValue(String value) {
        if (value == null || value.length() > 1024 || value.chars().anyMatch(c -> c < 0x20 && c != '\t' || c == 0x7f)) {
            throw new IllegalArgumentException("Invalid header value");
        }
    }
}
