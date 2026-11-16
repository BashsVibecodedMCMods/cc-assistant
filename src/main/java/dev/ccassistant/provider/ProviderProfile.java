package dev.ccassistant.provider;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One provider configuration: a player's profile (client) or an operator's provider (server). Holds no secret: the key
 * comes from {@link #keyMode} (a stored credential looked up by {@link #id}, or an environment variable by name).
 */
public record ProviderProfile(
    String id,
    String name,
    Preset preset,
    ProtocolKind protocol,
    String baseUrl,
    String model,
    int maxTokens,
    @Nullable String effort,
    boolean refusalFallback,
    boolean useMaxCompletionTokens,
    Map<String, String> headers,
    KeyMode keyMode,
    String keyEnvVar,
    boolean luaSelectable
) {
    public enum KeyMode {STORED, ENV, NONE}

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    public static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    public static final java.util.Set<String> EFFORTS = java.util.Set.of("low", "medium", "high", "xhigh", "max");
    public static final int MAX_TOKENS_LIMIT = 128_000;

    public ProviderProfile {
        headers = Map.copyOf(headers);
    }

    /** Checks every field; returns this profile with the base URL normalised. Messages are for players. */
    public ProviderProfile validated() {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Name must be 1-32 letters, digits, _ or -");
        }
        var url = UrlRules.checkBaseUrl(baseUrl);
        if (model == null || model.isBlank() || model.length() > 200 || model.chars().anyMatch(c -> c < 0x20)) {
            throw new IllegalArgumentException("Choose a model");
        }
        if (maxTokens < 1 || maxTokens > MAX_TOKENS_LIMIT) throw new IllegalArgumentException("Max tokens must be 1-" + MAX_TOKENS_LIMIT);
        if (effort != null && !EFFORTS.contains(effort)) throw new IllegalArgumentException("Unknown effort " + effort);
        if (keyMode == KeyMode.ENV && (keyEnvVar == null || !ENV_NAME.matcher(keyEnvVar).matches())) {
            throw new IllegalArgumentException("Environment variable name is not valid");
        }
        for (var e : headers.entrySet()) {
            UrlRules.checkHeaderName(e.getKey());
            UrlRules.checkHeaderValue(e.getValue());
        }
        if (headers.size() > 16) throw new IllegalArgumentException("At most 16 extra headers");
        return new ProviderProfile(id, name, preset, protocol, url, model.trim(), maxTokens, effort, refusalFallback,
            useMaxCompletionTokens, headers, keyMode, keyEnvVar == null ? "" : keyEnvVar, luaSelectable);
    }

    /** Headers sent on every request: the preset's fixed headers, then the profile's own. */
    public Map<String, String> allHeaders() {
        var all = new LinkedHashMap<>(preset.fixedHeaders());
        all.putAll(headers);
        return all;
    }

    public JsonObject toJson() {
        var o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("preset", preset.id());
        o.addProperty("protocol", protocol.id());
        o.addProperty("baseUrl", baseUrl);
        o.addProperty("model", model);
        o.addProperty("maxTokens", maxTokens);
        if (effort != null) o.addProperty("effort", effort);
        o.addProperty("refusalFallback", refusalFallback);
        o.addProperty("useMaxCompletionTokens", useMaxCompletionTokens);
        var h = new JsonObject();
        headers.forEach(h::addProperty);
        o.add("headers", h);
        o.addProperty("keyMode", keyMode.name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("keyEnvVar", keyEnvVar);
        o.addProperty("luaSelectable", luaSelectable);
        return o;
    }

    /** Reads a profile written by {@link #toJson()} or an operator's providers file entry. Throws on bad input. */
    public static ProviderProfile fromJson(JsonObject o, String fallbackId) {
        var preset = Preset.parse(str(o, "preset", "custom-openai"));
        var protocol = o.has("protocol") ? ProtocolKind.parse(str(o, "protocol", "")) : preset.protocol();
        var baseUrl = o.has("baseUrl") ? str(o, "baseUrl", "") : preset.baseUrl();
        var headers = new LinkedHashMap<String, String>();
        if (o.has("headers") && o.get("headers").isJsonObject()) {
            for (var e : o.getAsJsonObject("headers").entrySet()) headers.put(e.getKey(), e.getValue().getAsString());
        }
        KeyMode keyMode;
        String keyEnv;
        if (o.has("apiKeyEnvVar")) { // operator providers file
            keyEnv = str(o, "apiKeyEnvVar", "");
            keyMode = keyEnv.isEmpty() ? KeyMode.NONE : KeyMode.ENV;
        } else {
            keyMode = KeyMode.valueOf(str(o, "keyMode", "none").toUpperCase(java.util.Locale.ROOT));
            keyEnv = str(o, "keyEnvVar", "");
        }
        return new ProviderProfile(
            str(o, "id", fallbackId), str(o, "name", ""), preset, protocol, baseUrl, str(o, "model", ""),
            o.has("maxTokens") ? o.get("maxTokens").getAsInt() : 8192,
            o.has("effort") ? str(o, "effort", null) : null,
            o.has("refusalFallback") ? o.get("refusalFallback").getAsBoolean() : preset == Preset.ANTHROPIC,
            o.has("useMaxCompletionTokens") && o.get("useMaxCompletionTokens").getAsBoolean(),
            headers, keyMode, keyEnv,
            !o.has("luaSelectable") || o.get("luaSelectable").getAsBoolean()
        ).validated();
    }

    private static String str(JsonObject o, String key, String def) {
        var e = o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }
}
