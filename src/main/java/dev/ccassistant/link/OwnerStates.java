package dev.ccassistant.link;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Whether each player can currently serve their computers' requests (docs/LUA_API.md, "When the owner is offline").
 * Built from login/logout and the client's one-bit readiness status. Listeners hear about real changes only.
 */
public final class OwnerStates {
    public enum State {
        READY, OFFLINE, NO_CLIENT, DISABLED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Set<UUID> withClient = ConcurrentHashMap.newKeySet();
    private final Set<UUID> ready = ConcurrentHashMap.newKeySet();
    private final Map<UUID, State> last = new ConcurrentHashMap<>();
    private final BiConsumer<UUID, State> onChange;

    public OwnerStates(BiConsumer<UUID, State> onChange) {
        this.onChange = onChange;
    }

    public State state(UUID player) {
        if (!online.contains(player)) return State.OFFLINE;
        if (!withClient.contains(player)) return State.NO_CLIENT;
        return ready.contains(player) ? State.READY : State.DISABLED;
    }

    public synchronized void login(UUID player, boolean hasClient) {
        online.add(player);
        if (hasClient) withClient.add(player);
        else withClient.remove(player);
        ready.remove(player); // until the client says otherwise
        changed(player);
    }

    public synchronized void logout(UUID player) {
        online.remove(player);
        withClient.remove(player);
        ready.remove(player);
        changed(player);
    }

    public synchronized void clientStatus(UUID player, boolean isReady) {
        if (!online.contains(player)) return;
        withClient.add(player);
        if (isReady) ready.add(player);
        else ready.remove(player);
        changed(player);
    }

    public synchronized void clear() {
        online.clear();
        withClient.clear();
        ready.clear();
        last.clear();
    }

    private void changed(UUID player) {
        var now = state(player);
        var before = last.put(player, now);
        if (before == null) before = State.OFFLINE;
        if (before != now) onChange.accept(player, now);
    }
}
