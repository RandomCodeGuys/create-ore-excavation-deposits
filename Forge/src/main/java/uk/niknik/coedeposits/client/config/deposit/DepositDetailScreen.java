package uk.niknik.coedeposits.client.config.deposit;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.isxander.yacl3.api.YetAnotherConfigLib;

import uk.niknik.coedeposits.client.DepositAuthoring.Draft;

/**
 * Assembles the per-deposit detail editor: the {@link CoreTab} + the {@link InlineTab} for
 * one {@link Draft}. Every field auto-saves through {@link DepositBindings#persistOnChange},
 * so the screen-level save just flushes once more on close.
 */
public final class DepositDetailScreen {
    private DepositDetailScreen() {}

    /** Tab index of the "Inline recipe" category (Core is 0). */
    public static final int INLINE_TAB = 1;

    /** Build the detail editor for {@code d}, returning to {@code back} when closed. */
    public static Screen create(Draft d, Screen back) {
        return create(d, back, 0);
    }

    /**
     * Build the detail editor opened on {@code openTab} (0 = Core, {@link #INLINE_TAB} =
     * Inline recipe). Sub-screens that rebuild the editor (e.g. the fluid picker) pass the
     * tab they came from so the user isn't bounced back to Core.
     */
    public static Screen create(Draft d, Screen back, int openTab) {
        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Deposit: " + d.id))
                .category(CoreTab.build(d))
                .category(InlineTab.build(d, back))
                .save(DepositBindings::persist)
                .screenInit(screen -> {
                    if (openTab > 0) screen.tabNavigationBar.selectTab(openTab, false);
                })
                .build()
                .generateScreen(back);
    }
}
