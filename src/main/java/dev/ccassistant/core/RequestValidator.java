package dev.ccassistant.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a request in the mod's format (docs/LUA_API.md, "Request body") and returns a normalised copy that holds only
 * known fields. Used by the server on input from Lua and again by the player's client on input from the server.
 */
public final class RequestValidator {
    public static final int MAX_MESSAGES = 500;
    public static final int MAX_BLOCKS = 100;
    public static final int MAX_TOOLS = 64;
    public static final int MAX_STOP_SEQUENCES = 4;
    public static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    public static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Set<String> FIELDS = Set.of("messages", "system", "tools", "tool_choice", "max_tokens", "stop_sequences", "profile");

    private RequestValidator() {
    }

    public static JsonObject validate(JsonElement input, int maxBytes) throws AiException {
        if (input == null || !input.isJsonObject()) throw bad("The request must be a table");
        var in = input.getAsJsonObject();
        var size = in.toString().getBytes(StandardCharsets.UTF_8).length;
        if (size > maxBytes) throw new AiException(AiException.TOO_LARGE, "The request is " + size + " bytes; the limit is " + maxBytes);
        for (var key : in.keySet()) {
            if (!FIELDS.contains(key)) throw bad("Unknown field '" + key + "'. Allowed: messages, system, tools, tool_choice, max_tokens, stop_sequences, profile");
        }
        var out = new JsonObject();

        var messages = in.get("messages");
        if (messages == null || !messages.isJsonArray() || messages.getAsJsonArray().isEmpty()) {
            throw bad("'messages' must be a non-empty list");
        }
        if (messages.getAsJsonArray().size() > MAX_MESSAGES) {
            throw new AiException(AiException.TOO_LARGE, "More than " + MAX_MESSAGES + " messages");
        }
        var outMessages = new JsonArray();
        var i = 0;
        for (var m : messages.getAsJsonArray()) outMessages.add(message(m, ++i));
        out.add("messages", outMessages);

        var system = in.get("system");
        if (system != null && !system.isJsonNull()) out.addProperty("system", string(system, "system"));

        var tools = in.get("tools");
        if (tools != null && !tools.isJsonNull()) {
            if (!tools.isJsonArray()) throw bad("'tools' must be a list");
            if (tools.getAsJsonArray().size() > MAX_TOOLS) throw new AiException(AiException.TOO_LARGE, "More than " + MAX_TOOLS + " tools");
            var outTools = new JsonArray();
            var names = new java.util.HashSet<String>();
            var t = 0;
            for (var tool : tools.getAsJsonArray()) {
                var checked = tool(tool, ++t);
                if (!names.add(checked.get("name").getAsString())) throw bad("Two tools are named '" + checked.get("name").getAsString() + "'");
                outTools.add(checked);
            }
            if (!outTools.isEmpty()) out.add("tools", outTools);
        }

        var choice = in.get("tool_choice");
        if (choice != null && !choice.isJsonNull()) {
            var c = string(choice, "tool_choice");
            if (!c.equals("auto") && !c.equals("none")) throw bad("'tool_choice' must be \"auto\" or \"none\"");
            out.addProperty("tool_choice", c);
        }

        var maxTokens = in.get("max_tokens");
        if (maxTokens != null && !maxTokens.isJsonNull()) {
            if (!isInteger(maxTokens) || maxTokens.getAsLong() < 1) throw bad("'max_tokens' must be a positive integer");
            out.addProperty("max_tokens", Math.min(maxTokens.getAsLong(), Integer.MAX_VALUE));
        }

        var stops = in.get("stop_sequences");
        if (stops != null && !stops.isJsonNull()) {
            if (!stops.isJsonArray() || stops.getAsJsonArray().size() > MAX_STOP_SEQUENCES) {
                throw bad("'stop_sequences' must be a list of at most " + MAX_STOP_SEQUENCES + " strings");
            }
            var outStops = new JsonArray();
            for (var s : stops.getAsJsonArray()) {
                var str = string(s, "stop_sequences");
                if (str.isEmpty()) throw bad("Stop sequences can't be empty");
                outStops.add(str);
            }
            if (!outStops.isEmpty()) out.add("stop_sequences", outStops);
        }

        var profile = in.get("profile");
        if (profile != null && !profile.isJsonNull()) {
            var p = string(profile, "profile");
            if (!PROFILE_NAME.matcher(p).matches()) throw new AiException(AiException.BAD_PROFILE, "'" + p + "' is not a valid profile name");
            out.addProperty("profile", p);
        }
        return out;
    }

    private static JsonObject message(JsonElement e, int index) throws AiException {
        var where = "message " + index;
        if (!e.isJsonObject()) throw bad(where + " must be a table");
        var m = e.getAsJsonObject();
        for (var key : m.keySet()) {
            if (!key.equals("role") && !key.equals("content")) throw bad(where + " has unknown field '" + key + "'");
        }
        var role = m.get("role");
        var r = role == null ? "" : string(role, where + " role");
        if (!r.equals("user") && !r.equals("assistant")) throw bad(where + " role must be \"user\" or \"assistant\"");
        var out = new JsonObject();
        out.addProperty("role", r);
        var content = m.get("content");
        if (content == null || content.isJsonNull()) throw bad(where + " has no content");
        if (content.isJsonPrimitive()) {
            out.addProperty("content", string(content, where + " content"));
            return out;
        }
        if (!content.isJsonArray()) throw bad(where + " content must be a string or a list of blocks");
        var blocks = content.getAsJsonArray();
        if (blocks.size() > MAX_BLOCKS) throw new AiException(AiException.TOO_LARGE, where + " has more than " + MAX_BLOCKS + " blocks");
        var outBlocks = new JsonArray();
        var b = 0;
        for (var block : blocks) outBlocks.add(block(block, r, where + " block " + (++b)));
        out.add("content", outBlocks);
        return out;
    }

