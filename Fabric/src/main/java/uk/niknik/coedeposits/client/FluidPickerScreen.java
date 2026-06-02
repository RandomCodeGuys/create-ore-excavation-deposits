package uk.niknik.coedeposits.client;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Click-to-select fluid picker that shows each registered <b>source</b> fluid by
 * its own sprite + name — not its bucket item. YACL has no fluid controller, so
 * this is a small vanilla {@link Screen} with an {@link ObjectSelectionList} that
 * blits each fluid's still-texture from the block atlas (tinted, e.g. water's blue).
 * Selecting an entry calls {@code onPick} with the fluid id; cancelling returns to
 * {@code parent}. Used by {@link DepositEditorScreens} for the inline {@code fluid:}
 * block's fluid field.
 */
public final class FluidPickerScreen extends Screen {
    private final Screen parent;
    private final String currentFluidId;
    private final Consumer<String> onPick;
    private final List<Fluid> sourceFluids;

    private EditBox search;
    private FluidList list;

    public FluidPickerScreen(Screen parent, String currentFluidId, Consumer<String> onPick) {
        super(Component.literal("Pick a fluid"));
        this.parent = parent;
        this.currentFluidId = currentFluidId == null ? "" : currentFluidId;
        this.onPick = onPick;
        this.sourceFluids = BuiltInRegistries.FLUID.stream()
                .filter(f -> f != Fluids.EMPTY && f.defaultFluidState().isSource())
                .sorted(Comparator.comparing(f -> BuiltInRegistries.FLUID.getKey(f).toString()))
                .toList();
    }

    @Override
    protected void init() {
        this.search = new EditBox(this.font, this.width / 2 - 150, 28, 300, 18, Component.literal("Search"));
        this.search.setHint(Component.literal("Search by name or id…"));
        this.search.setResponder(s -> rebuild());
        addRenderableWidget(this.search);

        // (Minecraft, width, height, y, itemHeight) — rows hold a sprite + 2 text lines.
        this.list = new FluidList(this.minecraft, this.width, this.height - 52 - 32, 52, 24);
        addRenderableWidget(this.list);

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());

        rebuild();
        setInitialFocus(this.search);
    }

    private void rebuild() {
        this.list.reload(this.search == null ? "" : this.search.getValue().toLowerCase(Locale.ROOT));
    }

    /** Selection commits via the callback; the caller decides where to navigate next. */
    private void choose(Fluid f) {
        this.onPick.accept(BuiltInRegistries.FLUID.getKey(f).toString());
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);  // background + widgets (search, list, button)
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        if (this.list.count() == 0) {
            g.drawCenteredString(this.font, Component.literal("No source fluids match"),
                    this.width / 2, this.height / 2, 0xA0A0A0);
        }
    }

    private static Component nameOf(Fluid f) {
        // Fabric: fluid display name via the transfer API (the Forge line used
        // Fluid#getFluidType().getDescription(), which Fabric doesn't expose).
        return FluidVariantAttributes.getName(FluidVariant.of(f));
    }

    /**
     * Blit a fluid's still-texture (16×16, tinted) from the block atlas.
     *
     * <p><b>Fabric:</b> the still sprite + tint come from the transfer API's
     * {@link FluidVariantRendering} (the Forge line used
     * {@code IClientFluidTypeExtensions}).
     */
    private static void renderSprite(GuiGraphics g, Fluid f, int x, int y) {
        FluidVariant variant = FluidVariant.of(f);
        TextureAtlasSprite sprite = FluidVariantRendering.getSprite(variant);
        if (sprite == null) return;
        int tint = FluidVariantRendering.getColor(variant);
        float a = ((tint >> 24) & 0xFF) / 255f;
        if (a <= 0f) a = 1f;  // many fluids encode 0xRRGGBB with no alpha → treat as opaque
        float r = ((tint >> 16) & 0xFF) / 255f;
        float gr = ((tint >> 8) & 0xFF) / 255f;
        float b = (tint & 0xFF) / 255f;
        g.blit(x, y, 0, 16, 16, sprite, r, gr, b, a);
    }

    private final class FluidList extends ObjectSelectionList<FluidList.Row> {
        FluidList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, y + height, itemHeight);
        }

        void reload(String needle) {
            this.clearEntries();
            for (Fluid f : sourceFluids) {
                String id = BuiltInRegistries.FLUID.getKey(f).toString();
                String name = nameOf(f).getString();
                if (!needle.isEmpty()
                        && !id.toLowerCase(Locale.ROOT).contains(needle)
                        && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                this.addEntry(new Row(f, id, name));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.min(380, this.width - 20);
        }

        /** Public view of the (protected) entry count, for the empty-state message. */
        int count() {
            return this.getItemCount();
        }

        final class Row extends ObjectSelectionList.Entry<Row> {
            private final Fluid fluid;
            private final String id;
            private final String name;

            Row(Fluid fluid, String id, String name) {
                this.fluid = fluid;
                this.id = id;
                this.name = name;
            }

            @Override
            public Component getNarration() {
                return Component.literal(this.name);
            }

            @Override
            public boolean mouseClicked(double mx, double my, int button) {
                if (button == 0) {
                    choose(this.fluid);
                    return true;
                }
                return false;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovered, float partial) {
                renderSprite(g, this.fluid, left + 2, top + (height - 16) / 2);
                boolean current = this.id.equals(currentFluidId);
                var font = FluidPickerScreen.this.font;
                g.drawString(font, this.name + (current ? "  §a(current)" : ""), left + 24, top + 3, 0xFFFFFF);
                g.drawString(font, "§8" + this.id, left + 24, top + 13, 0x808080);
            }
        }
    }
}
