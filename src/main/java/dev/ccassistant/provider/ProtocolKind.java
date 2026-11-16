package dev.ccassistant.provider;

import java.util.Locale;

public enum ProtocolKind {
    OPENAI("openai"),
    ANTHROPIC("anthropic");

    private final String id;

    ProtocolKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public Protocol protocol() {
        return this == OPENAI ? OpenAiProtocol.INSTANCE : AnthropicProtocol.INSTANCE;
    }

    public static ProtocolKind parse(String id) {
        for (var k : values()) if (k.id.equals(id == null ? "" : id.toLowerCase(Locale.ROOT))) return k;
        throw new IllegalArgumentException("Unknown protocol '" + id + "' (use openai or anthropic)");
    }
}
