package dev.ccassistant.provider;

import java.util.Map;

final class Profiles {
    private Profiles() {
    }

    static ProviderProfile of(Preset preset, String baseUrl) {
        return new ProviderProfile("id1", "test", preset, preset.protocol(), baseUrl, "test-model", 1000, null,
            false, false, Map.of(), ProviderProfile.KeyMode.NONE, "", true).validated();
    }
}