    private static JsonObject block(JsonElement e, String role, String where) throws AiException {
        if (!e.isJsonObject()) throw bad(where + " must be a table");
        var b = e.getAsJsonObject();
        var type = b.has("type") ? string(b.get("type"), where + " type") : "";
        var out = new JsonObject();
        out.addProperty("type", type);
        switch (type) {
            case "text" -> {
                only(b, where, "type", "text");
                out.addProperty("text", string(require(b, "text", where), where + " text"));
            }
            case "tool_use" -> {
                if (!role.equals("assistant")) throw bad(where + ": tool_use blocks belong in assistant messages");
                only(b, where, "type", "id", "name", "input", "input_error");
                out.addProperty("id", nonEmpty(require(b, "id", where), where + " id"));
                out.addProperty("name", nonEmpty(require(b, "name", where), where + " name"));
                var input = b.get("input");
                if (input == null || input.isJsonNull()) input = new JsonObject();
                if (!input.isJsonObject()) throw bad(where + " input must be a table");
                out.add("input", input);
            }
            case "tool_result" -> {
                if (!role.equals("user")) throw bad(where + ": tool_result blocks belong in user messages");
                only(b, where, "type", "tool_use_id", "content", "is_error");
                out.addProperty("tool_use_id", nonEmpty(require(b, "tool_use_id", where), where + " tool_use_id"));
                var content = b.get("content");
                if (content == null || content.isJsonNull()) {
                    out.addProperty("content", "");
                } else if (content.isJsonPrimitive()) {
                    out.addProperty("content", string(content, where + " content"));
                } else if (content.isJsonArray()) {
                    var parts = new JsonArray();
                    for (var part : content.getAsJsonArray()) {
                        if (!part.isJsonObject() || !"text".equals(str(part.getAsJsonObject(), "type"))) {
                            throw bad(where + " content may only hold text blocks");
                        }
                        parts.add(block(part, role, where + " content"));
                    }
                    out.add("content", parts);
                } else {
                    throw bad(where + " content must be a string or a list of text blocks");
                }
                var isError = b.get("is_error");
                if (isError != null && !isError.isJsonNull()) {
                    if (!isError.isJsonPrimitive() || !isError.getAsJsonPrimitive().isBoolean()) throw bad(where + " is_error must be a boolean");
                    if (isError.getAsBoolean()) out.addProperty("is_error", true);
                }
            }
            case "reasoning" -> {
                if (!role.equals("assistant")) throw bad(where + ": reasoning blocks belong in assistant messages");
                only(b, where, "type", "text", "protocol", "raw");
                var text = b.get("text");
                out.addProperty("text", text == null || text.isJsonNull() ? "" : string(text, where + " text"));
                out.addProperty("protocol", string(require(b, "protocol", where), where + " protocol"));
                var raw = b.get("raw");
                if (raw != null && !raw.isJsonNull()) {
                    if (!raw.isJsonObject()) throw bad(where + " raw must be a table");
                    out.add("raw", raw);
                }
            }
            default -> throw bad(where + " has unknown type '" + type + "' (use text, tool_use, tool_result or reasoning)");
        }
        return out;
    }

    private static JsonObject tool(JsonElement e, int index) throws AiException {
        var where = "tool " + index;
        if (!e.isJsonObject()) throw bad(where + " must be a table");
        var t = e.getAsJsonObject();
        only(t, where, "name", "description", "input_schema");
        var name = string(require(t, "name", where), where + " name");
        if (!TOOL_NAME.matcher(name).matches()) throw bad(where + " name '" + name + "' must be 1-64 letters, digits, _ or -");
        var out = new JsonObject();
        out.addProperty("name", name);
        var desc = t.get("description");
        out.addProperty("description", desc == null || desc.isJsonNull() ? "" : string(desc, where + " description"));
        var schema = t.get("input_schema");
        if (schema == null || schema.isJsonNull()) {
            schema = new JsonObject();
            schema.getAsJsonObject().addProperty("type", "object");
            schema.getAsJsonObject().add("properties", new JsonObject());
        }
        if (!schema.isJsonObject() || !"object".equals(str(schema.getAsJsonObject(), "type"))) {
            throw bad(where + " input_schema must be a table with type = \"object\"");
        }
        out.add("input_schema", schema);
        return out;
    }

    private static void only(JsonObject o, String where, String... allowed) throws AiException {
        outer:
        for (var key : o.keySet()) {
            for (var a : allowed) if (a.equals(key)) continue outer;
            throw bad(where + " has unknown field '" + key + "'");
        }
    }

    private static JsonElement require(JsonObject o, String key, String where) throws AiException {
        var v = o.get(key);
        if (v == null || v.isJsonNull()) throw bad(where + " needs '" + key + "'");
        return v;
    }

    private static String string(JsonElement e, String what) throws AiException {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw bad(what + " must be a string");
        return e.getAsString();
    }

    private static String nonEmpty(JsonElement e, String what) throws AiException {
        var s = string(e, what);
        if (s.isEmpty() || s.length() > 256) throw bad(what + " must be 1-256 characters");
        return s;
    }

    private static String str(JsonObject o, String key) {
        var v = o.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }

    private static boolean isInteger(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
        var d = e.getAsDouble();
        return d == Math.rint(d);
    }

    private static AiException bad(String message) {
        return new AiException(AiException.BAD_REQUEST, message);
    }
}
