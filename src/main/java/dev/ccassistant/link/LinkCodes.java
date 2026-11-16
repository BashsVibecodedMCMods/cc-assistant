package dev.ccassistant.link;

import dev.ccassistant.core.AiException;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * One-time link codes (docs/SECURITY.md section 3): 6 characters from an alphabet without lookalikes, single use,
 * 5-minute expiry, one active code per computer, and at most 10 failed attempts per player per 10 minutes.
 */
public final class LinkCodes {
    public static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // 32 symbols: no 0/O, 1/I
    public static final int LENGTH = 6;
    public static final long EXPIRY_MS = 5 * 60_000;
    public static final int MAX_FAILURES = 10;
    public static final long FAILURE_WINDOW_MS = 10 * 60_000;

    private record Entry(int computer, long expires) {
    }

    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private final Map<String, Entry> byCode = new HashMap<>();
    private final Map<Integer, String> byComputer = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> failures = new HashMap<>();

    public LinkCodes(LongSupplier clock) {
        this.clock = clock;
    }

    public LinkCodes() {
        this(System::currentTimeMillis);
    }

    /** Creates a code for {@code computer}, replacing any earlier one. */
    public synchronized String create(int computer) {
        purge();
        var old = byComputer.remove(computer);
        if (old != null) byCode.remove(old);
        String code;
        do {
            var sb = new StringBuilder(LENGTH);
            for (var i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            code = sb.toString();
        } while (byCode.containsKey(code));
        byCode.put(code, new Entry(computer, clock.getAsLong() + EXPIRY_MS));
        byComputer.put(computer, code);
        return code;
    }

    /** Uses a code typed by {@code player}. Returns the computer id, or throws with a message for the player. */
    public synchronized int consume(String typed, UUID player) throws AiException {
        purge();
        var now = clock.getAsLong();
        var fails = failures.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (!fails.isEmpty() && fails.peekFirst() <= now - FAILURE_WINDOW_MS) fails.removeFirst();
        if (fails.size() >= MAX_FAILURES) {
            throw new AiException(AiException.RATE_LIMITED, "Too many wrong codes. Try again in a few minutes.");
        }
        var code = typed == null ? "" : typed.trim().toUpperCase(Locale.ROOT);
        var entry = byCode.remove(code);
        if (entry == null) {
            fails.addLast(now);
            throw new AiException(AiException.BAD_REQUEST, "That code is wrong or has expired. Run 'ai link' on the computer for a new one.");
        }
        byComputer.remove(entry.computer());
        return entry.computer();
    }

    public synchronized OptionalInt pendingFor(String code) {
        purge();
        var e = byCode.get(code);
        return e == null ? OptionalInt.empty() : OptionalInt.of(e.computer());
    }

    public synchronized void clear() {
        byCode.clear();
        byComputer.clear();
        failures.clear();
    }

    private void purge() {
        var now = clock.getAsLong();
        byCode.entrySet().removeIf(e -> {
            if (e.getValue().expires() > now) return false;
            byComputer.remove(e.getValue().computer(), e.getKey());
            return true;
        });
    }
}
