package dev.ccassistant.client;

import dev.ccassistant.Config;
import dev.ccassistant.net.TransferPayload;
import dev.ccassistant.net.Transfers;

/** Messages from the server, already on the client thread. Client only. */
public final class ClientNet {
    private static Transfers.Reassembler reassembler;

    private ClientNet() {
    }

    public static void onPayload(TransferPayload payload) {
        if (reassembler == null) reassembler = new Transfers.Reassembler(Config.CLIENT_MAX_REQUEST_BYTES.get() + 64 * 1024);
        var msg = reassembler.accept(payload);
        if (msg != null) ClientState.requests().onMessage(msg);
    }

    public static void reset() {
        reassembler = null;
    }
}
