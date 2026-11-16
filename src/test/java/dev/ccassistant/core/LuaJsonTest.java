package dev.ccassistant.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LuaJsonTest {
    private static Map<Object, Object> table(Object... kv) {
        var m = new LinkedHashMap<Object, Object>();
        for (var i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void sequentialNumberKeysBecomeArrays() throws AiException {
        var json = LuaJson.toJson(table(1.0, "a", 2.0, "b"));
        assertEquals("[\"a\",\"b\"]", json.toString());
    }

    @Test
    void sparseOrMixedKeysAreObjectsOrErrors() throws AiException {
        assertEquals("{\"x\":1}", LuaJson.toJson(table("x", 1.0)).toString());
        var e = assertThrows(AiException.class, () -> LuaJson.toJson(table(1.0, "a", "x", "b")));
        assertEquals(AiException.BAD_REQUEST, e.code());
        // {[1]="a", [3]="c"} is not a sequence and has non-string keys
        assertThrows(AiException.class, () -> LuaJson.toJson(table(1.0, "a", 3.0, "c")));
    }

    @Test
    void emptyTablesDependOnTheirKey() throws AiException {
        var json = LuaJson.toJson(table("messages", new HashMap<>(), "properties", new HashMap<>(), "required", new HashMap<>()));
        assertEquals(JsonParser.parseString("{\"messages\":[],\"properties\":{},\"required\":[]}"), json);
    }

    @Test
    void numbers() throws AiException {
        assertEquals("3", LuaJson.toJson(3.0).toString());
        assertEquals("2.5", LuaJson.toJson(2.5).toString());
        assertThrows(AiException.class, () -> LuaJson.toJson(Double.NaN));
        assertThrows(AiException.class, () -> LuaJson.toJson(Double.POSITIVE_INFINITY));
    }

    @Test
    void luaByteStringsAreReadAsUtf8() throws AiException {
        var utf8 = "héllo ☃".getBytes(StandardCharsets.UTF_8);
        var asCcString = new String(utf8, StandardCharsets.ISO_8859_1); // how CC passes a byte string
        assertEquals("héllo ☃", LuaJson.toJson(asCcString).getAsString());
        assertEquals("héllo ☃", LuaJson.toJson(ByteBuffer.wrap(utf8)).getAsString());
        // invalid UTF-8 becomes U+FFFD instead of failing
        assertEquals("a�b", LuaJson.toJson(new String(new byte[]{'a', (byte) 0xFF, 'b'}, StandardCharsets.ISO_8859_1)).getAsString());
    }

    @Test
    void stringsGoBackToLuaAsUtf8Bytes() {
        var lua = (ByteBuffer) LuaJson.toLua(JsonParser.parseString("\"☃\""));
        var bytes = new byte[lua.remaining()];
        lua.get(bytes);
        assertArrayEquals("☃".getBytes(StandardCharsets.UTF_8), bytes);
    }

    @Test
    void jsonToLuaShapes() {
        @SuppressWarnings("unchecked")
        var m = (Map<Object, Object>) LuaJson.toLua(JsonParser.parseString("{\"a\":[true,null,2],\"b\":null,\"c\":1.5}"));
        assertFalse(m.containsKey("b"));
        assertEquals(1.5, m.get("c"));
        @SuppressWarnings("unchecked")
        var a = (Map<Object, Object>) m.get("a");
        assertEquals(true, a.get(1));
        assertFalse(a.containsKey(2));
        assertEquals(2L, a.get(3));
    }

    @Test
    void depthIsLimited() {
        Object v = "x";
        for (var i = 0; i < LuaJson.MAX_DEPTH + 2; i++) v = table("k", v);
        final var deep = v;
        assertThrows(AiException.class, () -> LuaJson.toJson(deep));
    }
}
