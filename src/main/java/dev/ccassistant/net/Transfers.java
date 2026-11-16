package dev.ccassistant.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Splits JSON messages into {@link TransferPayload} chunks and puts them back together, with size limits. */
public final class Transfers {
    public static final int CHUNK_BYTES = 30_000;
    private static final AtomicInteger NEXT = new AtomicInteger();

    private Transfers() {
    }

    public static void send(JsonObject message, Consumer<TransferPayload> out) {
        var bytes = message.toString().getBytes(StandardCharsets.UTF_8);
        var id = NEXT.incrementAndGet();
        var count = Math.max(1, (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        for (var i = 0; i < count; i++) {
            var from = i * CHUNK_BYTES;
            out.accept(new TransferPayload(id, i, count, Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + CHUNK_BYTES))));
        }
    }

    /** Collects chunks from one peer. Not thread-safe; use one per peer from one thread. */
    public static final class Reassembler {
        private static final int MAX_OPEN = 8;
        private final int maxBytes;
        private final Map<Integer, Partial> open = new LinkedHashMap<>();

        private static final class Partial {
            final int count;
            int next;
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            Partial(int count) {
                this.count = count;
            }
        }

        public Reassembler(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        /** Returns the whole message when its last chunk arrives; null otherwise. Bad or oversized input is dropped. */
        public @Nullable JsonObject accept(TransferPayload p) {
            if (p.count() < 1 || p.index() < 0 || p.index() >= p.count() || (long) p.count() * CHUNK_BYTES > maxBytes + CHUNK_BYTES) {
                open.remove(p.transfer());
                return null;
            }
            var partial = open.get(p.transfer());
            if (partial == null) {
                if (p.index() != 0) return null;
                if (open.size() >= MAX_OPEN) open.remove(open.keySet().iterator().next());
                partial = new Partial(p.count());
                open.put(p.transfer(), partial);
            }
            if (partial.count != p.count() || partial.next != p.index() || partial.bytes.size() + p.data().length > maxBytes) {
                open.remove(p.transfer());
                return null;
            }
            partial.bytes.writeBytes(p.data());
            partial.next++;
            if (partial.next < partial.count) return null;
            open.remove(p.transfer());
            try {
                var json = JsonParser.parseString(partial.bytes.toString(StandardCharsets.UTF_8));
                return json.isJsonObject() ? json.getAsJsonObject() : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
