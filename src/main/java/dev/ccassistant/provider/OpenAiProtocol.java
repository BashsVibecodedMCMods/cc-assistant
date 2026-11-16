package dev.ccassistant.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ccassistant.core.AiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI Chat Completions, the protocol most providers copy (docs/PROVIDERS.md section 3). */
public final class OpenAiProtocol implements Protocol {
    public static final OpenAiProtocol INSTANCE = new OpenAiProtocol();

    private OpenAiProtocol() {
    }

    private static Map<String, String> headers(ProviderProfile profile, String key) {
        var h = new LinkedHashMap<String, String>(profile.allHeaders());
        if (!key.isEmpty()) h.put("Authorization", "Bearer " + key);
        return h;
    }

    @Override
    public HttpSpec chatRequest(ProviderProfile profile, String key, JsonObject request, int maxTokens) {
        var body = new JsonObject();
        body.addProperty("model", profile.model());
        body.add("messages", toWireMessages(request));
        body.addProperty(profile.useMaxCompletionTokens() ? "max_completion_tokens" : "max_tokens", maxTokens);

        var tools = Json.arr(request, "tools");
        if (tools != null && !tools.isEmpty()) {
            var wireTools = new JsonArray();
            for (var t : tools) {
                var tool = t.getAsJsonObject();
                var fn = new JsonObject();
                fn.addProperty("name", Json.str(tool, "name"));
                fn.addProperty("description", Json.str(tool, "description"));
                fn.add("parameters", tool.get("input_schema"));
                var wrapper = new JsonObject();
                wrapper.addProperty("type", "function");
                wrapper.add("function", fn);
                wireTools.add(wrapper);
            }
            body.add("tools", wireTools);
            var choice = Json.str(request, "tool_choice");
            if (choice != null) body.addProperty("tool_choice", choice);
        }
        var stops = Json.arr(request, "stop_sequences");
        if (stops != null && !stops.isEmpty()) body.add("stop", stops);
        return new HttpSpec(profile.baseUrl() + "/chat/completions", headers(profile, key), body);
    }

    static JsonArray toWireMessages(JsonObject request) {
        var out = new JsonArray();
        var system = Json.str(request, "system");
        if (system != null && !system.isEmpty()) {
            var m = new JsonObject();
            m.addProperty("role", "system");
            m.addProperty("content", system);
            out.add(m);
        }
        for (var me : request.getAsJsonArray("messages")) {
            var message = me.getAsJsonObject();
            var role = Json.str(message, "role");
            var blocks = Json.blocks(message);
            if ("assistant".equals(role)) {
                var text = new StringBuilder();
                var calls = new JsonArray();
                for (var be : blocks) {
                    var b = be.getAsJsonObject();
                    switch (String.valueOf(Json.str(b, "type"))) {
                        case "text" -> appendText(text, Json.str(b, "text"));
                        case "tool_use" -> {
                            var fn = new JsonObject();
                            fn.addProperty("name", Json.str(b, "name"));
                            var input = b.get("input");
                            fn.addProperty("arguments", input == null ? "{}" : input.toString());
                            var call = new JsonObject();
                            call.addProperty("id", Json.str(b, "id"));
                            call.addProperty("type", "function");
                            call.add("function", fn);
                            calls.add(call);
                        }
                        default -> {
                        } // reasoning is never sent back over this protocol
                    }
                }
                if (text.isEmpty() && calls.isEmpty()) continue;
                var m = new JsonObject();
                m.addProperty("role", "assistant");
                if (text.isEmpty()) m.add("content", com.google.gson.JsonNull.INSTANCE);
                else m.addProperty("content", text.toString());
                if (!calls.isEmpty()) m.add("tool_calls", calls);
                out.add(m);
            } else {
                // User turn: tool results become their own "tool" messages, in order; text after them becomes a user
                // message after the tool messages (and text before any result stays before them).
                var text = new StringBuilder();
                for (var be : blocks) {
                    var b = be.getAsJsonObject();
                    var type = String.valueOf(Json.str(b, "type"));
                    if (type.equals("text")) {
                        appendText(text, Json.str(b, "text"));
                    } else if (type.equals("tool_result")) {
                        if (!text.isEmpty()) {
                            out.add(userMessage(text.toString()));
                            text.setLength(0);
                        }
                        var m = new JsonObject();
                        m.addProperty("role", "tool");
                        m.addProperty("tool_call_id", Json.str(b, "tool_use_id"));
                        var content = toolResultText(b);
                        var isError = b.has("is_error") && b.get("is_error").getAsBoolean();
                        m.addProperty("content", isError ? "Error: " + content : content);
                        out.add(m);
                    }
                }
                if (!text.isEmpty()) out.add(userMessage(text.toString()));
            }
        }
        return out;
    }

