package dev.ccassistant.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.ccassistant.Config;
import dev.ccassistant.core.AiException;
import dev.ccassistant.provider.ModelSearch;
import dev.ccassistant.provider.ProviderClient;
import dev.ccassistant.server.AssistantRuntime;
import dev.ccassistant.server.ServerProviders;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.time.Duration;
import java.util.Locale;

/** {@code /assistant link|unlink|list|status|models|reload} (docs/CONFIG.md, "Commands"). */
public final class AssistantCommand {
    private AssistantCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("assistant")
            .then(Commands.literal("link")
                .then(Commands.argument("code", StringArgumentType.word()).executes(AssistantCommand::link)))
            .then(Commands.literal("unlink")
                .then(Commands.argument("computer", IntegerArgumentType.integer(0)).executes(AssistantCommand::unlink)))
            .then(Commands.literal("list").executes(AssistantCommand::list))
            .then(Commands.literal("status").executes(AssistantCommand::status))
            .then(Commands.literal("models").requires(s -> s.hasPermission(2))
                .then(Commands.argument("provider", StringArgumentType.word())
                    .executes(c -> models(c, ""))
                    .then(Commands.argument("search", StringArgumentType.greedyString())
                        .executes(c -> models(c, StringArgumentType.getString(c, "search"))))))
            .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(AssistantCommand::reload)));
    }

    private static AssistantRuntime rt(CommandSourceStack source) {
        var rt = AssistantRuntime.get();
        if (rt == null) source.sendFailure(Component.literal("Not ready yet"));
        return rt;
    }

    private static int link(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = c.getSource().getPlayerOrException();
        var rt = rt(c.getSource());
        if (rt == null) return 0;
        try {
            var computer = rt.link(StringArgumentType.getString(c, "code"), player.getUUID(), player.getGameProfile().getName());
            var note = switch (Config.BACKEND.get()) {
                case PLAYER, EITHER -> rt.owners().state(player.getUUID()) == dev.ccassistant.link.OwnerStates.State.READY
                    ? "" : " It will use your own provider profiles: open Mods > CC: Terminal Assistant > Config to set one up.";
                case SERVER -> " It will use the server's AI provider.";
                case NONE -> " AI isn't enabled on this server yet (backend = none).";
            };
            c.getSource().sendSuccess(() -> Component.literal("Linked computer #" + computer + " to you." + note), false);
            return 1;
        } catch (AiException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int unlink(CommandContext<CommandSourceStack> c) {
        var rt = rt(c.getSource());
        if (rt == null) return 0;
        var computer = IntegerArgumentType.getInteger(c, "computer");
        var owner = rt.links().owner(computer);
        var player = c.getSource().getPlayer();
        var isOwner = owner != null && player != null && owner.uuid().equals(player.getUUID());
        if (owner == null) {
            c.getSource().sendFailure(Component.literal("Computer #" + computer + " isn't linked."));
            return 0;
        }
        if (!isOwner && !c.getSource().hasPermission(2)) {
            c.getSource().sendFailure(Component.literal("Computer #" + computer + " isn't yours."));
            return 0;
        }
        rt.unlink(computer);
        c.getSource().sendSuccess(() -> Component.literal("Unlinked computer #" + computer + " (was " + owner.name() + ")."), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        var rt = rt(c.getSource());
        if (rt == null) return 0;
        var player = c.getSource().getPlayer();
        var all = c.getSource().hasPermission(2);
        var lines = new StringBuilder();
        var n = 0;
        for (var e : rt.links().all().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            if (!all && (player == null || !e.getValue().uuid().equals(player.getUUID()))) continue;
            lines.append("\n  #").append(e.getKey());
            if (all) lines.append(" - ").append(e.getValue().name());
            n++;
        }
        var count = n;
        var text = lines.toString();
        c.getSource().sendSuccess(() -> Component.literal((all ? "Linked computers: " : "Your linked computers: ") + count + text), false);
        return count;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        var rt = rt(c.getSource());
        if (rt == null) return 0;
        var sb = new StringBuilder("Backend: ").append(Config.BACKEND.get().name().toLowerCase(Locale.ROOT));
        var player = c.getSource().getPlayer();
        if (player != null) {
            sb.append("\nYour state: ").append(rt.owners().state(player.getUUID()).id());
            var mine = rt.links().all().entrySet().stream().filter(e -> e.getValue().uuid().equals(player.getUUID())).count();
            sb.append("\nYour linked computers: ").append(mine);
        }
        if (c.getSource().hasPermission(2)) {
            var p = rt.providers();
            sb.append("\nServer providers: ").append(p.providers().isEmpty() ? "none" : String.join(", ",
                p.providers().stream().map(x -> x.name()).toList()));
            if (p.loadError() != null) sb.append(" (file error, see log)");
        }
        var text = sb.toString();
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int models(CommandContext<CommandSourceStack> c, String search) {
        var rt = rt(c.getSource());
        if (rt == null) return 0;
        var provider = rt.providers().byName(StringArgumentType.getString(c, "provider"));
        if (provider == null) {
            c.getSource().sendFailure(Component.literal("No server provider by that name."));
            return 0;
        }
        String key;
        try {
            key = ServerProviders.key(provider);
        } catch (AiException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        var source = c.getSource();
        source.sendSuccess(() -> Component.literal("Fetching models...").withStyle(ChatFormatting.GRAY), false);
        ProviderClient.models(provider, key, Duration.ofSeconds(30)).result().whenComplete((models, error) ->
            rt.server().execute(() -> {
                if (error != null) {
                    source.sendFailure(Component.literal(ProviderClient.unwrap(error).getMessage()));
                    return;
                }
                var found = ModelSearch.filter(models, search, false);
                var sb = new StringBuilder(found.size() + " of " + models.size() + " models");
                found.stream().limit(30).forEach(m -> sb.append("\n  ").append(m.id())
                    .append(Boolean.TRUE.equals(m.tools()) ? "  [tools]" : Boolean.FALSE.equals(m.tools()) ? "  [no tools]" : ""));
                if (found.size() > 30) sb.append("\n  ... narrow the search to see more");
                var text = sb.toString();
                source.sendSuccess(() -> Component.literal(text), false);
            }));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        var rt = rt(c.getSource());
        if (rt == null) return 0;
        rt.reloadProviders();
        var p = rt.providers();
        if (p.loadError() != null) {
            c.getSource().sendFailure(Component.literal(ServerProviders.FILE + " has an error; see the server log."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Reloaded " + p.providers().size() + " server provider(s). "
            + "The TOML config reloads automatically when its file changes."), true);
        return 1;
    }
}
