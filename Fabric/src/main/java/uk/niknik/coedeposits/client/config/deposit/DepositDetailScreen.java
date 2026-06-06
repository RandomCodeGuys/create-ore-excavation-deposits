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

    /** Build the detail editor for {@code d}, returning to {@code back} when closed. */
    public static Screen create(Draft d, Screen back) {
        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Deposit: " + d.id))
                .category(CoreTab.build(d))
                .category(InlineTab.build(d, back))
                .save(DepositBindings::persist)
                .build()
                .generateScreen(back);
    }
}
