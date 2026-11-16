package dev.ccassistant.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class QuotasTest {
    private static final Quotas.Limits LIMITS = new Quotas.Limits(1, 2, 3, 3, 4);

    @Test
    void concurrencyIsReleased() throws AiException {
        var q = new Quotas(new AtomicLong()::get);
        var p = UUID.randomUUID();
        q.acquire(1, p, LIMITS);
        assertEquals(AiException.BUSY, assertThrows(AiException.class, () -> q.acquire(1, p, LIMITS)).code());
        q.release(1, p);
        q.acquire(1, p, LIMITS);
    }

    @Test
    void perPlayerAndTotalConcurrency() throws AiException {
        var q = new Quotas(new AtomicLong()::get);
        var p = UUID.randomUUID();
        q.acquire(1, p, LIMITS);
        q.acquire(2, p, LIMITS);
        assertEquals(AiException.BUSY, assertThrows(AiException.class, () -> q.acquire(3, p, LIMITS)).code());
        q.acquire(3, null, LIMITS);
        assertEquals(AiException.BUSY, assertThrows(AiException.class, () -> q.acquire(4, null, LIMITS)).code());
    }

    @Test
    void rateWindowSlides() throws AiException {
        var now = new AtomicLong(1_000_000);
        var q = new Quotas(now::get);
        for (var i = 0; i < 3; i++) {
            q.acquire(7, null, LIMITS);
            q.release(7, null);
        }
        var e = assertThrows(AiException.class, () -> q.acquire(7, null, LIMITS));
        assertEquals(AiException.RATE_LIMITED, e.code());
        assertTrue(e.getMessage().contains("60s"), e.getMessage());
        now.addAndGet(60_001);
        q.acquire(7, null, LIMITS);
    }

    @Test
    void playerRateCoversAllTheirComputers() throws AiException {
        var q = new Quotas(new AtomicLong()::get);
        var p = UUID.randomUUID();
        for (var i = 0; i < 4; i++) {
            q.acquire(10 + i, p, LIMITS);
            q.release(10 + i, p);
        }
        assertEquals(AiException.RATE_LIMITED, assertThrows(AiException.class, () -> q.acquire(20, p, LIMITS)).code());
    }
}
