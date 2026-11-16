package dev.ccassistant.provider;

import java.util.Locale;
import java.util.Map;

/** Built-in provider presets (docs/PROVIDERS.md section 1). */
public enum Preset {
    ANTHROPIC("anthropic", "Anthropic", ProtocolKind.ANTHROPIC, "https://api.anthropic.com", true, "", Map.of()),
    OPENAI("openai", "OpenAI", ProtocolKind.OPENAI, "https://api.openai.com/v1", true, "", Map.of()),
    OPENROUTER("openrouter", "OpenRouter", ProtocolKind.OPENAI, "https://openrouter.ai/api/v1", true, "",
        Map.of("X-OpenRouter-Title", "CC Terminal Assistant")),
    NANOGPT("nanogpt", "NanoGPT", ProtocolKind.OPENAI, "https://api.nano-gpt.com/api/v1", true, "?detailed=true", Map.of()),
    ZAI("zai", "Z.AI (pay-as-you-go API)", ProtocolKind.OPENAI, "https://api.z.ai/api/paas/v4", true, "", Map.of()),
    OLLAMA("ollama", "Ollama (local)", ProtocolKind.OPENAI, "http://localhost:11434/v1", false, "", Map.of()),
    LMSTUDIO("lmstudio", "LM Studio (local)", ProtocolKind.OPENAI, "http://localhost:1234/v1", false, "", Map.of()),
    CUSTOM_OPENAI("custom-openai", "Custom (OpenAI-compatible)", ProtocolKind.OPENAI, "", false, "", Map.of()),
    CUSTOM_ANTHROPIC("custom-anthropic", "Custom (Anthropic-compatible)", ProtocolKind.ANTHROPIC, "", false, "", Map.of());

    private final String id;
    private final String displayName;
    private final ProtocolKind protocol;
    private final String baseUrl;
    private final boolean keyRequired;
    private final String modelsQuery;
    private final Map<String, String> fixedHeaders;

    Preset(String id, String displayName, ProtocolKind protocol, String baseUrl, boolean keyRequired, String modelsQuery,
           Map<String, String> fixedHeaders) {
        this.id = id;
        this.displayName = displayName;
        this.protocol = protocol;
        this.baseUrl = baseUrl;
        this.keyRequired = keyRequired;
        this.modelsQuery = modelsQuery;
        this.fixedHeaders = fixedHeaders;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public ProtocolKind protocol() {
        return protocol;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean keyRequired() {
        return keyRequired;
    }

    public String modelsQuery() {
        return modelsQuery;
    }

    public Map<String, String> fixedHeaders() {
        return fixedHeaders;
    }

    public boolean isCustom() {
        return this == CUSTOM_OPENAI || this == CUSTOM_ANTHROPIC;
    }

    public static Preset parse(String id) {
        for (var p : values()) if (p.id.equals(id == null ? "" : id.toLowerCase(Locale.ROOT))) return p;
        throw new IllegalArgumentException("Unknown preset '" + id + "'");
    }
}
