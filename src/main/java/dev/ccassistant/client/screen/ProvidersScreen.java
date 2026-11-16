package dev.ccassistant.client.screen;

import com.google.gson.JsonParser;
import dev.ccassistant.Config;
import dev.ccassistant.client.ClientState;
import dev.ccassistant.provider.ProviderClient;
import dev.ccassistant.provider.ProviderProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.UUID;

/** The main provider screen (docs/CLIENT_UI.md section 2.1). */
public final class ProvidersScreen extends Screen {
    private final @Nullable Screen parent;
    private ProfileList list;
    private Component status = Component.empty();
    private @Nullable String selectedId;

    public ProvidersScreen(@Nullable Screen parent) {
        super(Component.literal("AI Providers"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        var store = ClientState.store();
        addRenderableWidget(Checkbox.builder(Component.literal("Let my linked computers use these providers"), font)
            .pos(width / 2 - 150, 22).selected(Config.CLIENT_ENABLED.get())
            .onValueChange((box, on) -> ClientState.setEnabled(on)).build());

        list = new ProfileList(minecraft, width, height - 118, 44);
        for (var p : store.profiles()) list.addProfile(p, p.name().equals(store.defaultName()));
        list.selectById(selectedId);
        addRenderableWidget(list);

        var y = height - 70;
        var w = 64;
        var x = width / 2 - (w * 6 + 5 * 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Add"), b -> minecraft.setScreen(new EditProfileScreen(this, null))).bounds(x, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Edit"), b -> withSelected(p -> minecraft.setScreen(new EditProfileScreen(this, p)))).bounds(x + (w + 4), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Duplicate"), b -> withSelected(this::duplicate)).bounds(x + 2 * (w + 4), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> withSelected(this::confirmDelete)).bounds(x + 3 * (w + 4), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Set default"), b -> withSelected(this::makeDefault)).bounds(x + 4 * (w + 4), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Test"), b -> withSelected(p -> confirmTest(p, this))).bounds(x + 5 * (w + 4), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Limits..."), b -> minecraft.setScreen(new LimitsScreen(this))).bounds(width / 2 - 154, y + 24, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 + 54, y + 24, 100, 20).build());
    }

    private void withSelected(java.util.function.Consumer<ProviderProfile> action) {
        var e = list.getSelected();
        if (e == null) {
            status = Component.literal("Select a profile first.").withStyle(ChatFormatting.YELLOW);
            return;
        }
        selectedId = e.profile.id();
        action.accept(e.profile);
    }

    private void duplicate(ProviderProfile p) {
        var store = ClientState.store();
        var base = p.name().length() > 26 ? p.name().substring(0, 26) : p.name();
        var name = base + "_copy";
        for (var i = 2; store.byName(name) != null; i++) name = base + "_" + i;
        var copy = new ProviderProfile(UUID.randomUUID().toString(), name, p.preset(), p.protocol(), p.baseUrl(), p.model(),
            p.maxTokens(), p.effort(), p.refusalFallback(), p.useMaxCompletionTokens(), p.headers(),
            p.keyMode(), p.keyEnvVar(), p.luaSelectable());
        try {
            store.save(copy, p.keyMode() == ProviderProfile.KeyMode.STORED ? store.key(p) : null);
            selectedId = copy.id();
            status = Component.literal("Duplicated as " + name + ".");
        } catch (Exception e) {
            status = Component.literal("Couldn't save: " + e.getMessage()).withStyle(ChatFormatting.RED);
        }
        rebuildWidgets();
    }

    private void confirmDelete(ProviderProfile p) {
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                try {
                    ClientState.store().delete(p.id());
                    ClientState.statusChanged();
                    status = Component.literal("Deleted " + p.name() + ".");
                } catch (Exception e) {
                    status = Component.literal("Couldn't delete: " + e.getMessage()).withStyle(ChatFormatting.RED);
                }
            }
            minecraft.setScreen(this);
        }, Component.literal("Delete profile " + p.name() + "?"), Component.literal("Its stored key is deleted too.")));
    }

    private void makeDefault(ProviderProfile p) {
        try {
            ClientState.store().setDefault(p.name());
            ClientState.statusChanged();
            status = Component.literal(p.name() + " is now the default.");
        } catch (Exception e) {
            status = Component.literal("Couldn't save: " + e.getMessage()).withStyle(ChatFormatting.RED);
        }
        rebuildWidgets();
    }

    /** Asks first (a test request costs a tiny amount), then sends one short request and reports. */
    static void confirmTest(ProviderProfile p, Screen back) {
        var mc = Minecraft.getInstance();
        mc.setScreen(new ConfirmScreen(yes -> {
            mc.setScreen(back);
            if (yes) runTest(p, ClientState.store().key(p), back);
        }, Component.literal("Test " + p.name() + "?"),
            Component.literal("This sends one short request (\"Reply with OK\") to " + p.preset().displayName()
                + " with model " + p.model() + ". It may cost a tiny amount.")));
    }

    static void runTest(ProviderProfile p, String key, Screen back) {
        var mc = Minecraft.getInstance();
        setStatus(back, Component.literal("Testing " + p.name() + "...").withStyle(ChatFormatting.GRAY));
        var started = System.currentTimeMillis();
        var request = JsonParser.parseString("{\"messages\":[{\"role\":\"user\",\"content\":\"Reply with OK\"}]}").getAsJsonObject();
        ProviderClient.chat(p, key, request, 16, Duration.ofSeconds(60), 1 << 20).result().whenComplete((response, error) -> mc.execute(() -> {
            var ms = System.currentTimeMillis() - started;
            if (error != null) {
                setStatus(back, Component.literal("Test failed: " + ProviderClient.unwrap(error).getMessage()).withStyle(ChatFormatting.RED));
                return;
            }
            var text = new StringBuilder();
            for (var b : response.getAsJsonArray("content")) {
                var o = b.getAsJsonObject();
                if ("text".equals(o.get("type").getAsString())) text.append(o.get("text").getAsString());
            }
            var reply = text.toString().replaceAll("\\s+", " ").trim();
            if (reply.length() > 60) reply = reply.substring(0, 60) + "...";
            setStatus(back, Component.literal("OK in " + ms + " ms: \"" + reply + "\"").withStyle(ChatFormatting.GREEN));
        }));
    }

    private static void setStatus(Screen screen, Component text) {
        if (screen instanceof ProvidersScreen ps) ps.status = text;
        else if (screen instanceof EditProfileScreen es) es.setStatus(text);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        if (list.children().isEmpty()) {
            g.drawCenteredString(font, Component.literal("No profiles yet. Click Add to set up a provider.").withStyle(ChatFormatting.GRAY),
                width / 2, 70, 0xFFFFFF);
        }
        g.drawCenteredString(font, status, width / 2, height - 84, 0xFFFFFF);
        g.drawCenteredString(font, Component.literal("Keys are stored only on this computer (" + ClientState.store().dir()
            + "). They are never sent to the Minecraft server.").withStyle(ChatFormatting.DARK_GRAY), width / 2, height - 22, 0xFFFFFF);
        for (var w : ClientState.store().warnings()) {
            g.drawCenteredString(font, Component.literal(w).withStyle(ChatFormatting.GOLD), width / 2, height - 12, 0xFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private final class ProfileList extends ObjectSelectionList<ProfileList.Row> {
        ProfileList(Minecraft mc, int width, int height, int y) {
            super(mc, width, height, y, 18);
        }

        void addProfile(ProviderProfile p, boolean isDefault) {
            addEntry(new Row(p, isDefault));
        }

        void selectById(@Nullable String id) {
            if (id == null) return;
            for (var r : children()) if (r.profile.id().equals(id)) setSelected(r);
        }

        @Override
        public int getRowWidth() {
            return Math.min(400, width - 20);
        }

        final class Row extends ObjectSelectionList.Entry<Row> {
            final ProviderProfile profile;
            final boolean isDefault;

            Row(ProviderProfile profile, boolean isDefault) {
                this.profile = profile;
                this.isDefault = isDefault;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovering, float partialTick) {
                var store = ClientState.store();
                g.drawString(font, (isDefault ? "● " : "  ") + profile.name(), left + 2, top + 4, 0xFFFFFF);
                g.drawString(font, profile.preset().displayName(), left + 100, top + 4, 0xAAAAAA);
                var model = profile.model().length() > 28 ? profile.model().substring(0, 27) + "…" : profile.model();
                g.drawString(font, model, left + 210, top + 4, 0xCCCCCC);
                var keyOk = store.hasUsableKey(profile);
                g.drawString(font, keyOk ? "key ✓" : "no key", left + width - 46, top + 4, keyOk ? 0x55FF55 : 0xFF5555);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                ProfileList.this.setSelected(this);
                selectedId = profile.id();
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(profile.name() + ", " + profile.preset().displayName() + ", " + profile.model());
            }
        }
    }
}
