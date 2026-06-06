package uk.niknik.coedeposits.client;

import java.util.ArrayList;
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
 * Click-to-select item picker: a searchable list of every registered item, each shown by its
 * real inventory icon + name + id. Selecting an entry calls {@code onPick} with the item id;
 * cancelling returns to {@code parent}. The deposit editor uses this for the drilling-output
 * rows, where the item picker must sit on the same row as a Remove button (so YACL's inline
 * item controller — which takes a whole row — can't be used).
 *
 * <p>The item list (icon + id + display name) is computed <b>once per session</b> into a
 * static cache: building a hover name can be slow and a few modded items throw while doing it,
 * so doing it per keystroke (in {@code reload}) both lagged and truncated the list at the first
 * misbehaving item. The cache guards each name in a try/catch (falling back to the id) so every
 * item always appears.
 */
public final class ItemPickerScreen extends Screen {

    /** One cached, render-ready item: icon stack + id + (safely resolved) display name. */
    private record ItemEntry(ItemStack stack, String id, String name) {}

    /** Built once — the item registry is immutable at runtime. */
    private static List<ItemEntry> cache;

    private static List<ItemEntry> allItems() {
        if (cache == null) {
            List<ItemEntry> list = new ArrayList<>();
            for (Item it : BuiltInRegistries.ITEM) {
                if (it == Items.AIR) continue;
                String id = BuiltInRegistries.ITEM.getKey(it).toString();
                ItemStack stack = new ItemStack(it);
                String name;
                try {
                    name = stack.getHoverName().getString();
                } catch (Exception e) {
                    name = id;   // never let one misbehaving item drop out of (or break) the list
                }
                list.add(new ItemEntry(stack, id, name));
            }
            list.sort(Comparator.comparing(ItemEntry::id));
            cache = List.copyOf(list);
        }
        return cache;
    }

    private final Screen parent;
    private final String currentItemId;
    private final Consumer<String> onPick;

    private EditBox search;
    private ItemList list;

    public ItemPickerScreen(Screen parent, String currentItemId, Consumer<String> onPick) {
        super(Component.literal("Pick an item"));
        this.parent = parent;
        this.currentItemId = currentItemId == null ? "" : currentItemId;
        this.onPick = onPick;
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
    private void choose(String id) {
        this.onPick.accept(id);
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
        // 1.20.1 ctor: (Minecraft, width, height, y0, y1, itemHeight) — 1.21.1 dropped the y1 arg.
        ItemList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, y + height, itemHeight);
        }

        void reload(String needle) {
            this.clearEntries();
            for (ItemEntry e : allItems()) {
                if (!needle.isEmpty()
                        && !e.id().toLowerCase(Locale.ROOT).contains(needle)
                        && !e.name().toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                this.addEntry(new Row(e));
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
            private final ItemEntry entry;

            Row(ItemEntry entry) {
                this.entry = entry;
            }

            @Override
            public Component getNarration() {
                return Component.literal(entry.name());
            }

            @Override
            public boolean mouseClicked(double mx, double my, int button) {
                if (button == 0) {
                    choose(entry.id());
                    return true;
                }
                return false;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovered, float partial) {
                g.renderItem(entry.stack(), left + 2, top + (height - 16) / 2);
                boolean current = entry.id().equals(currentItemId);
                var font = ItemPickerScreen.this.font;
                g.drawString(font, entry.name() + (current ? "  §a(current)" : ""), left + 24, top + 3, 0xFFFFFF);
                g.drawString(font, "§8" + entry.id(), left + 24, top + 13, 0x808080);
            }
        }
    }
}