    static String toolResultText(JsonObject block) {
        var c = block.get("content");
        if (c == null || c.isJsonNull()) return "";
        if (c.isJsonPrimitive()) return c.getAsString();
        var sb = new StringBuilder();
        if (c.isJsonArray()) {
            for (var e : c.getAsJsonArray()) {
                if (e.isJsonObject()) appendText(sb, Json.str(e.getAsJsonObject(), "text"));
            }
        }
        return sb.toString();
    }

    private static void appendText(StringBuilder sb, String text) {
        if (text == null || text.isEmpty()) return;
        if (!sb.isEmpty()) sb.append("\n\n");
        sb.append(text);
    }

    private static JsonObject userMessage(String text) {
        var m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", text);
        return m;
    }

    @Override
    public JsonObject parseChat(JsonElement response) throws AiException {
        if (response == null || !response.isJsonObject()) throw bad("response is not a JSON object");
        var root = response.getAsJsonObject();
        var choices = Json.arr(root, "choices");
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            var err = errorMessage(root);
            throw bad(err != null ? err : "response has no choices");
        }
        var choice = choices.get(0).getAsJsonObject();
        var message = Json.obj(choice, "message");
        if (message == null) throw bad("response has no message");

        var content = new JsonArray();
        var reasoning = Json.str(message, "reasoning_content");
        if (reasoning == null) reasoning = Json.str(message, "reasoning");
        if (reasoning != null && !reasoning.isEmpty()) {
            var r = new JsonObject();
            r.addProperty("type", "reasoning");
            r.addProperty("text", reasoning);
            r.addProperty("protocol", "openai");
            content.add(r);
        }
        var c = message.get("content");
        if (c != null && c.isJsonPrimitive()) {
            if (!c.getAsString().isEmpty()) content.add(Json.textBlock(c.getAsString()));
        } else if (c != null && c.isJsonArray()) {
            var sb = new StringBuilder();
            for (var part : c.getAsJsonArray()) {
                if (part.isJsonObject()) appendText(sb, Json.str(part.getAsJsonObject(), "text"));
            }
            if (!sb.isEmpty()) content.add(Json.textBlock(sb.toString()));
        }
        var calls = Json.arr(message, "tool_calls");
        if (calls != null) {
            int n = 0;
            for (var ce : calls) {
                n++;
                if (!ce.isJsonObject()) continue;
                var call = ce.getAsJsonObject();
                var fn = Json.obj(call, "function");
                var use = new JsonObject();
                use.addProperty("type", "tool_use");
                var id = Json.str(call, "id");
                use.addProperty("id", id == null || id.isEmpty() ? "call_" + n : id);
                use.addProperty("name", fn == null ? "" : String.valueOf(Json.str(fn, "name")));
                var args = fn == null ? null : fn.get("arguments");
                JsonObject input = new JsonObject();
                String inputError = null;
                if (args != null && args.isJsonObject()) {
                    input = args.getAsJsonObject();
                } else if (args != null && args.isJsonPrimitive()) {
                    var text = args.getAsString();
                    if (!text.isBlank()) {
                        try {
                            var parsed = JsonParser.parseString(text);
                            if (parsed.isJsonObject()) input = parsed.getAsJsonObject();
                            else inputError = "tool arguments must be a JSON object";
                        } catch (RuntimeException e) {
                            inputError = "tool arguments are not valid JSON";
                        }
                    }
                }
                use.add("input", input);
                if (inputError != null) use.addProperty("input_error", inputError);
                content.add(use);
            }
        }

