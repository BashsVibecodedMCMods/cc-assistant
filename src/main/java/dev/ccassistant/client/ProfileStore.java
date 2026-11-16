package dev.ccassistant.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ccassistant.provider.ProviderProfile;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The player's provider profiles ({@code profiles.json}, no secrets) and stored keys ({@code credentials.json},
 * owner-only permissions on POSIX), kept outside the game instance (docs/CLIENT_UI.md section 3). Writes are atomic.
 * Thread-safe.
 */
public final class ProfileStore {
    public static final String PROFILES = "profiles.json";
    public static final String CREDENTIALS = "credentials.json";

    private final Path dir;
    private final Function<String, String> env;
    private final List<ProviderProfile> profiles = new ArrayList<>();
    private final Map<String, String> keys = new LinkedHashMap<>();
    private @Nullable String defaultName;
    private final List<String> warnings = new ArrayList<>();

    public ProfileStore(Path dir, Function<String, String> env) {
        this.dir = dir;
        this.env = env;
        load();
    }

    /** The default folder for this OS (docs/CLIENT_UI.md section 3). */
    public static Path defaultDir() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var home = Path.of(System.getProperty("user.home", "."));
        if (os.contains("win")) {
            var appData = System.getenv("APPDATA");
            return (appData != null && !appData.isBlank() ? Path.of(appData) : home.resolve("AppData/Roaming")).resolve("ccassistant");
        }
        if (os.contains("mac")) return home.resolve("Library/Application Support/ccassistant");
        var xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".config")).resolve("ccassistant");
    }

    /** The configured folder, refusing anything inside the game's saves folder. */
    public static Path resolveDir(String configured, Path gameDir) {
        if (configured == null || configured.isBlank()) return defaultDir();
        var p = Path.of(configured).toAbsolutePath().normalize();
        var saves = gameDir.toAbsolutePath().normalize().resolve("saves");
        if (p.startsWith(saves)) throw new IllegalArgumentException("storageDir must not be inside saves/");
        return p;
    }

    public Path dir() {
        return dir;
    }

    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    public synchronized List<ProviderProfile> profiles() {
        return List.copyOf(profiles);
    }

    public synchronized @Nullable String defaultName() {
        return defaultName;
    }

    public synchronized @Nullable ProviderProfile byName(@Nullable String name) {
        if (name == null) return null;
        for (var p : profiles) if (p.name().equals(name)) return p;
        return null;
    }

    public synchronized @Nullable ProviderProfile defaultProfile() {
        return byName(defaultName);
    }

    /** The key to use for {@code p}: the stored one, or the named environment variable. "" when none. */
    public synchronized String key(ProviderProfile p) {
        return switch (p.keyMode()) {
            case STORED -> keys.getOrDefault(p.id(), "");
            case ENV -> {
                var v = env.apply(p.keyEnvVar());
                yield v == null ? "" : v.trim();
            }
            case NONE -> "";
        };
    }

    public synchronized boolean hasUsableKey(ProviderProfile p) {
        return !key(p).isEmpty() || (!p.preset().keyRequired() && p.keyMode() != ProviderProfile.KeyMode.ENV);
    }

    /** The last 4 characters of a stored key, for display; null when none is stored. */
    public synchronized @Nullable String keyHint(ProviderProfile p) {
        var k = p.keyMode() == ProviderProfile.KeyMode.STORED ? keys.get(p.id()) : null;
        if (k == null || k.isEmpty()) return null;
        return k.length() <= 4 ? "****" : k.substring(k.length() - 4);
    }

    /**
     * Adds or replaces a profile (matched by id). {@code newKey}: null keeps the stored key, "" removes it, anything
     * else replaces it. A profile not in STORED mode never keeps a stored key.
     */
    public synchronized void save(ProviderProfile profile, @Nullable String newKey) throws IOException {
        var checked = profile.validated();
        for (var p : profiles) {
            if (p.name().equals(checked.name()) && !p.id().equals(checked.id())) {
                throw new IllegalArgumentException("Another profile is already named " + checked.name());
            }
        }
        String oldName = null;
        var replaced = false;
        for (var i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id().equals(checked.id())) {
                oldName = profiles.get(i).name();
                profiles.set(i, checked);
                replaced = true;
            }
        }
        if (!replaced) profiles.add(checked);
        if (defaultName == null || defaultName.equals(oldName)) defaultName = checked.name();
        if (checked.keyMode() != ProviderProfile.KeyMode.STORED) keys.remove(checked.id());
        else if (newKey != null) {
            var k = newKey.trim();
            if (k.isEmpty()) keys.remove(checked.id());
            else keys.put(checked.id(), k);
        }
        write();
    }

    public synchronized void delete(String id) throws IOException {
        profiles.removeIf(p -> p.id().equals(id));
        keys.remove(id);
        if (byName(defaultName) == null) defaultName = profiles.isEmpty() ? null : profiles.get(0).name();
        write();
    }

    public synchronized void setDefault(String name) throws IOException {
        if (byName(name) == null) throw new IllegalArgumentException("No profile named " + name);
        defaultName = name;
        write();
    }

    // ---- Files ----

    private void load() {
        profiles.clear();
        keys.clear();
        defaultName = null;
        var pf = dir.resolve(PROFILES);
        if (Files.exists(pf)) {
            try {
                var root = JsonParser.parseString(Files.readString(pf, StandardCharsets.UTF_8)).getAsJsonObject();
                var arr = root.getAsJsonArray("profiles");
                if (arr != null) {
                    for (var e : arr) {
                        try {
                            var p = ProviderProfile.fromJson(e.getAsJsonObject(), java.util.UUID.randomUUID().toString());
                            if (byName(p.name()) == null) profiles.add(p);
                        } catch (RuntimeException ex) {
                            warnings.add("Skipped an invalid profile: " + ex.getMessage());
                        }
                    }
                }
                if (root.has("default") && root.get("default").isJsonPrimitive()) defaultName = root.get("default").getAsString();
                if (byName(defaultName) == null) defaultName = profiles.isEmpty() ? null : profiles.get(0).name();
            } catch (IOException | RuntimeException e) {
                quarantine(pf, "profiles");
            }
        }
        var cf = dir.resolve(CREDENTIALS);
        if (Files.exists(cf)) {
            try {
                var root = JsonParser.parseString(Files.readString(cf, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : root.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) keys.put(e.getKey(), e.getValue().getAsString());
                }
            } catch (IOException | RuntimeException e) {
                quarantine(cf, "stored keys");
            }
        }
    }

    private void quarantine(Path file, String what) {
        var broken = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.move(file, broken);
            warnings.add("Your " + what + " file was damaged and was moved to " + broken.getFileName() + ".");
        } catch (IOException e) {
            warnings.add("Your " + what + " file is damaged and couldn't be read.");
        }
    }

    private void write() throws IOException {
        Files.createDirectories(dir);
        var root = new JsonObject();
        root.addProperty("version", 1);
        if (defaultName != null) root.addProperty("default", defaultName);
        var arr = new JsonArray();
        for (var p : profiles) arr.add(p.toJson());
        root.add("profiles", arr);
        var gson = new GsonBuilder().setPrettyPrinting().create();
        atomicWrite(dir.resolve(PROFILES), gson.toJson(root), false);

        var creds = new JsonObject();
        keys.forEach(creds::addProperty);
        atomicWrite(dir.resolve(CREDENTIALS), gson.toJson(creds), true);
    }

    private static void atomicWrite(Path target, String content, boolean secret) throws IOException {
        var tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        var posix = target.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (secret && posix) {
            try {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (FileAlreadyExistsException ignored) {
            }
        }
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        if (secret && posix) Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
