package uk.niknik.coedeposits.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Compact 16×16 toggle widget for the world-map overlay, designed to match
 * the dark semi-transparent aesthetic of Xaero's own map UI rather than the
 * bulky vanilla Minecraft button chrome.
 *
 * <p>Layout:
 * <ul>
 *   <li>Semi-transparent dark background ({@code 0x90000000}, brighter on hover)</li>
 *   <li>1-px state-colored border (cyan when overlay is ON, gray when OFF)</li>
 *   <li>Centered {@code "D"} glyph in the same colour as the border</li>
 *   <li>Hover tooltip describes current state and action</li>
 * </ul>
 *
 * <p>Click flips {@link DepositClientCache#toggleEnabled()} and plays the
 * standard UI button-click sound. The widget itself re-reads cache state on
 * every render so the visual is always live (no stale label between toggle
 * and re-init like the previous vanilla-Button version).
 */
public final class DepositToggleWidget extends AbstractWidget {
    /** Side length of the square widget in screen pixels. */
    public static final int SIZE = 16;

    /** Border + glyph colour when the overlay is enabled. */
    private static final int COLOR_ON = 0xFF4DD2FF;   // soft cyan
    /** Border + glyph colour when the overlay is disabled. */
    private static final int COLOR_OFF = 0xFF707070;  // muted grey

    /** Background tint — normal vs hover. Black with 56%/75% alpha. */
    private static final int BG_NORMAL = 0x90000000;
    private static final int BG_HOVER = 0xC0000000;

    public DepositToggleWidget(int x, int y) {
        super(x, y, SIZE, SIZE, Component.translatable("coedeposits.map.toggle.button"));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean on = DepositClientCache.isEnabled();
        boolean hover = this.isHoveredOrFocused();

        int x0 = getX();
        int y0 = getY();
        int x1 = x0 + SIZE;
        int y1 = y0 + SIZE;

        // Solid background fill.
        g.fill(x0, y0, x1, y1, hover ? BG_HOVER : BG_NORMAL);

        // 1-px border, state-coloured.
        int border = on ? COLOR_ON : COLOR_OFF;
        g.hLine(x0, x1 - 1, y0, border);
        g.hLine(x0, x1 - 1, y1 - 1, border);
        g.vLine(x0, y0, y1 - 1, border);
        g.vLine(x1 - 1, y0, y1 - 1, border);

        // Centred "D" glyph.
        Font font = Minecraft.getInstance().font;
        String label = "D";
        int tx = x0 + (SIZE - font.width(label)) / 2;
        int ty = y0 + (SIZE - font.lineHeight) / 2 + 1;  // +1 for visual centering
        g.drawString(font, label, tx, ty, border, false);

        // Live tooltip — short, action-oriented.
        this.setTooltip(Tooltip.create(Component.translatable(
                on ? "coedeposits.map.toggle.tooltip.on"
                        : "coedeposits.map.toggle.tooltip.off")));
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        DepositClientCache.toggleEnabled();
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        this.defaultButtonNarrationText(out);
    }
}
