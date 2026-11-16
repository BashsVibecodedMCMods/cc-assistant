package dev.ccassistant.client;

import dev.ccassistant.CCAssistant;
import dev.ccassistant.Config;
import dev.ccassistant.net.Transfers;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;

/** Client-side singletons: the profile store and the request handler. Client only. */
public final class ClientState {
    private static ProfileStore store;
    private static Path storeDir;
    private static ClientRequests requests;

    private ClientState() {
    }

    public static synchronized ProfileStore store() {
        Path dir;
        try {
            dir = ProfileStore.resolveDir(Config.CLIENT_STORAGE_DIR.get(), FMLPaths.GAMEDIR.get());
        } catch (IllegalArgumentException e) {
            CCAssistant.LOG.warn("Ignoring storageDir: {}", e.getMessage());
            dir = ProfileStore.defaultDir();
        }
        if (store == null || !dir.equals(storeDir)) {
            store = new ProfileStore(dir, System::getenv);
            storeDir = dir;
            for (var w : store.warnings()) CCAssistant.LOG.warn(w);
        }
        return store;
    }

    public static synchronized ClientRequests requests() {
        if (requests == null) {
            requests = new ClientRequests(ClientState::store,
                new ClientRequests.Settings(Config.CLIENT_ENABLED::get, Config.CLIENT_REQUESTS_PER_MINUTE::get,
                    Config.CLIENT_REQUESTS_PER_HOUR::get, Config.CLIENT_MAX_REQUEST_BYTES::get),
                msg -> Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().getConnection() != null) {
                        Transfers.send(msg, PacketDistributor::sendToServer);
                    }
                }),
                text -> Minecraft.getInstance().execute(() -> {
                    var player = Minecraft.getInstance().player;
                    if (Config.CLIENT_NOTIFY.get() && player != null) player.displayClientMessage(Component.literal(text), true);
                }),
                System::currentTimeMillis);
        }
        return requests;
    }

    /** Turns the client side on or off, updating the server at once. Off also aborts requests in progress. */
    public static void setEnabled(boolean enabled) {
        Config.CLIENT_ENABLED.set(enabled);
        Config.CLIENT_SPEC.save();
        if (!enabled) requests().cancelAll();
        statusChanged();
    }

    /** Call after anything that can change readiness (profiles, keys, the switch). */
    public static void statusChanged() {
        if (Minecraft.getInstance().getConnection() != null) requests().sendStatus(false);
    }
}
