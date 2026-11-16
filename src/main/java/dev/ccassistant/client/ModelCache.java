package dev.ccassistant.client;

import dev.ccassistant.provider.ModelInfo;
import dev.ccassistant.provider.ProviderClient;
import dev.ccassistant.provider.ProviderProfile;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Model lists per provider endpoint, fetched in the background and kept for 24 hours. */
public final class ModelCache {
    private static final long TTL_MS = 24 * 3600_000L;

    private record Entry(long fetched, List<ModelInfo> models) {
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private ModelCache() {
    }

    public static CompletableFuture<List<ModelInfo>> get(ProviderProfile profile, String key, boolean refresh) {
        var cacheKey = profile.protocol().id() + " " + profile.baseUrl() + " " + Integer.toHexString(key.hashCode());
        var hit = CACHE.get(cacheKey);
        if (!refresh && hit != null && System.currentTimeMillis() - hit.fetched() < TTL_MS) {
            return CompletableFuture.completedFuture(hit.models());
        }
        return ProviderClient.models(profile, key, Duration.ofSeconds(30)).result().thenApply(models -> {
            CACHE.put(cacheKey, new Entry(System.currentTimeMillis(), List.copyOf(models)));
            return models;
        });
    }
}
