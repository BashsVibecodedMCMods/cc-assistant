package dev.ccassistant.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ccassistant.core.AiException;

import java.util.List;

/**
 * A wire protocol. Requests and responses on the Lua side are always in the mod's own format (docs/PROVIDERS.md
 * section 2); a protocol translates to and from its provider's format.
 */
public interface Protocol {
    /**
     * Builds the chat request. {@code request} is a validated request in the mod's format; {@code maxTokens} is the
     * final cap. {@code key} may be empty for local servers.
     */
    HttpSpec chatRequest(ProviderProfile profile, String key, JsonObject request, int maxTokens);

    /** Converts a successful provider response into the mod's format. */
    JsonObject parseChat(JsonElement response) throws AiException;

    HttpSpec modelsRequest(ProviderProfile profile, String key);

    List<ModelInfo> parseModels(JsonElement response, ProviderProfile profile) throws AiException;

    /** Pulls a short human-readable message out of an error body, or null. */
    default String errorMessage(JsonElement body) {
        if (body == null || !body.isJsonObject()) return null;
        var o = body.getAsJsonObject();
        var err = o.get("error");
        if (err != null && err.isJsonObject()) {
            var e = err.getAsJsonObject();
            var type = e.has("type") && e.get("type").isJsonPrimitive() ? e.get("type").getAsString() : null;
            var msg = e.has("message") && e.get("message").isJsonPrimitive() ? e.get("message").getAsString() : null;
            if (msg != null) return type != null ? type + ": " + msg : msg;
            if (type != null) return type;
        } else if (err != null && err.isJsonPrimitive()) {
            return err.getAsString();
        }
        if (o.has("message") && o.get("message").isJsonPrimitive()) return o.get("message").getAsString();
        if (o.has("detail") && o.get("detail").isJsonPrimitive()) return o.get("detail").getAsString();
        return null;
    }
}
