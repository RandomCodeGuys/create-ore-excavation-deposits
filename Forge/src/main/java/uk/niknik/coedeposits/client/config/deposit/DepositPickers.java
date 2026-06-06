package uk.niknik.coedeposits.client.config.deposit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionEventListener;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.ItemControllerBuilder;

import uk.niknik.coedeposits.client.DepositAuthoring.Draft;
import uk.niknik.coedeposits.client.DepositAuthoring.OutputEntry;
import uk.niknik.coedeposits.client.DepositAuthoring.RecipeEntry;
import uk.niknik.coedeposits.client.DepositRowController;

/**
 * The deposit editor's list sub-screens. Each is a YACL screen whose entries are one-line
 * {@link DepositRowController} rows; a dropdown (or visual item picker) at the top adds, and
 * each row removes. Mutations persist via the {@code onChange} callback, then rebuild.
 *
 * <ul>
 *   <li>{@link #stringListScreen} — pick/type from a candidate set (biome tags, dimensions,
 *       and the global Enabled-dimensions list).</li>
 *   <li>{@link #veinRecipeScreen} — COE vein recipe ids (weight fixed at 1).</li>
 *   <li>{@link #outputListScreen} — drill outputs: a visual item picker, then per output an
 *       editable item picker + Count / Chance(%) sliders.</li>
 * </ul>
 */
public final class DepositPickers {
    private DepositPickers() {}

