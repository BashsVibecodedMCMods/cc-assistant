package dev.ccassistant.provider;

import com.google.gson.JsonParser;
import dev.ccassistant.core.AiException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicProtocolTest {
    private static final ProviderProfile CLAUDE = new ProviderProfile("i", "claude", Preset.ANTHROPIC, ProtocolKind.ANTHROPIC,
        Preset.ANTHROPIC.baseUrl(), "claude-opus-5-5", 8192, "medium", true, false, Map.of(),
        ProviderProfile.KeyMode.ENV, "K", true).validated();

    @Test
    void buildsRequestWithReplayedThinking() {
        var spec = AnthropicProtocol.INSTANCE.chatRequest(CLAUDE, "sk-ant", JsonParser.parseString("""
            {"system":"sys","tool_choice":"none","tools":[{"name":"t","description":"d","input_schema":{"type":"object"}}],
             "messages":[{"role":"user","content":"hi"},
               {"role":"assistant","content":[{"type":"reasoning","text":"","protocol":"anthropic","raw":{"type":"thinking","thinking":"","signature":"sig"}},
                 {"type":"reasoning","text":"x","protocol":"openai"},
                 {"type":"tool_use","id":"t1","name":"t","input":{}}]},
               {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"r","is_error":true}]}]}
            """).getAsJsonObject(), 777);
        assertEquals("https://api.anthropic.com/v1/messages", spec.url());
        assertEquals("sk-ant", spec.headers().get("x-api-key"));
        assertEquals(AnthropicProtocol.API_VERSION, spec.headers().get("anthropic-version"));
        assertEquals(AnthropicProtocol.FALLBACK_BETA, spec.headers().get("anthropic-beta"));
        var body = spec.body();
        assertEquals(777, body.get("max_tokens").getAsInt());
        assertEquals("default", body.get("fallbacks").getAsString());
        assertEquals("medium", body.getAsJsonObject("output_config").get("effort").getAsString());
        assertEquals("none", body.getAsJsonObject("tool_choice").get("type").getAsString());
        var assistant = body.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content");
        assertEquals(2, assistant.size()); // anthropic thinking replayed, openai reasoning dropped
        assertEquals("sig", assistant.get(0).getAsJsonObject().get("signature").getAsString());
        var result = body.getAsJsonArray("messages").get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
        assertTrue(result.get("is_error").getAsBoolean());
    }

    @Test
    void parsesResponseKeepingOpaqueBlocks() throws AiException {
        var out = AnthropicProtocol.INSTANCE.parseChat(JsonParser.parseString("""
            {"model":"claude-opus-5-5","stop_reason":"pause_turn","usage":{"input_tokens":3,"output_tokens":4},
             "content":[{"type":"thinking","thinking":"","signature":"s"},{"type":"text","text":"hi"},
                        {"type":"tool_use","id":"t","name":"n","input":{"a":1}}]}
            """));
        assertEquals("other", out.get("stop_reason").getAsString());
        var c = out.getAsJsonArray("content");
        assertEquals("reasoning", c.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("s", c.get(0).getAsJsonObject().getAsJsonObject("raw").get("signature").getAsString());
        assertEquals("hi", c.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals(1, c.get(2).getAsJsonObject().getAsJsonObject("input").get("a").getAsInt());
        assertEquals(4, out.getAsJsonObject("usage").get("output_tokens").getAsInt());
    }

    @Test
    void customAnthropicDefaultsToNoFallback() {
        var p = ProviderProfile.fromJson(JsonParser.parseString(
            "{\"name\":\"n\",\"preset\":\"custom-anthropic\",\"baseUrl\":\"https://api.nano-gpt.com/api\",\"model\":\"m\"}").getAsJsonObject(), "x");
        assertFalse(p.refusalFallback());
        var spec = AnthropicProtocol.INSTANCE.chatRequest(p, "", JsonParser.parseString(
            "{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}").getAsJsonObject(), 5);
        assertEquals("https://api.nano-gpt.com/api/v1/messages", spec.url());
        assertFalse(spec.headers().containsKey("anthropic-beta"));
        assertFalse(spec.body().has("fallbacks"));
    }

    @Test
    void parsesModels() throws AiException {
        var models = AnthropicProtocol.INSTANCE.parseModels(JsonParser.parseString(
            "{\"data\":[{\"id\":\"claude-opus-5-5\",\"display_name\":\"Claude Opus 5.5\",\"max_input_tokens\":1000000}],\"has_more\":false}"), CLAUDE);
        assertEquals("Claude Opus 5.5", models.get(0).name());
        assertEquals(Boolean.TRUE, models.get(0).tools());
    }
}
