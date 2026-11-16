package dev.ccassistant.client;

import dev.ccassistant.provider.Preset;
import dev.ccassistant.provider.ProviderProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProfileStoreTest {
    private static ProviderProfile profile(String id, String name, ProviderProfile.KeyMode mode, String env) {
        return new ProviderProfile(id, name, Preset.NANOGPT, Preset.NANOGPT.protocol(), Preset.NANOGPT.baseUrl(), "z-ai/glm-5.3",
            4096, null, false, false, Map.of(), mode, env, true);
    }

    @Test
    void keysNeverGoInProfilesFileAndArePrivate(@TempDir Path dir) throws Exception {
        var store = new ProfileStore(dir, k -> null);
        store.save(profile("a", "nano", ProviderProfile.KeyMode.STORED, ""), "sk-very-secret-1234");
        var profiles = Files.readString(dir.resolve(ProfileStore.PROFILES));
        assertFalse(profiles.contains("sk-very-secret"), profiles);
        assertTrue(Files.readString(dir.resolve(ProfileStore.CREDENTIALS)).contains("sk-very-secret-1234"));
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(dir.resolve(ProfileStore.CREDENTIALS)));
        }
        // a fresh store reads both back
        var again = new ProfileStore(dir, k -> null);
        var p = again.byName("nano");
        assertNotNull(p);
        assertEquals("sk-very-secret-1234", again.key(p));
        assertEquals("1234", again.keyHint(p));
        assertEquals("nano", again.defaultName());
        assertTrue(again.hasUsableKey(p));
    }

    @Test
    void keepClearAndReplaceKeys(@TempDir Path dir) throws Exception {
        var store = new ProfileStore(dir, k -> null);
        var p = profile("a", "nano", ProviderProfile.KeyMode.STORED, "");
        store.save(p, "k1-aaaa");
        store.save(p, null);
        assertEquals("k1-aaaa", store.key(p));
        store.save(p, "k2-bbbb");
        assertEquals("k2-bbbb", store.key(p));
        store.save(p, "");
        assertEquals("", store.key(p));
        assertFalse(store.hasUsableKey(p)); // NanoGPT needs a key
    }

    @Test
    void envModeReadsTheVariableAndDropsStoredKey(@TempDir Path dir) throws Exception {
        var store = new ProfileStore(dir, k -> k.equals("NANO_KEY") ? " from-env " : null);
        store.save(profile("a", "nano", ProviderProfile.KeyMode.STORED, ""), "stored-key");
        var env = profile("a", "nano", ProviderProfile.KeyMode.ENV, "NANO_KEY");
        store.save(env, null);
        assertEquals("from-env", store.key(env));
        assertFalse(Files.readString(dir.resolve(ProfileStore.CREDENTIALS)).contains("stored-key"));
    }

    @Test
    void namesUniqueDefaultsAndDelete(@TempDir Path dir) throws Exception {
        var store = new ProfileStore(dir, k -> null);
        store.save(profile("a", "one", ProviderProfile.KeyMode.STORED, ""), "k-1111");
        store.save(profile("b", "two", ProviderProfile.KeyMode.STORED, ""), "k-2222");
        assertThrows(IllegalArgumentException.class, () -> store.save(profile("c", "one", ProviderProfile.KeyMode.NONE, ""), null));
        assertEquals("one", store.defaultName());
        store.setDefault("two");
        store.delete("b");
        assertEquals("one", store.defaultName());
        assertFalse(Files.readString(dir.resolve(ProfileStore.CREDENTIALS)).contains("k-2222"));
    }

    @Test
    void damagedFilesAreSetAsideWithAWarning(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(ProfileStore.PROFILES), "{ not json");
        var store = new ProfileStore(dir, k -> null);
        assertTrue(store.profiles().isEmpty());
        assertFalse(store.warnings().isEmpty());
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("profiles.json.broken-")));
        }
    }

    @Test
    void storageDirMayNotBeInsideSaves(@TempDir Path game) {
        assertThrows(IllegalArgumentException.class, () -> ProfileStore.resolveDir(game.resolve("saves/world").toString(), game));
        assertEquals(game.resolve("keys").toAbsolutePath().normalize(), ProfileStore.resolveDir(game.resolve("keys").toString(), game));
    }
}
