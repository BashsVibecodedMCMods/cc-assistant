package dev.ccassistant.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ccassistant.core.AiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Anthropic Messages (docs/PROVIDERS.md section 4). */
public final class AnthropicProtocol implements Protocol {
    public static final AnthropicProtocol INSTANCE = new AnthropicProtocol();
    public static final String API_VERSION = "2026-10-21";
    public static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final Set<String> STOP_REASONS = Set.of("end_turn", "tool_use", "max_tokens", "refusal", "stop_sequence");

    private AnthropicProtocol() {
    }

    private static Map<String, String> headers(ProviderProfile profile, String key, boolean chat) {
        var h = new LinkedHashMap<String, String>(profile.allHeaders());
        if (!key.isEmpty()) h.put("x-api-key", key);
        h.put("anthropic-version", API_VERSION);
        if (chat && profile.refusalFallback()) h.put("anthropic-beta", FALLBACK_BETA);
        return h;
    }

    @Override
    public HttpSpec chatRequest(ProviderProfile profile, String key, JsonObject request, int maxTokens) {
        var body = new JsonObject();
        body.addProperty("model", profile.model());
        body.addProperty("max_tokens", maxTokens);
        var system = Json.str(request, "system");
        if (system != null && !system.isEmpty()) body.addProperty("system", system);

        var messages = new JsonArray();
        for (var me : request.getAsJsonArray("messages")) {
            var message = me.getAsJsonObject();
            var blocks = new JsonArray();
            for (var be : Json.blocks(message)) {
                var b = be.getAsJsonObject();
                switch (String.valueOf(Json.str(b, "type"))) {
                    case "text" -> {
                        var text = Json.str(b, "text");
                        if (text != null && !text.isEmpty()) blocks.add(Json.textBlock(text));
                    }
                    case "tool_use" -> {
                        var use = new JsonObject();
                        use.addProperty("type", "tool_use");
                        use.addProperty("id", Json.str(b, "id"));
                        use.addProperty("name", Json.str(b, "name"));
                        use.add("input", b.has("input") ? b.get("input") : new JsonObject());
                        blocks.add(use);
                    }
                    case "tool_result" -> {
                        var result = new JsonObject();
                        result.addProperty("type", "tool_result");
                        result.addProperty("tool_use_id", Json.str(b, "tool_use_id"));
                        result.addProperty("content", OpenAiProtocol.toolResultText(b));
                        if (b.has("is_error") && b.get("is_error").getAsBoolean()) result.addProperty("is_error", true);
                        blocks.add(result);
                    }
                    case "reasoning" -> {
                        // Replayed only to the protocol that produced it, exactly as received.
                        var raw = Json.obj(b, "raw");
                        if ("anthropic".equals(Json.str(b, "protocol")) && raw != null) blocks.add(raw);
                    }
                    default -> {
                    }
                }
            }
            if (blocks.isEmpty()) continue;
            var m = new JsonObject();
            m.addProperty("role", Json.str(message, "role"));
            m.add("content", blocks);
            messages.add(m);
        }
        body.add("messages", messages);

        var tools = Json.arr(request, "tools");
        if (tools != null && !tools.isEmpty()) {
            var wire = new JsonArray();
            for (var t : tools) {
                var tool = t.getAsJsonObject();
                var w = new JsonObject();
                w.addProperty("name", Json.str(tool, "name"));
                w.addProperty("description", Json.str(tool, "description"));
                w.add("input_schema", tool.get("input_schema"));
                wire.add(w);
            }
            body.add("tools", wire);
            var choice = Json.str(request, "tool_choice");
            if (choice != null) {
                var c = new JsonObject();
                c.addProperty("type", choice);
                body.add("tool_choice", c);
            }
        }
        var stops = Json.arr(request, "stop_sequences");
        if (stops != null && !stops.isEmpty()) body.add("stop_sequences", stops);
        if (profile.effort() != null) {
            var oc = new JsonObject();
            oc.addProperty("effort", profile.effort());
            body.add("output_config", oc);
        }
        if (profile.refusalFallback()) body.addProperty("fallbacks", "default");
        return new HttpSpec(profile.baseUrl() + "/v1/messages", headers(profile, key, true), body);
    }

    @Override
    public JsonObject parseChat(JsonElement response) throws AiException {
        if (response == null || !response.isJsonObject()) throw bad("response is not a JSON object");
        var root = response.getAsJsonObject();
        var wire = Json.arr(root, "content");
        if (wire == null) {
            var err = errorMessage(root);
            throw bad(err != null ? err : "response has no content");
        }
        var content = new JsonArray();
        for (var e : wire) {
            if (!e.isJsonObject()) continue;
            var b = e.getAsJsonObject();
            var type = String.valueOf(Json.str(b, "type"));
            switch (type) {
                case "text" -> {
                    var text = Json.str(b, "text");
                    if (text != null) content.add(Json.textBlock(text));
                }
                case "tool_use" -> {
                    var use = new JsonObject();
                    use.addProperty("type", "tool_use");
                    use.addProperty("id", Json.str(b, "id"));
                    use.addProperty("name", Json.str(b, "name"));
                    var input = b.get("input");
                    use.add("input", input != null && input.isJsonObject() ? input : new JsonObject());
                    content.add(use);
                }
                default -> {
                    // thinking, redacted_thinking, fallback and anything newer: kept opaque so it can be sent back.
                    var r = new JsonObject();
                    r.addProperty("type", "reasoning");
                    var thinking = Json.str(b, "thinking");
                    r.addProperty("text", thinking == null ? "" : thinking);
                    r.addProperty("protocol", "anthropic");
                    r.add("raw", b);
                    content.add(r);
                }
            }
        }
        var out = new JsonObject();
        out.addProperty("model", Json.str(root, "model"));
        var stop = Json.str(root, "stop_reason");
        out.addProperty("stop_reason", stop != null && STOP_REASONS.contains(stop) ? stop : "other");
        out.add("content", content);
        var usage = Json.obj(root, "usage");
        if (usage != null) {
            var u = new JsonObject();
            var in = Json.num(usage.get("input_tokens"));
            var outTokens = Json.num(usage.get("output_tokens"));
            if (in != null) u.addProperty("input_tokens", in.longValue());
            if (outTokens != null) u.addProperty("output_tokens", outTokens.longValue());
            out.add("usage", u);
        }
        return out;
    }

    @Override
    public HttpSpec modelsRequest(ProviderProfile profile, String key) {
        return new HttpSpec(profile.baseUrl() + "/v1/models?limit=1000", headers(profile, key, false), null);
    }

    @Override
    public List<ModelInfo> parseModels(JsonElement response, ProviderProfile profile) throws AiException {
        var data = response != null && response.isJsonObject() ? Json.arr(response.getAsJsonObject(), "data") : null;
        if (data == null) throw bad("the model list has an unexpected shape");
        var out = new ArrayList<ModelInfo>();
        for (var e : data) {
            if (!e.isJsonObject()) continue;
            var m = e.getAsJsonObject();
            var id = Json.str(m, "id");
            if (id == null || id.isEmpty()) continue;
            var ctx = Json.num(m.get("max_input_tokens"));
            out.add(new ModelInfo(id, Json.str(m, "display_name"), ctx == null ? null : ctx.intValue(), null, null,
                profile.preset() == Preset.ANTHROPIC ? Boolean.TRUE : null));
        }
        return out;
    }

    private static AiException bad(String message) {
        return new AiException(AiException.BAD_RESPONSE, "The provider's reply couldn't be understood: " + message);
    }
}
