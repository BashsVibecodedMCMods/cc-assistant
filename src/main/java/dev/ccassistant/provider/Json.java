package dev.ccassistant.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

/** Small null-tolerant Gson helpers. */
public final class Json {
    private Json() {
    }

    public static @Nullable String str(@Nullable JsonObject o, String key) {
        if (o == null) return null;
        var e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    public static @Nullable JsonObject obj(@Nullable JsonObject o, String key) {
        if (o == null) return null;
        var e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static @Nullable JsonArray arr(@Nullable JsonObject o, String key) {
        if (o == null) return null;
        var e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    public static @Nullable Double num(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        try {
            if (p.isNumber()) return p.getAsDouble();
            if (p.isString()) return Double.parseDouble(p.getAsString().trim());
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    public static @Nullable Boolean bool(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        var p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isString()) {
            var s = p.getAsString();
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return null;
    }

    public static JsonObject textBlock(String text) {
        var b = new JsonObject();
        b.addProperty("type", "text");
        b.addProperty("text", text);
        return b;
    }

    /** The content of a message as an array of blocks (a plain string becomes one text block). */
    public static JsonArray blocks(JsonObject message) {
        var c = message.get("content");
        if (c != null && c.isJsonArray()) return c.getAsJsonArray();
        var a = new JsonArray();
        if (c != null && c.isJsonPrimitive()) a.add(textBlock(c.getAsString()));
        return a;
    }
}
