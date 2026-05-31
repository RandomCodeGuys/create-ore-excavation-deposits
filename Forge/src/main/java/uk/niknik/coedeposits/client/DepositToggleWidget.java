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
        // ── Phase 1: snapshot state used by every following phase ───────────
        // Read the cache flag fresh each frame so toggle changes (from keybind
        // or another click) appear without a re-init. isHoveredOrFocused gives
        // us both mouse hover AND keyboard focus paths — same visual either way.
        boolean on = DepositClientCache.isEnabled();
        boolean hover = this.isHoveredOrFocused();

        int x0 = getX();
        int y0 = getY();
        int x1 = x0 + SIZE;
        int y1 = y0 + SIZE;

        // ── Phase 2: background tint ────────────────────────────────────────
        // Brighter on hover so the widget responds visibly to mouse-over even
        // though the border + glyph colour aren't hover-aware.
        g.fill(x0, y0, x1, y1, hover ? BG_HOVER : BG_NORMAL);

        // ── Phase 3: 1-pixel state-coloured border ──────────────────────────
        // Cyan when overlay is ON, grey when OFF — the same colour drives
        // the glyph below so the on/off state reads as a single visual unit.
        int border = on ? COLOR_ON : COLOR_OFF;
        g.hLine(x0, x1 - 1, y0, border);          // top
        g.hLine(x0, x1 - 1, y1 - 1, border);      // bottom
        g.vLine(x0, y0, y1 - 1, border);          // left
        g.vLine(x1 - 1, y0, y1 - 1, border);      // right

        // ── Phase 4: centred "D" glyph ──────────────────────────────────────
        // tx/ty math centres the 1-char label inside the 16-px box. The +1
        // on ty compensates for MC's font baseline sitting near the top of
        // its declared line-height — visually centres rather than mathematically.
        Font font = Minecraft.getInstance().font;
        String label = "D";
        int tx = x0 + (SIZE - font.width(label)) / 2;
        int ty = y0 + (SIZE - font.lineHeight) / 2 + 1;
        g.drawString(font, label, tx, ty, border, false);

        // ── Phase 5: tooltip ────────────────────────────────────────────────
        // setTooltip on every frame is cheap and keeps the tooltip live with
        // the current on/off state (otherwise toggling via keybind wouldn't
        // refresh the tooltip text until the next focus event).
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