        var out = new JsonObject();
        out.addProperty("model", Json.str(root, "model"));
        out.addProperty("stop_reason", stopReason(Json.str(choice, "finish_reason"), calls != null && !calls.isEmpty()));
        out.add("content", content);
        var usage = Json.obj(root, "usage");
        if (usage != null) {
            var u = new JsonObject();
            var in = Json.num(usage.get("prompt_tokens"));
            var outTokens = Json.num(usage.get("completion_tokens"));
            if (in != null) u.addProperty("input_tokens", in.longValue());
            if (outTokens != null) u.addProperty("output_tokens", outTokens.longValue());
            out.add("usage", u);
        }
        return out;
    }

    static String stopReason(String finish, boolean hasCalls) {
        if (finish == null) return hasCalls ? "tool_use" : "end_turn";
        return switch (finish) {
            case "stop" -> hasCalls ? "tool_use" : "end_turn";
            case "tool_calls", "function_call" -> "tool_use";
            case "length" -> "max_tokens";
            case "content_filter" -> "refusal";
            default -> "other";
        };
    }

    @Override
    public HttpSpec modelsRequest(ProviderProfile profile, String key) {
        return new HttpSpec(profile.baseUrl() + "/models" + profile.preset().modelsQuery(), headers(profile, key), null);
    }

    @Override
    public List<ModelInfo> parseModels(JsonElement response, ProviderProfile profile) throws AiException {
        JsonArray data = null;
        if (response != null && response.isJsonObject()) {
            data = Json.arr(response.getAsJsonObject(), "data");
            if (data == null) data = Json.arr(response.getAsJsonObject(), "models");
        } else if (response != null && response.isJsonArray()) {
            data = response.getAsJsonArray();
        }
        if (data == null) throw bad("the model list has an unexpected shape");
        var perToken = profile.preset() == Preset.OPENROUTER;
        var out = new ArrayList<ModelInfo>();
        for (var e : data) {
            if (!e.isJsonObject()) continue;
            var m = e.getAsJsonObject();
            var id = Json.str(m, "id");
            if (id == null || id.isEmpty()) continue;
            out.add(new ModelInfo(id, firstStr(m, "name", "display_name"), contextLength(m),
                perToken ? perMillion(Json.obj(m, "pricing"), "prompt") : null,
                perToken ? perMillion(Json.obj(m, "pricing"), "completion") : null,
                toolSupport(m)));
        }
        return out;
    }

    private static String firstStr(JsonObject o, String... keys) {
        for (var k : keys) {
            var v = Json.str(o, k);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static Integer contextLength(JsonObject m) {
        for (var k : new String[]{"context_length", "context_window", "contextLength", "max_context_length", "max_input_tokens"}) {
            var v = Json.num(m.get(k));
            if (v != null && v > 0) return (int) Math.min(Integer.MAX_VALUE, v.longValue());
        }
        var top = Json.obj(m, "top_provider");
        var v = top == null ? null : Json.num(top.get("context_length"));
        return v != null && v > 0 ? (int) Math.min(Integer.MAX_VALUE, v.longValue()) : null;
    }

    /** OpenRouter: "supported_parameters" lists "tools". Others: a capabilities object with a tool flag, if any. */
    private static Boolean toolSupport(JsonObject m) {
        var params = Json.arr(m, "supported_parameters");
        if (params != null) {
            for (var p : params) if (p.isJsonPrimitive() && p.getAsString().equals("tools")) return true;
            return false;
        }
        var caps = Json.obj(m, "capabilities");
        if (caps != null) {
            for (var k : new String[]{"tool_calling", "function_calling", "tools", "tool_use", "toolCalling", "functionCalling"}) {
                var b = Json.bool(caps.get(k));
                if (b != null) return b;
            }
        }
        return null;
    }

    private static Double perMillion(JsonObject pricing, String key) {
        var v = pricing == null ? null : Json.num(pricing.get(key));
        return v == null || v < 0 ? null : v * 1_000_000;
    }

    private static AiException bad(String message) {
        return new AiException(AiException.BAD_RESPONSE, "The provider's reply couldn't be understood: " + message);
    }
}
