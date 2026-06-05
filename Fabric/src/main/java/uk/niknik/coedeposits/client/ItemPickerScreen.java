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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Click-to-select item picker — a small vanilla {@link Screen} with a searchable
 * {@link ObjectSelectionList} of every registered item (icon + name + id).
 *
 * <p>Replaces YACL's {@code ItemController} dropdown, which renders ALL items
 * eagerly and CRASHES when a mod item's renderer assumes an in-world player — e.g.
 * Create's {@code handheld_worldshaper} does {@code player.getMainArm()} and NPEs
 * when the config is open from the main menu (no world → no player). Here the icon
 * is drawn ONLY when a player exists, so the main-menu case can't NPE; the list +
 * names still build fine without a player. Selecting an entry calls {@code onPick}
 * with the item id (the caller saves + navigates). Mirrors {@link FluidPickerScreen};
 * used by {@link DepositEditorScreens}.
 */
public final class ItemPickerScreen extends Screen {
    private final Screen parent;
    private final String currentItemId;
    private final Consumer<String> onPick;
    private final List<Item> items;

    private EditBox search;
    private ItemList list;

    public ItemPickerScreen(Screen parent, String currentItemId, Consumer<String> onPick) {
        super(Component.literal("Pick an item"));
        this.parent = parent;
        this.currentItemId = currentItemId == null ? "" : currentItemId;
        this.onPick = onPick;
        this.items = BuiltInRegistries.ITEM.stream()
                .filter(it -> it != Items.AIR)
                .sorted(Comparator.comparing(it -> BuiltInRegistries.ITEM.getKey(it).toString()))
                .toList();
    }

    @Override
    protected void init() {
        this.search = new EditBox(this.font, this.width / 2 - 150, 28, 300, 18, Component.literal("Search"));
        this.search.setHint(Component.literal("Search by name or id…"));
        this.search.setResponder(s -> rebuild());
        addRenderableWidget(this.search);

        this.list = new ItemList(this.minecraft, this.width, this.height - 52 - 32, 52, 24);
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
    private void choose(Item it) {
        this.onPick.accept(BuiltInRegistries.ITEM.getKey(it).toString());
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
            g.drawCenteredString(this.font, Component.literal("No items match"),
                    this.width / 2, this.height / 2, 0xA0A0A0);
        }
    }

    private final class ItemList extends ObjectSelectionList<ItemList.Row> {
        ItemList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, y + height, itemHeight);
        }

        void reload(String needle) {
            this.clearEntries();
            for (Item it : items) {
                String id = BuiltInRegistries.ITEM.getKey(it).toString();
                String name = new ItemStack(it).getHoverName().getString();
                if (!needle.isEmpty()
                        && !id.toLowerCase(Locale.ROOT).contains(needle)
                        && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                this.addEntry(new Row(it, id, name));
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
            private final Item item;
            private final ItemStack stack;
            private final String id;
            private final String name;

            Row(Item item, String id, String name) {
                this.item = item;
                this.stack = new ItemStack(item);
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
                    choose(this.item);
                    return true;
                }
                return false;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovered, float partial) {
                // Icon only when a player exists: some mod item renderers (e.g. Create's
                // handheld_worldshaper) call player.getMainArm() and NPE with no player
                // (config opened from the main menu). Names/ids still show either way.
                if (Minecraft.getInstance().player != null) {
                    g.renderFakeItem(this.stack, left + 2, top + (height - 16) / 2);
                }
                boolean current = this.id.equals(currentItemId);
                var font = ItemPickerScreen.this.font;
                g.drawString(font, this.name + (current ? "  §a(current)" : ""), left + 24, top + 3, 0xFFFFFF);
                g.drawString(font, "§8" + this.id, left + 24, top + 13, 0x808080);
            }
        }
    }
}
