package dev.ccassistant.client.screen;

import dev.ccassistant.client.ModelCache;
import dev.ccassistant.provider.ModelInfo;
import dev.ccassistant.provider.ModelSearch;
import dev.ccassistant.provider.ProviderClient;
import dev.ccassistant.provider.ProviderProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Searchable model list (docs/CLIENT_UI.md section 2.3). Loads in the background; never blocks the game. */
public final class ModelPickerScreen extends Screen {
    private final Screen parent;
    private final ProviderProfile profile;
    private final String key;
    private final Consumer<String> onSelect;
    private List<ModelInfo> all = List.of();
    private String query = "";
    private boolean toolsOnly = true;
    private String manual = "";
    private Component message = Component.literal("Loading...").withStyle(ChatFormatting.GRAY);
    private boolean loading;
    private ModelList list;

    public ModelPickerScreen(Screen parent, ProviderProfile profile, String key, Consumer<String> onSelect) {
        super(Component.literal("Choose model: " + profile.name()));
        this.parent = parent;
        this.profile = profile;
        this.key = key;
        this.onSelect = onSelect;
        load(false);
    }

    private void load(boolean refresh) {
        loading = true;
        message = Component.literal("Loading...").withStyle(ChatFormatting.GRAY);
        ModelCache.get(profile, key, refresh).whenComplete((models, error) -> Minecraft.getInstance().execute(() -> {
            loading = false;
            if (error != null) {
                all = List.of();
                message = Component.literal(ProviderClient.unwrap(error).getMessage() + " - type a model id below.").withStyle(ChatFormatting.RED);
            } else {
                all = models;
                message = Component.empty();
            }
            refill();
        }));
    }

    @Override
    protected void init() {
        var search = new EditBox(font, width / 2 - 155, 22, 170, 18, Component.literal("Search"));
        search.setValue(query);
        search.setHint(Component.literal("search").withStyle(ChatFormatting.DARK_GRAY));
        search.setResponder(v -> {
            query = v;
            refill();
        });
        addRenderableWidget(search);
        addRenderableWidget(Checkbox.builder(Component.literal("Tools only"), font).pos(width / 2 + 20, 22).selected(toolsOnly)
            .onValueChange((b, v) -> {
                toolsOnly = v;
                refill();
            }).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> load(true)).bounds(width / 2 + 100, 21, 55, 20).build());

        list = new ModelList(minecraft, width, height - 120, 46);
        addRenderableWidget(list);
        refill();

        var manualBox = new EditBox(font, width / 2 - 60, height - 52, 150, 18, Component.literal("Model id"));
        manualBox.setMaxLength(200);
        manualBox.setValue(manual);
        manualBox.setResponder(v -> manual = v);
        addRenderableWidget(manualBox);
        addRenderableWidget(Button.builder(Component.literal("Use"), b -> {
            if (!manual.isBlank()) pick(manual.trim());
        }).bounds(width / 2 + 95, height - 53, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 - 155, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Select"), b -> {
            var e = list.getSelected();
            if (e != null) pick(e.model.id());
        }).bounds(width / 2 + 55, height - 28, 100, 20).build());
        setInitialFocus(search);
    }

    private void refill() {
        if (list == null) return;
        list.setModels(ModelSearch.filter(all, query, toolsOnly));
    }

    private void pick(String id) {
        onSelect.accept(id);
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        var shown = list == null ? 0 : list.children().size();
        var info = loading ? message : !message.getString().isEmpty() ? message
            : Component.literal(shown + " of " + all.size() + " models. Prices per million input/output tokens.").withStyle(ChatFormatting.GRAY);
        g.drawCenteredString(font, info, width / 2, height - 70, 0xFFFFFF);
        g.drawString(font, "Not listed? Type an id:", width / 2 - 155, height - 47, 0xA0A0A0);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private static String price(Double d) {
        if (d == null) return "?";
        return d >= 10 ? String.format(Locale.ROOT, "$%.0f", d) : String.format(Locale.ROOT, "$%.2f", d);
    }

    private final class ModelList extends ObjectSelectionList<ModelList.Row> {
        ModelList(Minecraft mc, int width, int height, int y) {
            super(mc, width, height, y, 14);
        }

        void setModels(List<ModelInfo> models) {
            clearEntries();
            setScrollAmount(0);
            for (var m : models.size() > 2000 ? models.subList(0, 2000) : models) addEntry(new Row(m));
        }

        @Override
        public int getRowWidth() {
            return Math.min(420, width - 20);
        }

        final class Row extends ObjectSelectionList.Entry<Row> {
            final ModelInfo model;
            private long lastClick;

            Row(ModelInfo model) {
                this.model = model;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovering, float partialTick) {
                var id = model.id().length() > 34 ? model.id().substring(0, 33) + "…" : model.id();
                g.drawString(font, id, left + 2, top + 2, 0xFFFFFF);
                if (model.contextLength() != null) {
                    var ctx = model.contextLength() >= 1000 ? model.contextLength() / 1000 + "k" : model.contextLength().toString();
                    g.drawString(font, ctx, left + 200, top + 2, 0xAAAAAA);
                }
                if (model.inputPrice() != null || model.outputPrice() != null) {
                    g.drawString(font, price(model.inputPrice()) + "/" + price(model.outputPrice()), left + 240, top + 2, 0xAAAAAA);
                }
                var tools = model.tools() == null ? "tools ?" : model.tools() ? "tools ✓" : "no tools";
                g.drawString(font, tools, left + width - 50, top + 2,
                    model.tools() == null ? 0xAAAAAA : model.tools() ? 0x55FF55 : 0xFF5555);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                ModelList.this.setSelected(this);
                var now = System.currentTimeMillis();
                if (now - lastClick < 300) pick(model.id()); // double-click selects
                lastClick = now;
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(model.id());
            }
        }
    }
}
