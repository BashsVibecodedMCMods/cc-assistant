package dev.ccassistant.link;

import dev.ccassistant.core.AiException;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LinkCodesTest {
    @Test
    void codesAreSingleUseAndCaseInsensitive() throws AiException {
        var codes = new LinkCodes(new AtomicLong()::get);
        var code = codes.create(42);
        assertEquals(LinkCodes.LENGTH, code.length());
        assertTrue(code.chars().allMatch(c -> LinkCodes.ALPHABET.indexOf(c) >= 0));
        var player = UUID.randomUUID();
        assertEquals(42, codes.consume(" " + code.toLowerCase() + " ", player));
        assertThrows(AiException.class, () -> codes.consume(code, player));
    }

    @Test
    void codesExpire() {
        var now = new AtomicLong();
        var codes = new LinkCodes(now::get);
        var code = codes.create(1);
        now.addAndGet(LinkCodes.EXPIRY_MS + 1);
        assertThrows(AiException.class, () -> codes.consume(code, UUID.randomUUID()));
    }

    @Test
    void newCodeReplacesOld() throws AiException {
        var codes = new LinkCodes(new AtomicLong()::get);
        var first = codes.create(5);
        var second = codes.create(5);
        assertTrue(codes.pendingFor(first).isEmpty() || first.equals(second));
        assertEquals(5, codes.consume(second, UUID.randomUUID()));
    }

    @Test
    void failedAttemptsAreLimited() {
        var now = new AtomicLong();
        var codes = new LinkCodes(now::get);
        var player = UUID.randomUUID();
        for (var i = 0; i < LinkCodes.MAX_FAILURES; i++) {
            assertEquals(AiException.BAD_REQUEST, assertThrows(AiException.class, () -> codes.consume("ZZZZZZ", player)).code());
        }
        var good = codes.create(9);
        assertEquals(AiException.RATE_LIMITED, assertThrows(AiException.class, () -> codes.consume(good, player)).code());
        // another player is unaffected
        assertDoesNotThrow(() -> codes.consume(good, UUID.randomUUID()));
        now.addAndGet(LinkCodes.FAILURE_WINDOW_MS + 1);
        assertEquals(AiException.BAD_REQUEST, assertThrows(AiException.class, () -> codes.consume("ZZZZZZ", player)).code());
    }
}
