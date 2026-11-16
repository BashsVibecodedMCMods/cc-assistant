package dev.ccassistant.net;

import dev.ccassistant.Config;
import dev.ccassistant.server.AssistantRuntime;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Registers the payload and hands complete messages to the server runtime or the client. */
public final class Net {
    private static final Map<UUID, Transfers.Reassembler> SERVER_SIDE = new ConcurrentHashMap<>();

    private Net() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // optional(): players without the mod can still join; the server sees them as "no_client".
        event.registrar("1").optional().playBidirectional(TransferPayload.TYPE, TransferPayload.STREAM_CODEC, Net::handle);
    }

    private static void handle(TransferPayload payload, IPayloadContext context) {
        if (context.flow().isClientbound()) {
            if (FMLEnvironment.dist.isClient()) dev.ccassistant.client.ClientNet.onPayload(payload);
            return;
        }
        var player = context.player();
        var rt = AssistantRuntime.get();
        if (rt == null) return;
        var reassembler = SERVER_SIDE.computeIfAbsent(player.getUUID(),
            k -> new Transfers.Reassembler(Config.MAX_RESPONSE_BYTES.get() + 64 * 1024));
        var message = reassembler.accept(payload);
        if (message != null) rt.onClientMessage(player.getUUID(), message);
    }

    public static void forget(UUID player) {
        SERVER_SIDE.remove(player);
    }

    public static void clear() {
        SERVER_SIDE.clear();
    }
}
