package dev.ccassistant.core;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Request-rate windows and concurrency counters per computer, per player and for the whole server
 * (docs/SECURITY.md section 7). Thread-safe; callers must {@link #release} every successful {@link #acquire}.
 */
public final class Quotas {
    public record Limits(int perComputerConcurrent, int perPlayerConcurrent, int totalConcurrent,
                         int perComputerPerMinute, int perPlayerPerMinute) {
    }

    private static final long WINDOW_MS = 60_000;
    private final LongSupplier clock;
    private final Map<Integer, Integer> computerActive = new HashMap<>();
    private final Map<UUID, Integer> playerActive = new HashMap<>();
    private final Map<Integer, ArrayDeque<Long>> computerTimes = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> playerTimes = new HashMap<>();
    private int totalActive;

    public Quotas(LongSupplier clock) {
        this.clock = clock;
    }

    public Quotas() {
        this(System::currentTimeMillis);
    }

    /** Takes one slot, or throws {@code busy}/{@code rate_limited}. {@code player} is null for unlinked computers. */
    public synchronized void acquire(int computer, @Nullable UUID player, Limits limits) throws AiException {
        var now = clock.getAsLong();
        if (totalActive >= limits.totalConcurrent()) {
            throw new AiException(AiException.BUSY, "The server is handling too many AI requests; try again shortly");
        }
        if (computerActive.getOrDefault(computer, 0) >= limits.perComputerConcurrent()) {
            throw new AiException(AiException.BUSY, "This computer already has " + limits.perComputerConcurrent() + " request(s) in progress");
        }
        if (player != null && playerActive.getOrDefault(player, 0) >= limits.perPlayerConcurrent()) {
            throw new AiException(AiException.BUSY, "The owner already has " + limits.perPlayerConcurrent() + " request(s) in progress");
        }
        var ct = window(computerTimes.computeIfAbsent(computer, k -> new ArrayDeque<>()), now);
        if (ct.size() >= limits.perComputerPerMinute()) throw rate(ct, now, "this computer");
        ArrayDeque<Long> pt = null;
        if (player != null) {
            pt = window(playerTimes.computeIfAbsent(player, k -> new ArrayDeque<>()), now);
            if (pt.size() >= limits.perPlayerPerMinute()) throw rate(pt, now, "the owner");
        }
        ct.addLast(now);
        if (pt != null) pt.addLast(now);
        computerActive.merge(computer, 1, Integer::sum);
        if (player != null) playerActive.merge(player, 1, Integer::sum);
        totalActive++;
    }

    public synchronized void release(int computer, @Nullable UUID player) {
        computerActive.computeIfPresent(computer, (k, v) -> v <= 1 ? null : v - 1);
        if (player != null) playerActive.computeIfPresent(player, (k, v) -> v <= 1 ? null : v - 1);
        if (totalActive > 0) totalActive--;
    }

    public synchronized int active(int computer) {
        return computerActive.getOrDefault(computer, 0);
    }

    public synchronized int totalActive() {
        return totalActive;
    }

    private static ArrayDeque<Long> window(ArrayDeque<Long> times, long now) {
        while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_MS) times.removeFirst();
        return times;
    }

    private static AiException rate(ArrayDeque<Long> times, long now, String who) {
        var wait = Math.max(1, (times.peekFirst() + WINDOW_MS - now + 999) / 1000);
        return new AiException(AiException.RATE_LIMITED, "Too many requests from " + who + "; try again in " + wait + "s");
    }
}
