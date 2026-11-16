package dev.ccassistant.provider;

import java.util.concurrent.CompletableFuture;

/** An asynchronous result that can be cancelled. Cancelling completes the future with a "cancelled" error. */
public record Pending<T>(CompletableFuture<T> result, Runnable canceller) {
    public void cancel() {
        canceller.run();
    }
}
