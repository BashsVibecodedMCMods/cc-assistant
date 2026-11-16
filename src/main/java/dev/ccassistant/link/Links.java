package dev.ccassistant.link;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which player owns each computer (by CC computer id), stored with the overworld. Read from computer threads, so the
 * map is concurrent; writes come from the server thread (commands) or computer threads (unlink).
 */
public final class Links extends SavedData {
    private static final String ID = "ccassistant_links";

    public record Owner(UUID uuid, String name) {
    }

    private final Map<Integer, Owner> owners = new ConcurrentHashMap<>();

    public static Links get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Links::new, Links::load, null), ID);
    }

    public @Nullable Owner owner(int computer) {
        return owners.get(computer);
    }

    /** Sets the owner; returns the previous owner, if any. */
    public @Nullable Owner link(int computer, UUID uuid, String name) {
        var old = owners.put(computer, new Owner(uuid, name));
        setDirty();
        return old;
    }

    public @Nullable Owner unlink(int computer) {
        var old = owners.remove(computer);
        if (old != null) setDirty();
        return old;
    }

    /** Refreshes a stored player name (players can rename). */
    public void updateName(UUID uuid, String name) {
        for (var e : owners.entrySet()) {
            if (e.getValue().uuid().equals(uuid) && !e.getValue().name().equals(name)) {
                e.setValue(new Owner(uuid, name));
                setDirty();
            }
        }
    }

    public Map<Integer, Owner> all() {
        return Map.copyOf(owners);
    }

    private static Links load(CompoundTag tag, HolderLookup.Provider registries) {
        var links = new Links();
        for (var t : tag.getList("links", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag) t;
            if (c.hasUUID("owner")) links.owners.put(c.getInt("computer"), new Owner(c.getUUID("owner"), c.getString("name")));
        }
        return links;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        owners.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            var c = new CompoundTag();
            c.putInt("computer", e.getKey());
            c.putUUID("owner", e.getValue().uuid());
            c.putString("name", e.getValue().name());
            list.add(c);
        });
        tag.put("links", list);
        return tag;
    }
}
