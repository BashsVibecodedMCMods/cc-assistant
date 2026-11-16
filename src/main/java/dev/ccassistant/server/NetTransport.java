package dev.ccassistant.server;

import com.google.gson.JsonObject;
import dev.ccassistant.net.TransferPayload;
import dev.ccassistant.net.Transfers;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/** Sends messages to a player's client over the game connection. */
final class NetTransport implements AssistantRuntime.PlayerTransport {
    private final MinecraftServer server;

    NetTransport(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean hasClient(UUID player) {
        var p = server.getPlayerList().getPlayer(player);
        return p != null && p.connection.hasChannel(TransferPayload.TYPE);
    }

    @Override
    public void send(UUID player, JsonObject message) {
        server.execute(() -> {
            var p = server.getPlayerList().getPlayer(player);
            if (p != null) Transfers.send(message, chunk -> PacketDistributor.sendToPlayer(p, chunk));
        });
    }
}
