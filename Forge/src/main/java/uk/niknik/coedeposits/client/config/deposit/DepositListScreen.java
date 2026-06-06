package uk.niknik.coedeposits.client.config.deposit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;

import uk.niknik.coedeposits.client.DepositAuthoring.Draft;
import uk.niknik.coedeposits.client.DepositRowController;

/**
 * The "Deposits" tab embedded in the host config screen: an add button plus one
 * {@link DepositRowController} row per deposit ({@code id [Edit →][⊘ Disable][✖ Delete]}).
 * Mutations persist immediately, then rebuild the host screen back onto the Deposits tab
 * (YACL options can't change in place, so the screen is rebuilt via {@code rebuild}).
 */
public final class DepositListScreen {
    private DepositListScreen() {}

    /**
     * @param parent  ultimate back-target of the whole config screen (unused here, kept for
     *                symmetry with the host screen's other categories)
     * @param rebuild reopens the host config screen on the Deposits tab; run after every
     *                list mutation
     */
    public static ConfigCategory depositsCategory(Screen parent, Supplier<Screen> rebuild) {
        Minecraft mc = Minecraft.getInstance();
        ConfigCategory.Builder cat = ConfigCategory.createBuilder()
                .name(Component.literal("Deposits"))
                .tooltip(Component.literal("Edit deposit types written to config/coedeposits/deposits.json. "
                        + "Every change is saved automatically."));

        cat.option(ButtonOption.createBuilder()
                .name(Component.literal("➕ Add deposit"))
                .text(Component.literal("New"))
                .action((screen, opt) -> {
                    DepositBindings.drafts.add(0, DepositBindings.newDraft());   // prepend → first in the list and the file
                    DepositBindings.persist();
                    mc.setScreen(rebuild.get());
                })
                .build());

        for (Draft d : DepositBindings.drafts) {
            // Label suffix communicates provenance/state at a glance:
            //   (default) — a bundled/datapack type, not the overlay
            //   (disabled) — turned off via {"enabled": false}
            String suffix = d.disabled ? "  §c(disabled)§r"
                    : d.fromDefault ? "  §8(default)§r" : "";

            List<DepositRowController.Action> actions = new ArrayList<>();
            actions.add(new DepositRowController.Action(
                    Component.literal("Edit →"),
                    () -> mc.setScreen(DepositDetailScreen.create(d, rebuild.get()))));
            // Disable/Enable — for a default this is the only off-switch (deleting it just
            // drops the editor copy and the datapack default reappears on reload).
            actions.add(new DepositRowController.Action(
                    Component.literal(d.disabled ? "✔ Enable" : "⊘ Disable"),
                    () -> {
                        d.disabled = !d.disabled;
                        DepositBindings.persist();
                        mc.setScreen(rebuild.get());
                    }));
            // Delete removes the overlay entry outright, behind a confirmation. Hidden for
            // pristine defaults (nothing to delete — they return on reload); Disable is theirs.
            if (!d.fromDefault) {
                actions.add(new DepositRowController.Action(
                        Component.literal("✖ Delete"),
                        () -> mc.setScreen(new ConfirmScreen(
                                confirmed -> {
                                    if (confirmed) {
                                        DepositBindings.drafts.remove(d);
                                        DepositBindings.persist();
                                    }
                                    mc.setScreen(rebuild.get());
                                },
                                Component.literal("Delete deposit \"" + d.id + "\"?"),
                                Component.literal("Removes it from config/coedeposits/deposits.json. "
                                        + "This cannot be undone.")))));
            }

            cat.option(DepositRowController.row(Component.literal(d.id + suffix), actions));
        }

        return cat.build();
    }
}
