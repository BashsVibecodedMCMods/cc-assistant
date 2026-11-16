package dev.ccassistant.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestValidatorTest {
    private static String code(String json) {
        var e = assertThrows(AiException.class, () -> RequestValidator.validate(JsonParser.parseString(json), 1 << 20));
        return e.code();
    }

    @Test
    void acceptsAFullToolConversation() throws AiException {
        var out = RequestValidator.validate(JsonParser.parseString("""
            {"system":"s","max_tokens":100,"tool_choice":"auto","stop_sequences":["END"],"profile":"nano",
             "tools":[{"name":"read_file","description":"d","input_schema":{"type":"object","properties":{}}}],
             "messages":[{"role":"user","content":"hi"},
               {"role":"assistant","content":[{"type":"reasoning","text":"","protocol":"anthropic","raw":{"type":"thinking"}},
                 {"type":"text","text":"ok"},{"type":"tool_use","id":"c1","name":"read_file","input":{"path":"a"}}]},
               {"role":"user","content":[{"type":"tool_result","tool_use_id":"c1","content":"data","is_error":false}]}]}
            """), 1 << 20);
        assertEquals(3, out.getAsJsonArray("messages").size());
        assertEquals("nano", out.get("profile").getAsString());
        // is_error=false is normalised away
        assertFalse(out.getAsJsonArray("messages").get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().has("is_error"));
    }

    @Test
    void rejectsUnknownAndCostControlledFields() {
        assertEquals(AiException.BAD_REQUEST, code("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"model\":\"gpt\"}"));
        assertEquals(AiException.BAD_REQUEST, code("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"temperature\":1}"));
    }

    @Test
    void rejectsBadShapes() {
        assertEquals(AiException.BAD_REQUEST, code("{\"messages\":[]}"));
        assertEquals(AiException.BAD_REQUEST, code("{\"messages\":[{\"role\":\"system\",\"content\":\"x\"}]}"));
        assertEquals(AiException.BAD_REQUEST, code("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\"}]}]}"));
        assertEquals(AiException.BAD_REQUEST, code("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"b\"}]}]}"));
        assertEquals(AiException.BAD_REQUEST, code("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"tool_choice\":\"any\"}"));
        assertEquals(AiException.BAD_REQUEST, code("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"tools\":[{\"name\":\"bad name\"}]}"));
        assertEquals(AiException.BAD_REQUEST, code("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"tools\":[{\"name\":\"t\",\"type\":\"web_search_20260209\"}]}"));
        assertEquals(AiException.BAD_REQUEST, code("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"max_tokens\":0}"));
        assertEquals(AiException.BAD_PROFILE, code("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"profile\":\"a b\"}"));
    }

    @Test
    void enforcesSize() {
        var big = "x".repeat(2000);
        var e = assertThrows(AiException.class, () -> RequestValidator.validate(
            JsonParser.parseString("{\"messages\":[{\"role\":\"user\",\"content\":\"" + big + "\"}]}"), 1000));
        assertEquals(AiException.TOO_LARGE, e.code());
    }
}
