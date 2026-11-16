package dev.ccassistant.client.screen;

import dev.ccassistant.client.ClientState;
import dev.ccassistant.provider.Preset;
import dev.ccassistant.provider.ProviderProfile;
import dev.ccassistant.provider.UrlRules;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Add or edit one profile (docs/CLIENT_UI.md section 2.2). The draft lives in fields so it survives opening the model
 * picker (which re-runs {@link #init()} on return). A stored key is never put back into the text box.
 */
public final class EditProfileScreen extends Screen {
    private static final List<String> EFFORTS = List.of("", "low", "medium", "high", "xhigh", "max");

    private final Screen parent;
    private final @Nullable ProviderProfile original;
    private final String id;

    // draft
    private String name;
    private Preset preset;
    private String baseUrl;
    private ProviderProfile.KeyMode keyMode;
    private String newKey = "";
    private boolean clearKey;
    private boolean showKey;
    private String envVar;
    private String model;
    private String maxTokens;
    private String effort;
    private boolean luaSelectable;
    private boolean useMaxCompletionTokens;
    private boolean refusalFallback;
    private String headers;
    private Component status = Component.empty();

    public EditProfileScreen(Screen parent, @Nullable ProviderProfile profile) {
        super(Component.literal(profile == null ? "Add profile" : "Edit profile"));
        this.parent = parent;
        this.original = profile;
        if (profile == null) {
            id = UUID.randomUUID().toString();
            preset = Preset.NANOGPT;
            name = suggestName(preset);
            baseUrl = preset.baseUrl();
            keyMode = ProviderProfile.KeyMode.STORED;
            envVar = "";
            model = "";
            maxTokens = "8192";
            effort = "";
            luaSelectable = true;
            useMaxCompletionTokens = false;
            refusalFallback = false;
            headers = "";
        } else {
            id = profile.id();
            name = profile.name();
            preset = profile.preset();
            baseUrl = profile.baseUrl();
            keyMode = profile.keyMode();
            envVar = profile.keyEnvVar();
            model = profile.model();
            maxTokens = Integer.toString(profile.maxTokens());
            effort = profile.effort() == null ? "" : profile.effort();
            luaSelectable = profile.luaSelectable();
            useMaxCompletionTokens = profile.useMaxCompletionTokens();
            refusalFallback = profile.refusalFallback();
            var sb = new StringBuilder();
            profile.headers().forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "; ").append(k).append(": ").append(v));
            headers = sb.toString();
        }
    }

    private static String suggestName(Preset preset) {
        var base = preset.id().replace("custom-", "").replace("-", "_");
        var store = ClientState.store();
        var name = base;
        for (var i = 2; store.byName(name) != null; i++) name = base + i;
        return name;
    }

    void setStatus(Component text) {
        status = text;
    }

    private EditBox box(int x, int y, int w, String value, int maxLength, java.util.function.Consumer<String> onChange) {
        var b = new EditBox(font, x, y, w, 18, Component.empty());
        b.setMaxLength(maxLength);
        b.setValue(value);
        b.setResponder(onChange);
        return addRenderableWidget(b);
    }

    @Override
    protected void init() {
        var left = width / 2 - 155;
        var lx = left + 70; // field column
        var y = 24;

        box(lx, y, 100, name, 32, v -> name = v);
        addRenderableWidget(CycleButton.<Preset>builder(p -> Component.literal(p.displayName()))
            .withValues(Arrays.asList(Preset.values())).withInitialValue(preset).displayOnlyValue()
            .create(lx + 105, y - 1, 135, 20, Component.literal("Provider"), (b, p) -> changePreset(p)));
        y += 24;
        box(lx, y, 240, baseUrl, 300, v -> baseUrl = v);
        y += 24;

        addRenderableWidget(CycleButton.<ProviderProfile.KeyMode>builder(m -> Component.literal(switch (m) {
                case STORED -> "Store key";
                case ENV -> "Env variable";
                case NONE -> "No key";
            })).withValues(ProviderProfile.KeyMode.values()).withInitialValue(keyMode).displayOnlyValue()
            .create(lx, y - 1, 80, 20, Component.literal("Key"), (b, m) -> {
                keyMode = m;
                rebuildWidgets();
            }));
        if (keyMode == ProviderProfile.KeyMode.STORED) {
            var keyBox = box(lx + 84, y, 106, newKey, 512, v -> {
                newKey = v;
                if (!v.isEmpty()) clearKey = false;
            });
            keyBox.setFormatter((text, offset) -> FormattedCharSequence.forward(showKey ? text : "*".repeat(text.length()), Style.EMPTY));
            var hint = original == null ? null : ClientState.store().keyHint(original);
            keyBox.setHint(Component.literal(clearKey ? "(will be removed)" : hint != null ? "****" + hint + " (unchanged)" : "paste key")
                .withStyle(ChatFormatting.DARK_GRAY));
            addRenderableWidget(Button.builder(Component.literal(showKey ? "Hide" : "Show"), b -> {
                showKey = !showKey;
                rebuildWidgets();
            }).bounds(lx + 194, y - 1, 22, 20).build());
            addRenderableWidget(Button.builder(Component.literal("X"), b -> {
                newKey = "";
                clearKey = true;
                rebuildWidgets();
            }).bounds(lx + 218, y - 1, 22, 20).build());
        } else if (keyMode == ProviderProfile.KeyMode.ENV) {
            var envBox = box(lx + 84, y, 156, envVar, 128, v -> envVar = v);
            envBox.setHint(Component.literal("e.g. NANOGPT_API_KEY").withStyle(ChatFormatting.DARK_GRAY));
        }
        y += 24;

        var modelBox = box(lx, y, 170, model, 200, v -> model = v);
        modelBox.setHint(Component.literal("model id").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(Button.builder(Component.literal("Choose..."), b -> openPicker()).bounds(lx + 174, y - 1, 66, 20).build());
        y += 24;

        var tokens = box(lx, y, 60, maxTokens, 6, v -> maxTokens = v);
        tokens.setFilter(s -> s.chars().allMatch(Character::isDigit));
        if (preset.protocol() == dev.ccassistant.provider.ProtocolKind.ANTHROPIC) {
            addRenderableWidget(CycleButton.<String>builder(e -> Component.literal(e.isEmpty() ? "Effort: default" : "Effort: " + e))
                .withValues(EFFORTS).withInitialValue(effort).displayOnlyValue()
                .create(lx + 66, y - 1, 100, 20, Component.literal("Effort"), (b, e) -> effort = e));
        }
        y += 24;

        addRenderableWidget(Checkbox.builder(Component.literal("Computers may choose this profile by name"), font)
            .pos(left, y).selected(luaSelectable).onValueChange((b, v) -> luaSelectable = v).build());
        y += 22;
        if (preset.protocol() == dev.ccassistant.provider.ProtocolKind.OPENAI) {
            addRenderableWidget(Checkbox.builder(Component.literal("Send max_completion_tokens (some OpenAI models need it)"), font)
                .pos(left, y).selected(useMaxCompletionTokens).onValueChange((b, v) -> useMaxCompletionTokens = v).build());
        } else {
            addRenderableWidget(Checkbox.builder(Component.literal("Refusal fallback (Anthropic API only)"), font)
                .pos(left, y).selected(refusalFallback).onValueChange((b, v) -> refusalFallback = v).build());
        }
        y += 24;
        var headerBox = box(lx, y, 240, headers, 1000, v -> headers = v);
        headerBox.setHint(Component.literal("Name: value; Name2: value (not for secrets)").withStyle(ChatFormatting.DARK_GRAY));

        var by = height - 28;
        addRenderableWidget(Button.builder(Component.literal("Test"), b -> test()).bounds(width / 2 - 155, by, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 + 5, by, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(width / 2 + 85, by, 70, 20).build());
    }

    private void changePreset(Preset p) {
        // Keep the URL if the player edited it; replace it if it still holds the old preset's default.
        if (baseUrl.isBlank() || baseUrl.equals(preset.baseUrl())) baseUrl = p.baseUrl();
        if (original == null && name.equals(suggestName(preset))) name = suggestName(p);
        if (original == null) refusalFallback = p == Preset.ANTHROPIC;
        if (!p.keyRequired() && keyMode == ProviderProfile.KeyMode.STORED && original == null && p != Preset.CUSTOM_OPENAI
            && p != Preset.CUSTOM_ANTHROPIC) keyMode = ProviderProfile.KeyMode.NONE;
        if (p.keyRequired() && keyMode == ProviderProfile.KeyMode.NONE) keyMode = ProviderProfile.KeyMode.STORED;
        preset = p;
        rebuildWidgets();
    }

    private ProviderProfile build() {
        int tokens;
        try {
            tokens = Integer.parseInt(maxTokens.isBlank() ? "0" : maxTokens);
        } catch (NumberFormatException e) {
            tokens = 0;
        }
        return new ProviderProfile(id, name.trim(), preset, preset.protocol(), baseUrl.trim(), model.trim(), tokens,
            effort.isEmpty() || preset.protocol() != dev.ccassistant.provider.ProtocolKind.ANTHROPIC ? null : effort,
            preset.protocol() == dev.ccassistant.provider.ProtocolKind.ANTHROPIC && refusalFallback,
            preset.protocol() == dev.ccassistant.provider.ProtocolKind.OPENAI && useMaxCompletionTokens,
            parseHeaders(headers), keyMode, envVar.trim(), luaSelectable).validated();
    }

    static Map<String, String> parseHeaders(String text) {
        var out = new LinkedHashMap<String, String>();
        if (text == null || text.isBlank()) return out;
        for (var part : text.split(";")) {
            if (part.isBlank()) continue;
            var colon = part.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("Headers must look like Name: value");
            var key = part.substring(0, colon).trim();
            var value = part.substring(colon + 1).trim();
            UrlRules.checkHeaderName(key);
            UrlRules.checkHeaderValue(value);
            out.put(key, value);
        }
        return out;
    }

    /** The key the draft would use: the new one typed, or the stored one. */
    private String draftKey(ProviderProfile p) {
        if (p.keyMode() == ProviderProfile.KeyMode.STORED) {
            if (!newKey.isBlank()) return newKey.trim();
            if (clearKey || original == null) return "";
            return ClientState.store().key(original);
        }
        return ClientState.store().key(p);
    }

    private void openPicker() {
        ProviderProfile draft;
        try {
            var fake = model.isBlank() ? "placeholder" : model;
            var saved = model;
            model = fake;
            try {
                draft = build();
            } finally {
                model = saved;
            }
        } catch (IllegalArgumentException e) {
            status = Component.literal(e.getMessage()).withStyle(ChatFormatting.RED);
            return;
        }
        minecraft.setScreen(new ModelPickerScreen(this, draft, draftKey(draft), id -> model = id));
    }

    private void test() {
        try {
            var p = build();
            var key = draftKey(p);
            minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
                minecraft.setScreen(this);
                if (yes) ProvidersScreen.runTest(p, key, this);
            }, Component.literal("Test " + p.name() + "?"), Component.literal(
                "This sends one short request (\"Reply with OK\") with model " + p.model() + ". It may cost a tiny amount.")));
        } catch (IllegalArgumentException e) {
            status = Component.literal(e.getMessage()).withStyle(ChatFormatting.RED);
        }
    }

    private void save() {
        try {
            var p = build();
            String keyChange = null;
            if (p.keyMode() == ProviderProfile.KeyMode.STORED) {
                if (!newKey.isBlank()) keyChange = newKey;
                else if (clearKey) keyChange = "";
            }
            if (p.keyMode() == ProviderProfile.KeyMode.STORED && p.preset().keyRequired() && keyChange == null
                && (original == null || ClientState.store().keyHint(original) == null)) {
                throw new IllegalArgumentException("Paste your API key for " + p.preset().displayName());
            }
            ClientState.store().save(p, keyChange);
            newKey = "";
            ClientState.statusChanged();
            minecraft.setScreen(parent);
        } catch (Exception e) {
            status = Component.literal(e.getMessage() == null ? e.toString() : e.getMessage()).withStyle(ChatFormatting.RED);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        var left = width / 2 - 155;
        var labels = new String[]{"Name", "Base URL", "API key", "Model", "Max tokens"};
        for (var i = 0; i < labels.length; i++) g.drawString(font, labels[i], left, 29 + i * 24, 0xA0A0A0);
        g.drawString(font, "Headers", left, 29 + 5 * 24 + 46, 0xA0A0A0);
        g.drawCenteredString(font, Component.literal("Follow your provider's terms. Some subscription plans may only be used in specific apps.")
            .withStyle(ChatFormatting.GOLD), width / 2, height - 56, 0xFFFFFF);
        g.drawCenteredString(font, status, width / 2, height - 42, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        newKey = "";
        minecraft.setScreen(parent);
    }
}
