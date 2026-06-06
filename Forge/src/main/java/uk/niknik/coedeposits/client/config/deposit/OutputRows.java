package uk.niknik.coedeposits.client.config.deposit;

import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import dev.isxander.yacl3.api.Binding;
import dev.isxander.yacl3.api.Controller;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;

import uk.niknik.coedeposits.client.ItemPickerScreen;

/**
 * The drilling-output "header" row as a custom one-row widget: the item's icon + name on the
 * left (click → {@link ItemPickerScreen}) and a small ✖ Remove button on the right — so an item
 * picker and a remove control share a single row, which YACL's one-control-per-row layout can't
 * do. It's a value-less controller (the item lives in the draft); clicks are forwarded through
 * the callbacks. Count / Chance below it use native YACL number fields, which handle keyboard
 * focus and persistence reliably (a hand-hosted EditBox here did not).
 *
 * <p><b>YACL 3.6.6 (1.20.1) note.</b> No {@code onMouseClicked} hook in this release — we
 * override vanilla {@link ItemRowWidget#mouseClicked}, reached via the row's normal dispatch.
 */
public final class OutputRows {
    private OutputRows() {}

    /** {@code [item icon+name → picker][✖]}. */
    public static Option<Component> itemRow(String itemId, Consumer<String> onPicked, Runnable onRemove) {
        return Option.<Component>createBuilder()
                .name(Component.literal("Item"))
                .description(OptionDescription.of(Component.literal("Click the item to change it; ✖ removes this output.")))
                .binding(Binding.immutable(Component.literal(itemId)))
                .customController(opt -> new ItemRowController(opt, itemId, onPicked, onRemove))
                .build();
    }

    private record ItemRowController(Option<Component> option, String itemId,
                                     Consumer<String> onPicked, Runnable onRemove) implements Controller<Component> {
        @Override public Component formatValue() { return option.name(); }

        @Override
        public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> dim) {
            return new ItemRowWidget(screen, dim, DepositBindings.itemFromId(itemId), itemId, onPicked, onRemove);
        }
    }

    private static final class ItemRowWidget extends AbstractWidget {
        private static final int PAD = 4;
        private static final int ICON = 16;

        private final YACLScreen screen;
        private final ItemStack stack;
        private final String itemId;
        private final String itemName;
        private final Consumer<String> onPicked;
        private final Button removeButton;
        private int itemRegionRight;   // the clickable item region ends here (just left of the ✖)
        private boolean focused;

        ItemRowWidget(YACLScreen screen, Dimension<Integer> dim, Item item, String itemId,
                      Consumer<String> onPicked, Runnable onRemove) {
            super(dim);
            this.screen = screen;
            this.stack = new ItemStack(item);
            this.itemId = itemId;
            this.itemName = this.stack.getHoverName().getString();
            this.onPicked = onPicked;
            this.removeButton = Button.builder(Component.literal("✖"), b -> onRemove.run()).size(20, 18).build();
            layout();
        }

        private void layout() {
            Dimension<Integer> dim = getDimension();
            int x = dim.xLimit() - PAD - 20;
            removeButton.setX(x);
            removeButton.setY(dim.y() + (dim.height() - 18) / 2);
            itemRegionRight = x - PAD;
        }

        @Override
        public void setDimension(Dimension<Integer> dim) {
            super.setDimension(dim);
            layout();
        }

        @Override
        public void render(GuiGraphics gg, int mouseX, int mouseY, float delta) {
            Dimension<Integer> dim = getDimension();
            int iconX = dim.x() + PAD;
            gg.renderItem(stack, iconX, dim.y() + (dim.height() - ICON) / 2);
            int textX = iconX + ICON + 6;
            int textY = dim.y() + (dim.height() - textRenderer.lineHeight) / 2;
            gg.enableScissor(dim.x(), dim.y(), Math.max(textX, itemRegionRight), dim.yLimit());
            gg.drawString(textRenderer, itemName, textX, textY, 0xFFFFFF);
            gg.disableScissor();
            removeButton.render(gg, mouseX, mouseY, delta);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (removeButton.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            Dimension<Integer> dim = getDimension();
            if (button == 0 && mouseX >= dim.x() && mouseX < itemRegionRight
                    && mouseY >= dim.y() && mouseY < dim.yLimit()) {
                Minecraft.getInstance().setScreen(new ItemPickerScreen(screen, itemId, onPicked));
                return true;
            }
            return false;
        }

        @Override
        public boolean matchesSearch(String query) {
            String q = query.toLowerCase(Locale.ROOT);
            return itemId.toLowerCase(Locale.ROOT).contains(q) || itemName.toLowerCase(Locale.ROOT).contains(q);
        }

        @Override public void setFocused(boolean focused) { this.focused = focused; }
        @Override public boolean isFocused() { return focused; }
        @Override public void updateNarration(NarrationElementOutput out) {}
    }
}
