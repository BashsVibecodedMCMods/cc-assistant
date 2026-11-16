package dev.ccassistant.server;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ccassistant.CCAssistant;
import dev.ccassistant.core.AiException;
import dev.ccassistant.provider.ProviderProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The operator's providers, from world/serverconfig/ccassistant-providers.json (docs/CONFIG.md). */
public final class ServerProviders {
    public static final String FILE = "ccassistant-providers.json";

    private final List<ProviderProfile> providers;
    private final @Nullable String defaultName;
    private final boolean allowLuaChoice;
    private final @Nullable String loadError;

    private ServerProviders(List<ProviderProfile> providers, @Nullable String defaultName, boolean allowLuaChoice, @Nullable String loadError) {
        this.providers = List.copyOf(providers);
        this.defaultName = defaultName;
        this.allowLuaChoice = allowLuaChoice;
        this.loadError = loadError;
    }

    public static ServerProviders empty() {
        return new ServerProviders(List.of(), null, false, null);
    }

    public static Path path(MinecraftServer server) {
        return server.getWorldPath(new LevelResource("serverconfig")).resolve(FILE);
    }

    /** Loads the file, writing a commented example (not loaded) if there is none. Never throws. */
    public static ServerProviders load(MinecraftServer server) {
        var path = path(server);
        if (!Files.exists(path)) {
            writeExample(path.resolveSibling(FILE + ".example"));
            return empty();
        }
        try {
            return parse(JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            var msg = FILE + " is invalid: " + e.getMessage();
            CCAssistant.LOG.error(msg);
            return new ServerProviders(List.of(), null, false, msg);
        }
    }

    public static ServerProviders parse(JsonObject root) {
        var list = new ArrayList<ProviderProfile>();
        var names = new java.util.HashSet<String>();
        var arr = root.getAsJsonArray("providers");
        if (arr != null) {
            var i = 0;
            for (var e : arr) {
                i++;
                var p = ProviderProfile.fromJson(e.getAsJsonObject(), "server-" + i);
                if (p.keyMode() == ProviderProfile.KeyMode.STORED) {
                    throw new IllegalArgumentException("provider '" + p.name() + "': server keys must come from apiKeyEnvVar");
                }
                if (!names.add(p.name())) throw new IllegalArgumentException("two providers are named '" + p.name() + "'");
                list.add(p);
            }
        }
        var def = root.has("default") ? root.get("default").getAsString() : list.isEmpty() ? null : list.get(0).name();
        if (def != null && !names.contains(def)) throw new IllegalArgumentException("default provider '" + def + "' isn't listed");
        return new ServerProviders(list, def, root.has("allowLuaChoice") && root.get("allowLuaChoice").getAsBoolean(), null);
    }

    public List<ProviderProfile> providers() {
        return providers;
    }

    public @Nullable String loadError() {
        return loadError;
    }

    public @Nullable ProviderProfile byName(String name) {
        for (var p : providers) if (p.name().equals(name)) return p;
        return null;
    }

    /** The provider for a request, honouring an optional requested name. */
    public ProviderProfile pick(@Nullable String requested) throws AiException {
        if (providers.isEmpty()) {
            throw new AiException(AiException.NOT_CONFIGURED, loadError != null
                ? "The server's AI providers file is invalid. Ask an operator to check the log."
                : "The server has no AI providers configured. Ask an operator.");
        }
        if (requested != null && !requested.equals(defaultName)) {
            if (!allowLuaChoice) throw new AiException(AiException.BAD_PROFILE, "This server doesn't let programs choose a provider");
            var p = byName(requested);
            if (p == null) throw new AiException(AiException.BAD_PROFILE, "The server has no provider named '" + requested + "'");
            return p;
        }
        return byName(defaultName);
    }

    /** The provider's key, or "" when it needs none. Throws when a required key is missing. */
    public static String key(ProviderProfile p) throws AiException {
        if (p.keyMode() != ProviderProfile.KeyMode.ENV) return "";
        var key = System.getenv(p.keyEnvVar());
        if (key == null || key.isBlank()) {
            throw new AiException(AiException.NOT_CONFIGURED, "The server's AI key isn't set. Ask an operator.");
        }
        return key.trim();
    }

    private static void writeExample(Path path) {
        try {
            if (Files.exists(path)) return;
            Files.createDirectories(path.getParent());
            var example = JsonParser.parseString("""
                {"default":"nano","allowLuaChoice":false,"providers":[
                  {"name":"nano","preset":"nanogpt","apiKeyEnvVar":"NANOGPT_API_KEY","model":"z-ai/glm-5.3","maxTokens":4096},
                  {"name":"claude","preset":"anthropic","apiKeyEnvVar":"ANTHROPIC_API_KEY","model":"claude-opus-5-5","effort":"medium"}]}
                """);
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(example), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CCAssistant.LOG.warn("Couldn't write {}: {}", path, e.toString());
        }
    }
}