    /**
     * Reusable "pick from a closed set" sub-screen, styled like the deposits list: a
     * free-typeable dropdown of candidates not already added (picking a suggestion adds it
     * instantly; typing an id + Add covers the main-menu case where no candidates are loaded)
     * plus one {@code id + ✖ Remove} row per entry. Persists via {@code onChange}.
     *
     * @param noun singular noun for the add control + rows, e.g. {@code "Dimension"}
     * @param desc tooltip for the add dropdown
     */
    public static Screen stringListScreen(Screen parent, String title, List<String> entries, List<String> candidates,
                                          String noun, String desc, Runnable onChange) {
        String lower = noun.toLowerCase(Locale.ROOT);
        ConfigCategory.Builder cat = ConfigCategory.createBuilder().name(Component.literal(title));
        Runnable rebuild = () -> Minecraft.getInstance().setScreen(
                stringListScreen(parent, title, entries, candidates, noun, desc, onChange));

        // Add control: a dropdown of candidates not already added; picking one adds it
        // instantly. Greyed when nothing is left to add (e.g. no candidates loaded at the menu).
        List<String> available = new ArrayList<>();
        for (String c : candidates) {
            if (!entries.contains(c)) available.add(c);
        }
        cat.option(Option.<String>createBuilder()
                .name(Component.literal("➕ Add " + lower))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding("", () -> "", v -> {})
                .available(!available.isEmpty())
                .addListener((opt, e) -> {
                    if (e == OptionEventListener.Event.STATE_CHANGE && available.contains(opt.pendingValue())) {
                        entries.add(opt.pendingValue());
                        onChange.run();
                        rebuild.run();
                    }
                })
                .controller(opt -> DropdownStringControllerBuilder.create(opt).values(available).allowAnyValue(true))
                .build());

        for (int i = 0; i < entries.size(); i++) {
            final int idx = i;
            cat.option(DepositRowController.row(
                    Component.literal(entries.get(idx)),
                    List.of(new DepositRowController.Action(
                            Component.literal("✖ Remove"),
                            () -> {
                                if (idx < entries.size()) entries.remove(idx);
                                onChange.run();
                                rebuild.run();
                            }))));
        }

        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal(title))
                .category(cat.build())
                .save(onChange::run)
                .build()
                .generateScreen(parent);
    }

    /**
     * Vein-recipe picker, styled like the dimensions list: a dropdown of COE vein recipe ids
     * not already added (picking one appends it instantly) plus one {@code id + ✖ Remove} row
     * per recipe. New entries get weight 1; existing weights in the draft are preserved.
     */
    public static Screen veinRecipeScreen(Screen parent, Draft d, Runnable onChange) {
        ConfigCategory.Builder cat = ConfigCategory.createBuilder().name(Component.literal("Vein recipes"));
        Runnable rebuild = () -> Minecraft.getInstance().setScreen(veinRecipeScreen(parent, d, onChange));

        List<String> have = new ArrayList<>();
        for (RecipeEntry e : d.veinRecipes) have.add(e.recipe);
        List<String> available = new ArrayList<>();
        for (String c : DepositBindings.recipeIds) {
            if (!have.contains(c)) available.add(c);
        }
        cat.option(Option.<String>createBuilder()
                .name(Component.literal("➕ Add recipe"))
                .description(OptionDescription.of(Component.literal("Pick a COE vein recipe id (createoreexcavation:vein). Added with weight 1.")))
                .binding("", () -> "", v -> {})
                .available(!available.isEmpty())
                .addListener((opt, e) -> {
                    if (e == OptionEventListener.Event.STATE_CHANGE && available.contains(opt.pendingValue())) {
                        d.veinRecipes.add(new RecipeEntry(opt.pendingValue(), 1));
                        onChange.run();
                        rebuild.run();
                    }
                })
                .controller(opt -> DropdownStringControllerBuilder.create(opt).values(available).allowAnyValue(true))
                .build());

        for (int i = 0; i < d.veinRecipes.size(); i++) {
            final int idx = i;
            cat.option(DepositRowController.row(
                    Component.literal(d.veinRecipes.get(idx).recipe),
                    List.of(new DepositRowController.Action(
                            Component.literal("✖ Remove"),
                            () -> {
                                if (idx < d.veinRecipes.size()) d.veinRecipes.remove(idx);
                                onChange.run();
                                rebuild.run();
                            }))));
        }

        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Vein recipes"))
                .category(cat.build())
                .save(onChange::run)
                .build()
                .generateScreen(parent);
    }

    /**
     * Drilling-outputs sub-screen: a visual item picker at the top (pick an item → append an
     * output) and, per output, the same editable item picker plus Count / Chance(%) sliders
     * and a Remove button. Chance is stored 0..1 but edited as a 0–100 percent.
     */
    public static Screen outputListScreen(Screen parent, Draft d, Runnable onChange) {
        List<OutputEntry> outputs = d.drilling.outputs;
        ConfigCategory.Builder cat = ConfigCategory.createBuilder().name(Component.literal("Drilling outputs"));
        Runnable rebuild = () -> Minecraft.getInstance().setScreen(outputListScreen(parent, d, onChange));

        // Add control: a visual item picker (icon + name). Picking an item appends an output
        // (count 1, chance 100%) and rebuilds; the fresh screen resets the picker.
        cat.option(Option.<Item>createBuilder()
                .name(Component.literal("➕ Add output"))
                .description(OptionDescription.of(Component.literal("Pick an item the drill should yield. Added at count 1, chance 100% — tune it on the rows below.")))
                .binding(Items.AIR, () -> Items.AIR, item -> {})
                .addListener((opt, e) -> {
                    if (e == OptionEventListener.Event.STATE_CHANGE && opt.pendingValue() != Items.AIR) {
                        outputs.add(new OutputEntry(DepositBindings.itemToId(opt.pendingValue()), 1, 1.0f));
                        onChange.run();
                        rebuild.run();
                    }
                })
                .controller(ItemControllerBuilder::create)
                .build());

        for (int i = 0; i < outputs.size(); i++) {
            final int idx = i;
            OutputEntry e = outputs.get(i);
            // Row 1: item icon + name (click → item picker) + a small ✖ Remove.
            cat.option(OutputRows.itemRow(
                    e.item,
                    picked -> { e.item = picked; onChange.run(); rebuild.run(); },
                    () -> {
                        if (idx < outputs.size()) outputs.remove(idx);
                        onChange.run();
                        rebuild.run();
                    }));
            // Count + Chance — native YACL number fields (reliable keyboard focus + persistence,
            // unlike a hand-hosted EditBox). Chance is stored 0..1 but edited as a 0–100 percent.
            cat.option(Option.<Integer>createBuilder()
                    .name(Component.literal("Count"))
                    .description(OptionDescription.of(Component.literal("Items produced per drill cycle when this output drops.")))
                    .binding(e.count, () -> e.count, v -> e.count = v)
                    .addListener(DepositBindings.persistOnChange(v -> e.count = v))
                    .controller(o -> IntegerFieldControllerBuilder.create(o).min(1).max(64))
                    .build());
            cat.option(Option.<Integer>createBuilder()
                    .name(Component.literal("Chance %"))
                    .description(OptionDescription.of(Component.literal("Drop chance each drill cycle, 0–100. 100 = every cycle, 30 = ~30%.")))
                    .binding(Math.round(e.chance * 100f), () -> Math.round(e.chance * 100f), v -> e.chance = v / 100f)
                    .addListener(DepositBindings.persistOnChange(v -> e.chance = v / 100f))
                    .controller(o -> IntegerFieldControllerBuilder.create(o).min(0).max(100))
                    .build());
        }

        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Drilling outputs"))
                .category(cat.build())
                .save(onChange::run)
                .build()
                .generateScreen(parent);
    }
}
