package dev.ccassistant.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/**
 * Checks a response in the mod's format that came from a player's client, which may be modified. Returns a copy with
 * only known fields, so nothing unexpected reaches Lua.
 */
public final class ResponseValidator {
    private static final Set<String> STOP = Set.of("end_turn", "tool_use", "max_tokens", "refusal", "stop_sequence", "other");
    private static final int MAX_BLOCKS = 256;

    private ResponseValidator() {
    }

    public static JsonObject check(JsonElement input) throws AiException {
        if (input == null || !input.isJsonObject()) throw bad();
        var in = input.getAsJsonObject();
        var out = new JsonObject();
        var model = str(in, "model");
        if (model != null) out.addProperty("model", model.length() > 200 ? model.substring(0, 200) : model);
        var stop = str(in, "stop_reason");
        out.addProperty("stop_reason", stop != null && STOP.contains(stop) ? stop : "other");
        var content = in.get("content");
        if (content == null || !content.isJsonArray() || content.getAsJsonArray().size() > MAX_BLOCKS) throw bad();
        var blocks = new JsonArray();
        for (var e : content.getAsJsonArray()) {
            if (!e.isJsonObject()) throw bad();
            var b = e.getAsJsonObject();
            var type = str(b, "type");
            var o = new JsonObject();
            o.addProperty("type", type);
            switch (type == null ? "" : type) {
                case "text" -> o.addProperty("text", req(b, "text"));
                case "tool_use" -> {
                    o.addProperty("id", req(b, "id"));
                    o.addProperty("name", req(b, "name"));
                    var input2 = b.get("input");
                    o.add("input", input2 != null && input2.isJsonObject() ? input2 : new JsonObject());
                    var err = str(b, "input_error");
                    if (err != null) o.addProperty("input_error", err);
                }
                case "reasoning" -> {
                    var text = str(b, "text");
                    o.addProperty("text", text == null ? "" : text);
                    o.addProperty("protocol", req(b, "protocol"));
                    var raw = b.get("raw");
                    if (raw != null && raw.isJsonObject()) o.add("raw", raw);
                }
                default -> throw bad();
            }
            blocks.add(o);
        }
        out.add("content", blocks);
        var usage = in.get("usage");
        if (usage != null && usage.isJsonObject()) {
            var u = new JsonObject();
            for (var key : new String[]{"input_tokens", "output_tokens"}) {
                var v = usage.getAsJsonObject().get(key);
                if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsDouble() >= 0) {
                    u.addProperty(key, v.getAsLong());
                }
            }
            out.add("usage", u);
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        var v = o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
    }

    private static String req(JsonObject o, String key) throws AiException {
        var v = str(o, key);
        if (v == null) throw bad();
        return v;
    }

    private static AiException bad() {
        return new AiException(AiException.BAD_RESPONSE, "The owner's client sent a reply that couldn't be understood");
    }
}
