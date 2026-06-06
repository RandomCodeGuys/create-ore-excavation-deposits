package uk.niknik.coedeposits.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import dev.isxander.yacl3.api.Binding;
import dev.isxander.yacl3.api.Controller;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;

/**
 * A YACL {@link Controller} that renders one config entry as a SINGLE row: the
 * entry's label on the left and a right-aligned strip of action buttons on the
 * right. YACL ships no multi-button row (every {@code ButtonOption} is a full-width
 * row of its own), so this custom controller hosts vanilla {@link Button}s inside
 * its widget and forwards clicks to them by their own bounds.
 *
 * <p>Used by the deposit list, where each deposit is one row —
 * {@code "coedeposits:iron   [Edit →][⊘ Disable][✖ Delete]"} — instead of a group
 * header plus three stacked button rows.
 *
 * <p>The option carries no real value: it is bound with {@link Binding#immutable}
 * to its own label, and {@link #row} is the only intended way to build one. The
 * widget draws the label itself (a bare {@link AbstractWidget}, unlike YACL's
 * {@code ControllerWidget}, does not), clipping it so a long id can't run under the
 * buttons.
 */
public final class DepositRowController implements Controller<Component> {

    /** One button in the row: the text drawn on it and what a click does. */
    public record Action(Component label, Runnable onPress) {}

    private final Option<Component> option;
    private final List<Action> actions;

    private DepositRowController(Option<Component> option, List<Action> actions) {
        this.option = option;
        this.actions = actions;
    }

    /**
     * Build a value-less option that renders as a one-line action row.
     *
     * @param label   the row label (its {@code §}-codes are honoured, e.g. {@code §8(default)})
     * @param actions the buttons, left-to-right
     */
    public static Option<Component> row(Component label, List<Action> actions) {
        return Option.<Component>createBuilder()
                .name(label)
                .description(OptionDescription.of(label))
                .binding(Binding.immutable(label))
                .customController(opt -> new DepositRowController(opt, actions))
                .build();
    }

    @Override
    public Option<Component> option() {
        return option;
    }

    @Override
    public Component formatValue() {
        return option.name();
    }

    @Override
    public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> dim) {
        return new RowWidget(dim);
    }

    /** The row widget: draws the label (left, clipped) and hosts the buttons (right). */
    private final class RowWidget extends AbstractWidget {
        private static final int BTN_HEIGHT = 18;
        private static final int GAP = 4;          // between buttons
        private static final int EDGE_PAD = 6;     // inset from the row's right edge
        private static final int NAME_X = 8;       // label inset from the row's left edge
        private static final int LABEL_HPAD = 8;   // horizontal padding inside each button

        private final List<Button> buttons = new ArrayList<>();
        /** Left edge of the button strip — the label is clipped to stop short of it. */
        private int stripLeft;
        private boolean focused;

        RowWidget(Dimension<Integer> dim) {
            super(dim);
            for (Action a : actions) {
                int w = textRenderer.width(a.label()) + LABEL_HPAD * 2;
                buttons.add(Button.builder(a.label(), b -> a.onPress().run()).size(w, BTN_HEIGHT).build());
            }
            layout();
        }

        /** Right-align the button strip within the current dimension. */
        private void layout() {
            Dimension<Integer> dim = getDimension();
            int total = GAP * Math.max(0, buttons.size() - 1);
            for (Button b : buttons) total += b.getWidth();
            int x = dim.xLimit() - EDGE_PAD - total;
            stripLeft = x;
            int y = dim.y() + (dim.height() - BTN_HEIGHT) / 2;
            for (Button b : buttons) {
                b.setX(x);
                b.setY(y);
                x += b.getWidth() + GAP;
            }
        }

        @Override
        public void setDimension(Dimension<Integer> dim) {
            super.setDimension(dim);
            layout();
        }

        @Override
        public void render(GuiGraphics gg, int mouseX, int mouseY, float delta) {
            Dimension<Integer> dim = getDimension();
            int nameX = dim.x() + NAME_X;
            int textY = dim.y() + (dim.height() - textRenderer.lineHeight) / 2;
            int clipRight = Math.max(nameX, stripLeft - GAP);
            // Clip so a long id stops short of the buttons instead of running under them.
            gg.enableScissor(dim.x(), dim.y(), clipRight, dim.yLimit());
            gg.drawString(textRenderer, option.name().getString(), nameX, textY, 0xFFFFFF);
            gg.disableScissor();
            for (Button b : buttons) {
                b.render(gg, mouseX, mouseY, delta);
            }
        }

        @Override
        public boolean onMouseClicked(double mouseX, double mouseY, int button) {
            for (Button b : buttons) {
                if (b.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            for (Button b : buttons) {
                if (b.isMouseOver(mouseX, mouseY)) {
                    return true;
                }
            }
            return super.isMouseOver(mouseX, mouseY);
        }

        @Override
        public boolean matchesSearch(String query) {
            return option.name().getString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
        }

        @Override
        public void setFocused(boolean focused) {
            this.focused = focused;
        }

        @Override
        public boolean isFocused() {
            return focused;
        }

        @Override
        public void updateNarration(NarrationElementOutput out) {
            // Mouse-first row; nothing extra to narrate beyond the label.
        }
    }
}
