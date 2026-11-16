package dev.ccassistant.provider;

import com.google.gson.JsonObject;
import dev.ccassistant.core.AiException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Runs chat and model-list requests against one profile. Shared by the server backend and the player's client. */
public final class ProviderClient {
    private ProviderClient() {
    }

    /**
     * Sends a validated request (the mod's format) and completes with the response in the mod's format.
     *
     * @param key the API key, or "" for none. Never logged.
     */
    public static Pending<JsonObject> chat(ProviderProfile profile, String key, JsonObject request, int maxTokens,
                                           Duration timeout, int maxResponseBytes) {
        var protocol = profile.protocol().protocol();
        var call = ProviderHttp.send(protocol.chatRequest(profile, key, request, maxTokens), timeout, maxResponseBytes, key, protocol);
        var result = call.result().thenApply(json -> {
            try {
                return protocol.parseChat(json);
            } catch (AiException e) {
                throw new CompletionException(new AiException(e.code(), ProviderHttp.scrub(e.getMessage(), key)));
            }
        });
        return new Pending<>(result, () -> {
            call.cancel();
            result.completeExceptionally(new AiException(AiException.CANCELLED, "Cancelled"));
        });
    }

    public static Pending<List<ModelInfo>> models(ProviderProfile profile, String key, Duration timeout) {
        var protocol = profile.protocol().protocol();
        var call = ProviderHttp.send(protocol.modelsRequest(profile, key), timeout, 16 * 1024 * 1024, key, protocol);
        CompletableFuture<List<ModelInfo>> result = call.result().thenApply(json -> {
            try {
                return protocol.parseModels(json, profile);
            } catch (AiException e) {
                throw new CompletionException(new AiException(e.code(), ProviderHttp.scrub(e.getMessage(), key)));
            }
        });
        return new Pending<>(result, call::cancel);
    }

    /** Unwraps a future's failure into an {@link AiException}. */
    public static AiException unwrap(Throwable error) {
        var e = error;
        while ((e instanceof CompletionException || e instanceof java.util.concurrent.ExecutionException) && e.getCause() != null) {
            e = e.getCause();
        }
        if (e instanceof AiException ai) return ai;
        if (e instanceof java.util.concurrent.CancellationException) return new AiException(AiException.CANCELLED, "Cancelled");
        return new AiException(AiException.BAD_RESPONSE, "Unexpected error (" + e.getClass().getSimpleName() + ")");
    }
}
