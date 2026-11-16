package dev.ccassistant.client.screen;

import dev.ccassistant.Config;
import dev.ccassistant.client.ClientState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client limits and this session's counts (docs/CLIENT_UI.md section 2.4). */
public final class LimitsScreen extends Screen {
    private final Screen parent;
    private String perMinute = Integer.toString(Config.CLIENT_REQUESTS_PER_MINUTE.get());
    private String perHour = Integer.toString(Config.CLIENT_REQUESTS_PER_HOUR.get());
    private String maxBytes = Integer.toString(Config.CLIENT_MAX_REQUEST_BYTES.get());
    private boolean notify = Config.CLIENT_NOTIFY.get();
    private Component status = Component.empty();

    public LimitsScreen(Screen parent) {
        super(Component.literal("Client limits"));
        this.parent = parent;
    }

    private void number(int y, String value, java.util.function.Consumer<String> onChange) {
        var b = new EditBox(font, width / 2 + 20, y, 80, 18, Component.empty());
        b.setMaxLength(9);
        b.setValue(value);
        b.setFilter(s -> s.chars().allMatch(Character::isDigit));
        b.setResponder(onChange);
        addRenderableWidget(b);
    }

    @Override
    protected void init() {
        number(30, perMinute, v -> perMinute = v);
        number(54, perHour, v -> perHour = v);
        number(78, maxBytes, v -> maxBytes = v);
        addRenderableWidget(Checkbox.builder(Component.literal("Action-bar notice for each request"), font)
            .pos(width / 2 - 150, 102).selected(notify).onValueChange((b, v) -> notify = v).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 - 104, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(width / 2 + 4, height - 28, 100, 20).build());
    }

    private void save() {
        try {
            var m = Integer.parseInt(perMinute);
            var h = Integer.parseInt(perHour);
            var b = Integer.parseInt(maxBytes);
            if (m < 1 || m > 1000 || h < 1 || h > 100_000 || b < 1024 || b > 16 * 1024 * 1024) throw new NumberFormatException();
            Config.CLIENT_REQUESTS_PER_MINUTE.set(m);
            Config.CLIENT_REQUESTS_PER_HOUR.set(h);
            Config.CLIENT_MAX_REQUEST_BYTES.set(b);
            Config.CLIENT_NOTIFY.set(notify);
            Config.CLIENT_SPEC.save();
            onClose();
        } catch (NumberFormatException e) {
            status = Component.literal("Per minute 1-1000, per hour 1-100000, request size 1024-16777216 bytes").withStyle(ChatFormatting.RED);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        g.drawString(font, "Requests per minute", width / 2 - 150, 35, 0xA0A0A0);
        g.drawString(font, "Requests per hour", width / 2 - 150, 59, 0xA0A0A0);
        g.drawString(font, "Largest request (bytes)", width / 2 - 150, 83, 0xA0A0A0);
        var y = 130;
        g.drawString(font, "This session:", width / 2 - 150, y, 0xFFFFFF);
        var counters = ClientState.requests().counters();
        if (counters.isEmpty()) g.drawString(font, "no requests yet", width / 2 - 140, y + 12, 0x808080);
        for (var e : counters.entrySet()) {
            y += 12;
            var c = e.getValue();
            g.drawString(font, e.getKey() + ": " + c.requests + " requests, " + c.inputTokens + " in / " + c.outputTokens + " out tokens",
                width / 2 - 140, y, 0xC0C0C0);
        }
        g.drawCenteredString(font, status, width / 2, height - 42, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
