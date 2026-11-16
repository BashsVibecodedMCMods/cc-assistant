package dev.ccassistant;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CCAssistant.MOD_ID)
public final class CCAssistant {
    public static final String MOD_ID = "ccassistant";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public CCAssistant(IEventBus modBus, ModContainer container) {
    }
}
