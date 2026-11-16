package dev.ccassistant.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import dev.ccassistant.CCAssistant;
import dev.ccassistant.Config;
import dev.ccassistant.client.screen.ProvidersScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only registration: config screen, key bindings, client command, login status. */
public final class ClientSetup {
    private static final String CATEGORY = "key.categories." + CCAssistant.MOD_ID;
    private static final KeyMapping OPEN = new KeyMapping("key." + CCAssistant.MOD_ID + ".open",
        InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);
    private static final KeyMapping KILL = new KeyMapping("key." + CCAssistant.MOD_ID + ".off",
        InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);
    private static boolean openNextTick;

    private ClientSetup() {
    }

    public static void init(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new ProvidersScreen(parent));
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            e.register(OPEN);
            e.register(KILL);
        });
        NeoForge.EVENT_BUS.addListener(ClientSetup::onTick);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onCommands);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> {
            ClientNet.reset();
            ClientState.requests().forgetStatus();
            ClientState.requests().sendStatus(true);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            ClientState.requests().cancelAll();
            ClientState.requests().forgetStatus();
            ClientNet.reset();
        });
    }

    private static void onTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        while (OPEN.consumeClick()) openNextTick = true;
        while (KILL.consumeClick()) {
            ClientState.setEnabled(false);
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable("ccassistant.client.off"), true);
        }
        if (openNextTick && mc.screen == null) {
            openNextTick = false;
            mc.setScreen(new ProvidersScreen(null));
        }
    }

    private static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("assistant-client")
            .then(Commands.literal("config").executes(c -> {
                openNextTick = true; // chat screen closes first
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("on").executes(c -> {
                ClientState.setEnabled(true);
                c.getSource().sendSuccess(() -> Component.literal(ClientState.requests().ready()
                    ? "AI for your computers is on."
                    : "AI is switched on, but you have no usable default profile yet. Use /assistant-client config."), false);
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("off").executes(c -> {
                ClientState.setEnabled(false);
                c.getSource().sendSuccess(() -> Component.translatable("ccassistant.client.off"), false);
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("status").executes(c -> {
                var sb = new StringBuilder("Switch: ").append(Config.CLIENT_ENABLED.get() ? "on" : "off");
                var store = ClientState.store();
                sb.append("\nDefault profile: ").append(store.defaultName() == null ? "none" : store.defaultName());
                sb.append("\nReady: ").append(ClientState.requests().ready() ? "yes" : "no");
                sb.append("\nIn progress: ").append(ClientState.requests().inProgress());
                ClientState.requests().counters().forEach((name, n) -> sb.append("\n  ").append(name).append(": ")
                    .append(n.requests).append(" requests, ").append(n.inputTokens).append(" in / ")
                    .append(n.outputTokens).append(" out tokens"));
                var text = sb.toString();
                c.getSource().sendSuccess(() -> Component.literal(text), false);
                return Command.SINGLE_SUCCESS;
            })));
    }
}
