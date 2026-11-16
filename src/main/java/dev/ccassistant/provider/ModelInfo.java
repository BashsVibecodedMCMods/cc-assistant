package dev.ccassistant.provider;

import org.jetbrains.annotations.Nullable;

/**
 * A model as listed by a provider, normalised (docs/PROVIDERS.md section 5). Prices are USD per million tokens.
 * {@code tools} is null when the provider doesn't say.
 */
public record ModelInfo(String id, @Nullable String name, @Nullable Integer contextLength, @Nullable Double inputPrice,
                        @Nullable Double outputPrice, @Nullable Boolean tools) {
}
