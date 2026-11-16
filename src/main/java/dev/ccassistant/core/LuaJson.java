package dev.ccassistant.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Converts between Lua values (as CC passes them to Java) and JSON (docs/LUA_API.md, "Request body").
 * <p>
 * Lua strings are byte strings. CC hands them to Java either as a {@link ByteBuffer} or as a String with one char per
 * byte; both are read as UTF-8. Strings going back to Lua are returned as UTF-8 {@link ByteBuffer}s, which CC turns
 * into Lua strings byte for byte.
 */
public final class LuaJson {
    public static final int MAX_DEPTH = 64;
    public static final int MAX_NODES = 200_000;
    /** Keys whose empty table means an empty array rather than an empty object. */
    private static final Set<String> ARRAY_KEYS = Set.of("messages", "content", "tools", "required", "enum", "stop_sequences");

    private LuaJson() {
    }

    public static JsonElement toJson(Object value) throws AiException {
        return new ToJson().convert(value, "", 0);
    }

    private static final class ToJson {
        int nodes;

        JsonElement convert(Object value, String key, int depth) throws AiException {
            if (++nodes > MAX_NODES) throw new AiException(AiException.TOO_LARGE, "The request has too many values");
            if (depth > MAX_DEPTH) throw new AiException(AiException.BAD_REQUEST, "Tables are nested more than " + MAX_DEPTH + " deep");
            if (value == null) return JsonNull.INSTANCE;
            if (value instanceof Boolean b) return new JsonPrimitive(b);
            if (value instanceof Number n) return number(n, key);
            if (value instanceof String s) return new JsonPrimitive(decode(s));
            if (value instanceof ByteBuffer b) return new JsonPrimitive(decode(b));
            if (value instanceof Map<?, ?> map) return table(map, key, depth);
            throw new AiException(AiException.BAD_REQUEST, "Can't send a " + value.getClass().getSimpleName() + (key.isEmpty() ? "" : " in '" + key + "'"));
        }

        private JsonElement table(Map<?, ?> map, String key, int depth) throws AiException {
            if (map.isEmpty()) return ARRAY_KEYS.contains(key) ? new JsonArray() : new JsonObject();
            var n = map.size();
            var isArray = true;
            for (var k : map.keySet()) {
                if (!(k instanceof Number num) || num.doubleValue() != Math.rint(num.doubleValue())
                    || num.doubleValue() < 1 || num.doubleValue() > n) {
                    isArray = false;
                    break;
                }
            }
            if (isArray) {
                var byIndex = new HashMap<Integer, Object>();
                for (var e : map.entrySet()) byIndex.put(((Number) e.getKey()).intValue(), e.getValue());
                var arr = new JsonArray(n);
                for (var i = 1; i <= n; i++) arr.add(convert(byIndex.get(i), key, depth + 1));
                return arr;
            }
            var obj = new JsonObject();
            for (var e : map.entrySet()) {
                String name;
                if (e.getKey() instanceof String s) name = decode(s);
                else if (e.getKey() instanceof ByteBuffer b) name = decode(b);
                else throw new AiException(AiException.BAD_REQUEST, "Table" + (key.isEmpty() ? "" : " '" + key + "'")
                        + " mixes list entries and named fields");
                obj.add(name, convert(e.getValue(), name, depth + 1));
            }
            return obj;
        }

        private static JsonElement number(Number n, String key) throws AiException {
            var d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new AiException(AiException.BAD_REQUEST, "NaN and infinity can't be sent" + (key.isEmpty() ? "" : " ('" + key + "')"));
            }
            if (d == Math.rint(d) && Math.abs(d) <= 9.007199254740992E15) return new JsonPrimitive((long) d);
            return new JsonPrimitive(d);
        }
    }

    /** A String from CC: one char per byte means a Lua byte string, read as UTF-8. */
    public static String decode(String s) {
        for (var i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) return s; // already real text
        }
        return decode(ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1)));
    }

    public static String decode(ByteBuffer bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(bytes.duplicate()).toString();
        } catch (CharacterCodingException e) {
            return "�";
        }
    }

    public static ByteBuffer encode(String s) {
        return StandardCharsets.UTF_8.encode(CharBuffer.wrap(s));
    }

    /** JSON to Lua: objects become string-keyed tables, arrays become 1-based tables, null fields are dropped. */
    public static Object toLua(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) {
                var d = p.getAsDouble();
                return d == Math.rint(d) && Math.abs(d) <= 9.007199254740992E15 ? (Object) (long) d : (Object) d;
            }
            return encode(p.getAsString());
        }
        if (e.isJsonArray()) {
            var out = new HashMap<Object, Object>();
            var i = 1;
            for (var item : e.getAsJsonArray()) {
                var v = toLua(item);
                if (v != null) out.put(i, v);
                i++;
            }
            return out;
        }
        var out = new LinkedHashMap<Object, Object>();
        for (var entry : e.getAsJsonObject().entrySet()) {
            var v = toLua(entry.getValue());
            if (v != null) out.put(entry.getKey(), v);
        }
        return out;
    }
}
