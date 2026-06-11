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

import uk.niknik.coedeposits.Config;
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

        // ── Adopted foreign COE veins (no deposit_type of their own) ────────
        // Veins from other mods / datapacks that auto-adopt onto the map. They
        // have no type to edit, so list them here with a one-click Customize that
        // creates a placement=coe type (id = vein id, so already-placed adopted
        // deposits upgrade seamlessly) and opens its editor.
        List<String> adoptable = DepositBindings.adoptableVeinIds();
        if (adoptable.isEmpty() && mc.getConnection() == null) {
            // COE vein recipes come from the client's recipe manager, which only
            // exists in a world. From the title screen the list would be silently
            // empty — say so instead of hiding the section.
            cat.option(DepositRowController.row(
                    Component.literal("§8— enter a world to list COE veins (adopted / disabled) —"),
                    List.of()));
        }
        if (!adoptable.isEmpty()) {
            cat.option(DepositRowController.row(
                    Component.literal("§8— COE veins without a deposit_type: adopted add-on/datapack veins + base-COE (off by default) —"),
                    List.of()));
            for (String veinId : adoptable) {
                // Effective state: explicit disabled_veins entry, OR a base-COE
                // vein under coe_veins_disabled_by_default that hasn't been
                // re-enabled. The suffix distinguishes the two so admins see WHY.
                net.minecraft.resources.ResourceLocation rl =
                        net.minecraft.resources.ResourceLocation.tryParse(veinId);
                boolean explicitOff = Config.DISABLED_VEINS.get().contains(veinId);
                boolean disabled = explicitOff || (rl != null && Config.isVeinDisabled(rl));
                String suffix = !disabled ? "  §6(adopted)§r"
                        : explicitOff ? "  §c(disabled)§r" : "  §c(disabled — base COE default)§r";
                List<DepositRowController.Action> acts = new ArrayList<>();
                // Edit → promote to a placement=coe type you control. Available in
                // BOTH states — a declared type overrides the disable, so promoting
                // a default-off base-COE vein brings it back under your control.
                acts.add(new DepositRowController.Action(
                        Component.literal("Edit →"),
                        () -> {
                            Draft d = DepositBindings.adoptDraft(veinId);
                            DepositBindings.drafts.add(0, d);
                            DepositBindings.persist();
                            mc.setScreen(DepositDetailScreen.create(d, rebuild.get()));
                        }));
                if (disabled) {
                    acts.add(new DepositRowController.Action(
                            Component.literal("✔ Enable"),
                            () -> { setVeinDisabled(veinId, false); mc.setScreen(rebuild.get()); }));
                } else {
                    acts.add(new DepositRowController.Action(
                            Component.literal("⊘ Disable"),
                            () -> { setVeinDisabled(veinId, true); mc.setScreen(rebuild.get()); }));
                }
                cat.option(DepositRowController.row(
                        Component.literal(DepositBindings.shortVeinLabel(veinId) + suffix), acts));
            }
        }

        return cat.build();
    }

    /**
     * Flip a vein's enabled state and flush the config. Namespace-aware: a
     * base-COE vein under the default-off rule toggles via the
     * {@link Config#ENABLED_VEINS} exception list (Enable adds, Disable removes);
     * any other vein toggles via {@link Config#DISABLED_VEINS} as before. Both
     * lists are kept free of the id on the opposite action so they stay disjoint.
     */
    private static void setVeinDisabled(String veinId, boolean disabled) {
        net.minecraft.resources.ResourceLocation rl =
                net.minecraft.resources.ResourceLocation.tryParse(veinId);
        boolean coeDefaultOff = rl != null && Config.isCoeBundledVein(rl)
                && Config.COE_VEINS_DISABLED_BY_DEFAULT.get();

        List<String> off = new ArrayList<>(Config.DISABLED_VEINS.get());
        List<String> on = new ArrayList<>(Config.ENABLED_VEINS.get());
        if (coeDefaultOff) {
            if (disabled) on.remove(veinId);
            else if (!on.contains(veinId)) on.add(veinId);
            off.remove(veinId);  // default rule covers the off-state; keep the explicit list clean
        } else {
            if (disabled) { if (!off.contains(veinId)) off.add(veinId); }
            else off.remove(veinId);
            on.remove(veinId);
        }
        Config.DISABLED_VEINS.set(off);
        Config.ENABLED_VEINS.set(on);
        Config.SPEC.save();
    }
}
