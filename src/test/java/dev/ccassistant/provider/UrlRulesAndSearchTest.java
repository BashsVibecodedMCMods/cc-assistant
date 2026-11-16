package dev.ccassistant.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UrlRulesAndSearchTest {
    @Test
    void baseUrlRules() {
        assertEquals("https://x.example/v1", UrlRules.checkBaseUrl("https://x.example/v1/"));
        assertEquals("http://localhost:11434/v1", UrlRules.checkBaseUrl("http://localhost:11434/v1"));
        assertDoesNotThrow(() -> UrlRules.checkBaseUrl("http://127.0.0.1:1234/v1"));
        assertDoesNotThrow(() -> UrlRules.checkBaseUrl("http://[::1]:8080"));
        assertThrows(IllegalArgumentException.class, () -> UrlRules.checkBaseUrl("http://example.com/v1"));
        assertThrows(IllegalArgumentException.class, () -> UrlRules.checkBaseUrl("http://127.0.0.1.evil.com/v1"));
        assertThrows(IllegalArgumentException.class, () -> UrlRules.checkBaseUrl("ftp://x.example"));
        assertThrows(IllegalArgumentException.class, () -> UrlRules.checkBaseUrl("https://user:pw@x.example"));
        assertThrows(IllegalArgumentException.class, () -> UrlRules.checkBaseUrl("https://x.example/v1?key=1"));
    }

    @Test
    void headerRules() {
        assertThrows(IllegalArgumentException.class, () -> UrlRules.checkHeaderName("Authorization"));
        assertThrows(IllegalArgumentException.class, () -> UrlRules.checkHeaderName("x-API-key"));
        assertThrows(IllegalArgumentException.class, () -> UrlRules.checkHeaderValue("a\nb"));
        assertDoesNotThrow(() -> UrlRules.checkHeaderName("X-Title"));
    }

    @Test
    void searchMatchesAllWordsAndRanks() {
        var models = List.of(
            new ModelInfo("z-ai/glm-5.3", "GLM 5.3", null, null, null, true),
            new ModelInfo("glm", "plain", null, null, null, null),
            new ModelInfo("glm-4-9b", "GLM-4", null, null, null, false),
            new ModelInfo("claude-opus-5-5", "Claude Opus 5.5", null, null, null, true));
        var r = ModelSearch.filter(models, "glm", false);
        assertEquals(List.of("glm", "glm-4-9b", "z-ai/glm-5.3"), r.stream().map(ModelInfo::id).toList());
        assertEquals(List.of("glm", "z-ai/glm-5.3"), ModelSearch.filter(models, "GLM", true).stream().map(ModelInfo::id).toList());
        assertEquals(List.of("claude-opus-5-5"), ModelSearch.filter(models, "opus claude", false).stream().map(ModelInfo::id).toList());
        assertEquals(4, ModelSearch.filter(models, "", false).size());
    }
}
