package dev.ccassistant;

import dan200.computercraft.api.ComputerCraftAPI;
import dev.ccassistant.api.AiAPI;
import dev.ccassistant.command.AssistantCommand;
import dev.ccassistant.net.Net;
import dev.ccassistant.net.TransferPayload;
import dev.ccassistant.server.AssistantRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CCAssistant.MOD_ID)
public final class CCAssistant {
    public static final String MOD_ID = "ccassistant";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public CCAssistant(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);

        modBus.addListener(this::commonSetup);
        modBus.addListener(Net::register);

        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        if (FMLEnvironment.dist.isClient()) dev.ccassistant.client.ClientSetup.init(modBus, container);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ComputerCraftAPI.registerAPIFactory(AiAPI::new);
    }

    private void onServerStarting(ServerStartingEvent event) {
        AssistantRuntime.start(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        AssistantRuntime.stop();
        Net.clear();
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        var rt = AssistantRuntime.get();
        if (rt == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        rt.links().updateName(player.getUUID(), player.getGameProfile().getName());
        rt.owners().login(player.getUUID(), player.connection.hasChannel(TransferPayload.TYPE));
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        var rt = AssistantRuntime.get();
        if (rt == null) return;
        rt.owners().logout(event.getEntity().getUUID());
        Net.forget(event.getEntity().getUUID());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        AssistantCommand.register(event.getDispatcher());
    }
}
