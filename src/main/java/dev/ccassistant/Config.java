package dev.ccassistant;

import dev.ccassistant.core.Quotas;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** Server and client config (docs/CONFIG.md). Provider profiles and keys are not here. */
public final class Config {
    private Config() {
    }

    public enum Backend {NONE, PLAYER, SERVER, EITHER}

    // ---- Server ----
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.EnumValue<Backend> BACKEND;
    public static final ModConfigSpec.BooleanValue REQUIRE_LINK;
    public static final ModConfigSpec.BooleanValue ALLOW_COMMAND_COMPUTERS;
    public static final ModConfigSpec.IntValue MAX_REQUEST_BYTES;
    public static final ModConfigSpec.IntValue MAX_RESPONSE_BYTES;
    public static final ModConfigSpec.IntValue MAX_TOKENS;
    public static final ModConfigSpec.IntValue REQUEST_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue MAX_CONCURRENT_PER_COMPUTER;
    public static final ModConfigSpec.IntValue MAX_CONCURRENT_PER_PLAYER;
    public static final ModConfigSpec.IntValue MAX_CONCURRENT_TOTAL;
    public static final ModConfigSpec.IntValue REQUESTS_PER_MINUTE_PER_PLAYER;
    public static final ModConfigSpec.IntValue REQUESTS_PER_MINUTE_PER_COMPUTER;
    public static final ModConfigSpec.BooleanValue ALLOW_USER_TOOLS;
    public static final ModConfigSpec.BooleanValue ALLOW_DESTRUCTIVE_TOOLS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_ROM_TOOLS;
    public static final ModConfigSpec.IntValue MAX_TOOL_SECONDS;
    public static final ModConfigSpec.IntValue MAX_TOOL_OUTPUT_BYTES;
    public static final ModConfigSpec.IntValue MAX_TOOL_CALLS_PER_TURN;

    // ---- Client ----
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue CLIENT_ENABLED;
    public static final ModConfigSpec.IntValue CLIENT_REQUESTS_PER_MINUTE;
    public static final ModConfigSpec.IntValue CLIENT_REQUESTS_PER_HOUR;
    public static final ModConfigSpec.IntValue CLIENT_MAX_REQUEST_BYTES;
    public static final ModConfigSpec.BooleanValue CLIENT_NOTIFY;
    public static final ModConfigSpec.ConfigValue<String> CLIENT_STORAGE_DIR;

    static {
        var s = new ModConfigSpec.Builder();
        BACKEND = s.comment(
                "Where AI requests from computers go:",
                "  NONE   - nowhere; every request fails with not_configured (default)",
                "  PLAYER - the computer owner's own game client and provider profiles (they pay their provider)",
                "  SERVER - the providers in ccassistant-providers.json, with keys in environment variables (you pay)",
                "  EITHER - the owner's client when they are ready, otherwise the server's default provider")
            .defineEnum("backend", Backend.NONE);
        REQUIRE_LINK = s.comment("Every computer must be linked to a player before it can send requests. PLAYER always needs this.")
            .define("requireLink", true);
        ALLOW_COMMAND_COMPUTERS = s.comment(
                "Command computers can run Minecraft commands through CC's commands API. Keep this off unless you trust",
                "every user of every command computer, and the model, with operator-level commands.")
            .define("allowCommandComputers", false);

        s.push("limits");
        MAX_REQUEST_BYTES = s.defineInRange("maxRequestBytes", 262_144, 1024, 16 * 1024 * 1024);
        MAX_RESPONSE_BYTES = s.defineInRange("maxResponseBytes", 1_048_576, 4096, 16 * 1024 * 1024);
        MAX_TOKENS = s.comment("Cap on max_tokens for every request, on every backend.").defineInRange("maxTokens", 8192, 1, 128_000);
        REQUEST_TIMEOUT_SECONDS = s.defineInRange("requestTimeoutSeconds", 180, 5, 3600);
        MAX_CONCURRENT_PER_COMPUTER = s.defineInRange("maxConcurrentPerComputer", 1, 1, 64);
        MAX_CONCURRENT_PER_PLAYER = s.defineInRange("maxConcurrentPerPlayer", 2, 1, 256);
        MAX_CONCURRENT_TOTAL = s.defineInRange("maxConcurrentTotal", 16, 1, 4096);
        REQUESTS_PER_MINUTE_PER_PLAYER = s.defineInRange("requestsPerMinutePerPlayer", 10, 1, 10_000);
        REQUESTS_PER_MINUTE_PER_COMPUTER = s.defineInRange("requestsPerMinutePerComputer", 10, 1, 10_000);
        s.pop();

        s.comment("Defaults for the ROM tool loader. They limit what the model can do; they are not a sandbox against",
            "the computer's user (see docs/SECURITY.md section 4).").push("tools");
        ALLOW_USER_TOOLS = s.define("allowUserTools", true);
        ALLOW_DESTRUCTIVE_TOOLS = s.define("allowDestructiveTools", true);
        DISABLED_ROM_TOOLS = s.comment("ROM tool names to hide, e.g. [\"run_shell\", \"run_lua\"]")
            .defineListAllowEmpty("disabledRomTools", List.of(), () -> "", o -> o instanceof String);
        MAX_TOOL_SECONDS = s.defineInRange("maxToolSeconds", 30, 1, 600);
        MAX_TOOL_OUTPUT_BYTES = s.defineInRange("maxToolOutputBytes", 16_384, 256, 1_048_576);
        MAX_TOOL_CALLS_PER_TURN = s.defineInRange("maxToolCallsPerTurn", 16, 1, 256);
        s.pop();
        SERVER_SPEC = s.build();

        var c = new ModConfigSpec.Builder();
        CLIENT_ENABLED = c.comment("Let your linked computers use your provider profiles. Nothing is sent until this is on.")
            .define("enabled", false);
        CLIENT_REQUESTS_PER_MINUTE = c.defineInRange("requestsPerMinute", 6, 1, 1000);
        CLIENT_REQUESTS_PER_HOUR = c.defineInRange("requestsPerHour", 60, 1, 100_000);
        CLIENT_MAX_REQUEST_BYTES = c.defineInRange("maxRequestBytes", 262_144, 1024, 16 * 1024 * 1024);
        CLIENT_NOTIFY = c.comment("Show an action-bar notice for each request.").define("notify", true);
        CLIENT_STORAGE_DIR = c.comment("Where profiles.json and credentials.json live. Empty = your user config folder",
                "(%APPDATA%\\ccassistant, ~/Library/Application Support/ccassistant, or ~/.config/ccassistant).")
            .define("storageDir", "");
        CLIENT_SPEC = c.build();
    }

    public static Quotas.Limits quotaLimits() {
        return new Quotas.Limits(MAX_CONCURRENT_PER_COMPUTER.get(), MAX_CONCURRENT_PER_PLAYER.get(), MAX_CONCURRENT_TOTAL.get(),
            REQUESTS_PER_MINUTE_PER_COMPUTER.get(), REQUESTS_PER_MINUTE_PER_PLAYER.get());
    }
}
