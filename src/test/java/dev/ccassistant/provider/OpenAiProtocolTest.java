package dev.ccassistant.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ccassistant.core.AiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiProtocolTest {
    private static final ProviderProfile NANO = Profiles.of(Preset.NANOGPT, Preset.NANOGPT.baseUrl());

    private static JsonObject req(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void translatesAToolConversation() {
        var spec = OpenAiProtocol.INSTANCE.chatRequest(NANO, "sk-test", req("""
            {"system":"sys","tool_choice":"auto","stop_sequences":["X"],
             "tools":[{"name":"read_file","description":"d","input_schema":{"type":"object","properties":{}}}],
             "messages":[{"role":"user","content":"hi"},
               {"role":"assistant","content":[{"type":"reasoning","text":"hmm","protocol":"openai"},{"type":"text","text":"ok"},
                 {"type":"tool_use","id":"c1","name":"read_file","input":{"path":"a"}},
                 {"type":"tool_use","id":"c2","name":"read_file","input":{"path":"b"}}]},
               {"role":"user","content":[{"type":"tool_result","tool_use_id":"c1","content":"A"},
                 {"type":"tool_result","tool_use_id":"c2","content":"nope","is_error":true},{"type":"text","text":"thanks"}]}]}
            """), 500);
        assertEquals("https://api.nano-gpt.com/api/v1/chat/completions", spec.url());
        assertEquals("Bearer sk-test", spec.headers().get("Authorization"));
        var body = spec.body();
        assertEquals("test-model", body.get("model").getAsString());
        assertEquals(500, body.get("max_tokens").getAsInt());
        assertEquals("auto", body.get("tool_choice").getAsString());
        assertEquals("X", body.getAsJsonArray("stop").get(0).getAsString());
        assertEquals("function", body.getAsJsonArray("tools").get(0).getAsJsonObject().get("type").getAsString());
        var msgs = body.getAsJsonArray("messages");
        assertEquals(6, msgs.size()); // system, user, assistant, tool, tool, user
        assertEquals("system", msgs.get(0).getAsJsonObject().get("role").getAsString());
        var assistant = msgs.get(2).getAsJsonObject();
        assertEquals("ok", assistant.get("content").getAsString()); // reasoning dropped
        var call = assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("{\"path\":\"a\"}", call.getAsJsonObject("function").get("arguments").getAsString());
        assertEquals("tool", msgs.get(3).getAsJsonObject().get("role").getAsString());
        assertEquals("c1", msgs.get(3).getAsJsonObject().get("tool_call_id").getAsString());
        assertEquals("Error: nope", msgs.get(4).getAsJsonObject().get("content").getAsString());
        assertEquals("thanks", msgs.get(5).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void noKeyNoAuthorizationHeaderAndMaxCompletionTokens() {
        var p = new ProviderProfile("i", "o", Preset.OPENAI, ProtocolKind.OPENAI, Preset.OPENAI.baseUrl(), "m", 10, null,
            false, true, java.util.Map.of(), ProviderProfile.KeyMode.NONE, "", true).validated();
        var spec = OpenAiProtocol.INSTANCE.chatRequest(p, "", req("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}"), 10);
        assertFalse(spec.headers().containsKey("Authorization"));
        assertTrue(spec.body().has("max_completion_tokens"));
        assertFalse(spec.body().has("tools"));
        assertFalse(spec.body().has("tool_choice"));
    }

    @Test
    void parsesToolCallsReasoningAndUsage() throws AiException {
        var out = OpenAiProtocol.INSTANCE.parseChat(JsonParser.parseString("""
            {"model":"glm","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"Reading",
              "reasoning_content":"think",
              "tool_calls":[{"id":"x1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"a\\"}"}},
                            {"type":"function","function":{"name":"oops","arguments":"{not json"}}]}}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}
            """));
        assertEquals("glm", out.get("model").getAsString());
        assertEquals("tool_use", out.get("stop_reason").getAsString());
        var c = out.getAsJsonArray("content");
        assertEquals("reasoning", c.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("Reading", c.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("a", c.get(2).getAsJsonObject().getAsJsonObject("input").get("path").getAsString());
        var broken = c.get(3).getAsJsonObject();
        assertEquals("call_2", broken.get("id").getAsString());
        assertTrue(broken.has("input_error"));
        assertEquals(10, out.getAsJsonObject("usage").get("input_tokens").getAsInt());
    }

    @Test
    void mapsFinishReasons() {
        assertEquals("end_turn", OpenAiProtocol.stopReason("stop", false));
        assertEquals("max_tokens", OpenAiProtocol.stopReason("length", false));
        assertEquals("refusal", OpenAiProtocol.stopReason("content_filter", false));
        assertEquals("other", OpenAiProtocol.stopReason("weird", false));
        assertEquals("tool_use", OpenAiProtocol.stopReason(null, true));
    }

    @Test
    void responseWithoutChoicesIsBadResponse() {
        var e = assertThrows(AiException.class, () -> OpenAiProtocol.INSTANCE.parseChat(JsonParser.parseString("{\"error\":{\"message\":\"boom\"}}")));
        assertEquals(AiException.BAD_RESPONSE, e.code());
        assertTrue(e.getMessage().contains("boom"));
    }

    @Test
    void parsesOpenRouterModels() throws AiException {
        var or = Profiles.of(Preset.OPENROUTER, Preset.OPENROUTER.baseUrl());
        var models = OpenAiProtocol.INSTANCE.parseModels(JsonParser.parseString("""
            {"data":[{"id":"z-ai/glm-5.3","name":"GLM 5.3","context_length":200000,
                      "pricing":{"prompt":"0.0000006","completion":"0.0000022"},"supported_parameters":["tools","max_tokens"]},
                     {"id":"old/model","supported_parameters":["max_tokens"]}]}
            """), or);
        assertEquals(2, models.size());
        var glm = models.get(0);
        assertEquals(200000, glm.contextLength());
        assertEquals(0.6, glm.inputPrice(), 1e-9);
        assertEquals(2.2, glm.outputPrice(), 1e-9);
        assertEquals(Boolean.TRUE, glm.tools());
        assertEquals(Boolean.FALSE, models.get(1).tools());
    }

    @Test
    void parsesPlainModelLists() throws AiException {
        var models = OpenAiProtocol.INSTANCE.parseModels(JsonParser.parseString(
            "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-x\",\"object\":\"model\"},{\"id\":\"cap\",\"capabilities\":{\"tool_calling\":true}}]}"), NANO);
        assertNull(models.get(0).tools());
        assertNull(models.get(0).inputPrice());
        assertEquals(Boolean.TRUE, models.get(1).tools());
        assertEquals("https://api.nano-gpt.com/api/v1/models?detailed=true", OpenAiProtocol.INSTANCE.modelsRequest(NANO, "k").url());
    }
}
