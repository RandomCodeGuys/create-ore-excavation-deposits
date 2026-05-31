package uk.niknik.coedeposits.client;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.ListOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionEventListener;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.ControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumDropdownControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.ItemControllerBuilder;
import dev.isxander.yacl3.api.controller.LongFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.client.DepositAuthoring.Draft;
import uk.niknik.coedeposits.client.DepositAuthoring.OutputEntry;
import uk.niknik.coedeposits.client.DepositAuthoring.RecipeEntry;
import uk.niknik.coedeposits.deposit.DepositType;

/**
 * The deposit authoring UI: a master list of {@link Draft}s, a per-deposit
 * detail editor, and per-list sub-editors — all rendered with YACL, writing to
 * {@code config/coedeposits/deposits.json} via {@link DepositAuthoring}.
 *
 * <p><b>Auto-save.</b> Every field writes through to the draft and persists the
 * file on each change via {@code Option.addListener} (see {@link #apply}), so
 * edits stick no matter how you navigate — there is no separate "save" step to
 * forget, and rebuilding a screen after add/remove never loses anything.
 *
 * <p>Field types are chosen for ergonomics: enums → dropdown, items → item
 * picker, counts/chances → sliders, ids → dropdown autocomplete from the client
 * registries. Object lists (vein recipes, drilling outputs) and id lists (biome
 * tags, dimensions) open a dedicated sub-screen with one option group per entry.
 */
public final class DepositEditorScreens {
    private DepositEditorScreens() {}

    /** Working set for the current editor session; (re)loaded by {@link #open}. */
    private static List<Draft> drafts = new ArrayList<>();

    // Autocomplete candidate pools, refreshed on each open() from client state.
    private static List<String> recipeIds = List.of();
    private static List<String> itemTagIds = List.of();
    private static List<String> biomeTagIds = List.of();
    private static List<String> dimensionIds = List.of();

    /** Per-list-entry renderer: appends the entry's option(s) to a group. */
    @FunctionalInterface
    private interface EntryRenderer<E> {
        void render(OptionGroup.Builder group, List<E> list, int index);
    }

    /** UI choice for reveal: DEFAULT means "use the global setting" (no per-type override). */
    private enum RevealChoice implements StringRepresentable {
        DEFAULT, ALWAYS, ON_DISCOVERY, ON_PROXIMITY, ON_PROSPECT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static RevealChoice fromMode(Config.RevealMode mode) {
            return RevealChoice.valueOf(mode.name());
        }

        Config.RevealMode toMode() {
            return Config.RevealMode.valueOf(name());
        }
    }

    /** Entry point from the global config screen's "Open deposit editor" button. */
    public static void open(Screen parent) {
        drafts = DepositAuthoring.loadMerged();
        gatherCandidates();
        Minecraft.getInstance().setScreen(master(parent));
    }

    private static void persist() {
        DepositAuthoring.save(drafts);
    }

    /**
     * Write-through listener: on a value change, push the pending value into the
     * draft via {@code setter} and persist the file. Filtered to STATE_CHANGE so
     * it doesn't fire while the screen is being built.
     */
    private static <T> OptionEventListener<T> apply(Consumer<T> setter) {
        return (opt, event) -> {
            if (event == OptionEventListener.Event.STATE_CHANGE) {
                setter.accept(opt.pendingValue());
                persist();
            }
        };
    }

    private static void gatherCandidates() {
        itemTagIds = safe(() -> BuiltInRegistries.ITEM.getTagNames()
                .map(t -> t.location().toString()).sorted().toList());

        ClientPacketListener c = Minecraft.getInstance().getConnection();
        if (c != null) {
            recipeIds = safe(() -> c.getRecipeManager().getRecipes().stream()
                    .map(h -> h.getId().toString()).sorted().toList());
            biomeTagIds = safe(() -> c.registryAccess().registryOrThrow(Registries.BIOME).getTagNames()
                    .map(t -> t.location().toString()).sorted().toList());
            dimensionIds = safe(() -> c.levels().stream()
                    .map(k -> k.location().toString()).sorted().toList());
        } else {
            recipeIds = List.of();
            biomeTagIds = List.of();
            dimensionIds = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
        }
    }

    private static List<String> safe(Supplier<List<String>> src) {
        try {
            return src.get();
        } catch (Exception e) {
            Coedeposits.LOGGER.warn("[coedeposits] editor: failed to gather suggestions: {}", e.toString());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Master — list of deposits.
    // ═══════════════════════════════════════════════════════════════════════

    private static Screen master(Screen parent) {
        ConfigCategory.Builder cat = ConfigCategory.createBuilder()
                .name(Component.literal("Deposits"))
                .tooltip(Component.literal("Edit deposit types written to config/coedeposits/deposits.json. "
                        + "Every change is saved automatically."));

        cat.option(ButtonOption.createBuilder()
                .name(Component.literal("➕ Add deposit"))
                .text(Component.literal("New"))
                .action((screen, opt) -> {
                    drafts.add(newDraft());
                    persist();
                    Minecraft.getInstance().setScreen(master(parent));
                })
                .build());

        for (int i = 0; i < drafts.size(); i++) {
            Draft d = drafts.get(i);
            final int idx = i;
            // Label suffix communicates provenance/state at a glance:
            //   (default) — comes from a datapack/bundled type, not the overlay
            //   (disabled) — turned off via {"enabled": false}
            String suffix = d.disabled ? "  §c(disabled)§r"
                    : d.fromDefault ? "  §8(default)§r" : "";

            OptionGroup.Builder g = OptionGroup.createBuilder()
                    .name(Component.literal(d.id + suffix))
                    .option(ButtonOption.createBuilder()
                            .name(Component.literal("Edit"))
                            .text(Component.literal(d.disabled ? "Edit (disabled)" : "Edit →"))
                            .action((screen, opt) -> Minecraft.getInstance().setScreen(detail(d, parent)))
                            .build())
                    // Disable/Enable toggle — for defaults this is the only way to
                    // turn a bundled ore off without deleting its datapack file;
                    // for custom types it's a soft off-switch that keeps the config.
                    .option(ButtonOption.createBuilder()
                            .name(Component.literal(d.disabled ? "Enable" : "Disable"))
                            .text(Component.literal(d.disabled ? "✔ Enable" : "⊘ Disable"))
                            .action((screen, opt) -> {
                                d.disabled = !d.disabled;
                                persist();
                                Minecraft.getInstance().setScreen(master(parent));
                            })
                            .build());

            // Remove deletes the overlay entry outright. For a default that just
            // drops the editor copy (the datapack default reappears on reopen),
            // so only offer it for overlay-backed drafts to avoid confusion.
            if (!d.fromDefault) {
                g.option(ButtonOption.createBuilder()
                        .name(Component.literal("Remove"))
                        .text(Component.literal("✖ Remove"))
                        .action((screen, opt) -> {
                            if (idx < drafts.size()) drafts.remove(idx);
                            persist();
                            Minecraft.getInstance().setScreen(master(parent));
                        })
                        .build());
            }
            cat.group(g.build());
        }

        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Coedeposits — Deposits"))
                .category(cat.build())
                .save(DepositEditorScreens::persist)
                .build()
                .generateScreen(parent);
    }

    /** A fresh deposit pre-filled with values that actually generate. */
    private static Draft newDraft() {
        Draft d = new Draft();
        d.id = "coedeposits:new_" + (drafts.size() + 1);
        d.weight = 100;
        d.sizeMin = 4;
        d.sizeMax = 12;
        d.unitsMin = 20_000;
        d.unitsHasMax = true;
        d.unitsMax = 120_000;
        return d;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Detail — scalar fields inline; object/id lists open sub-screens.
    // ═══════════════════════════════════════════════════════════════════════

    private static Screen detail(Draft d, Screen masterParent) {
        Screen back = master(masterParent);

        ConfigCategory core = ConfigCategory.createBuilder()
                .name(Component.literal("Core"))
                .option(str("Id",
                        "Unique name for this deposit as namespace:path — also its key in deposits.json. Use your "
                                + "own namespace (not 'minecraft') for custom ores, e.g. mypack:ruby.",
                        () -> d.id, v -> d.id = v))
                .option(enumOpt("Placement",
                        "Who decides where the ore goes.\n"
                                + "MANAGED (default): coedeposits builds a multi-chunk blob using the fields below.\n"
                                + "COE: hand placement to Create Ore Excavation's grid scatter (needs an inline vein "
                                + "'placement' on the Inline tab; the blob fields are ignored).",
                        () -> d.placement, v -> d.placement = v))
                .option(intOpt("Weight",
                        "Spawn frequency relative to other deposit types — 200 spawns ~10x as often as 20. "
                                + "0 = never spawns naturally (only via /coedeposits place).",
                        () -> d.weight, v -> d.weight = v, 0, 1_000_000))
                .group(OptionGroup.createBuilder().name(Component.literal("Distance (blocks from spawn)"))
                        .option(intOpt("Min", "Nearest distance from world spawn this ore may appear. 0 = from spawn.",
                                () -> d.distanceMin, v -> d.distanceMin = v, 0, Integer.MAX_VALUE))
                        .option(intOpt("Max", "Farthest it may appear. 2147483647 (default) = no outer limit.",
                                () -> d.distanceMax, v -> d.distanceMax = v, 0, Integer.MAX_VALUE))
                        .build())
                .group(OptionGroup.createBuilder().name(Component.literal("Size (chunks)"))
                        .option(intSlider("Min", "Smallest deposit footprint in chunks.",
                                () -> d.sizeMin, v -> d.sizeMin = v, 1, 64, 1))
                        .option(intSlider("Max", "Largest footprint; each placement rolls a size between Min and Max.",
                                () -> d.sizeMax, v -> d.sizeMax = v, 1, 64, 1))
                        .build())
                .group(OptionGroup.createBuilder().name(Component.literal("Per-chunk units"))
                        .option(longOpt("Min",
                                "How much ore one chunk holds before it runs dry, near spawn — the drill 'budget'. "
                                        + "0 = empty (won't yield ore).",
                                () -> d.unitsMin, v -> d.unitsMin = v, 0, Long.MAX_VALUE))
                        .option(boolOpt("Has max",
                                "ON: budget grows from Min (near spawn) to Max (far away). OFF: keeps growing with "
                                        + "distance via the global 'unbounded_growth'.",
                                () -> d.unitsHasMax, v -> d.unitsHasMax = v))
                        .option(longOpt("Max", "Per-chunk budget far from spawn (setting it turns 'Has max' on).",
                                () -> d.unitsMax, v -> { d.unitsMax = v; d.unitsHasMax = true; }, 0, Long.MAX_VALUE))
                        .build())
                .option(dblOpt("Replenish /hour",
                        "Slow regen: units restored per real hour to drilled chunks while loaded, capped at the "
                                + "chunk's original budget. 0 (default) = no regen.",
                        () -> d.replenishRatePerHour, v -> d.replenishRatePerHour = v, 0.0, 10_000_000.0))
                .option(enumOpt("Reveal mode",
                        "When this deposit appears on the world map.\n"
                                + "DEFAULT: follow the global reveal_mode setting.\n"
                                + "ALWAYS: immediately · ON_DISCOVERY: after you walk into it · "
                                + "ON_PROXIMITY: only while nearby · ON_PROSPECT: after a vein finder hits it.",
                        () -> d.overrideReveal ? RevealChoice.fromMode(d.reveal) : RevealChoice.DEFAULT,
                        v -> {
                            if (v == RevealChoice.DEFAULT) {
                                d.overrideReveal = false;
                            } else {
                                d.overrideReveal = true;
                                d.reveal = v.toMode();
                            }
                        }))
                .option(colorOpt("Map color", "Marker color for this deposit on the world map. Alpha ignored.",
                        () -> d.mapColor, v -> d.mapColor = v))
                .build();

        ConfigCategory recipes = ConfigCategory.createBuilder()
                .name(Component.literal("Recipes & filters"))
                .option(listButton("Vein recipes",
                        "The COE vein recipe(s) that define this ore (recipe ids, not items). Open to add recipes "
                                + "with a weight each for a mixed deposit. Leave empty only if you build the ore on "
                                + "the Inline tab.",
                        (s) -> Minecraft.getInstance().setScreen(listScreen(s, "Vein recipes",
                                d.veinRecipes, RecipeEntry::new, recipeRenderer()))))
                .option(str("Infinite vein recipe",
                        "Optional, advanced. A vein recipe used by '/coedeposits place' with a negative amount to "
                                + "make a never-depleting vein. Blank = none.",
                        () -> d.veinRecipeInfinite, v -> d.veinRecipeInfinite = v, recipeIds))
                .group(intListOption("Fillers",
                        "Optional barren 'tailings' chunks — one weight per entry, competing against the recipe "
                                + "weights in the per-chunk roll. Empty = an all-ore deposit.",
                        d.fillers))
                .option(listButton("Biome filter",
                        "Restrict spawning to biomes carrying these tags (e.g. c:is_mountain). Empty = any biome.",
                        (s) -> Minecraft.getInstance().setScreen(listScreen(s, "Biome filter",
                                d.biomeFilter, () -> "c:is_plains", idRenderer(biomeTagIds, "Biome tag",
                                        "A biome tag id, e.g. c:is_mountain (leading # tolerated).")))))
                .option(listButton("Dimensions",
                        "Restrict spawning to these dimensions. Empty = any dimension where coedeposits is enabled.",
                        (s) -> Minecraft.getInstance().setScreen(listScreen(s, "Dimensions",
                                d.dimensions, () -> "minecraft:overworld", idRenderer(dimensionIds, "Dimension",
                                        "A dimension id, e.g. minecraft:overworld.")))))
                .build();

        ConfigCategory inline = ConfigCategory.createBuilder()
                .name(Component.literal("Inline recipe (advanced)"))
                .tooltip(Component.literal(
                        "ADVANCED — optional. Most deposits just pick a ready 'Vein recipe' on the previous tab and "
                                + "leave this OFF. Use it only to BUILD a brand-new COE vein/drilling recipe from "
                                + "scratch. Inline recipes only work from the config file, not from datapack deposit "
                                + "types."))
                .group(OptionGroup.createBuilder().name(Component.literal("Vein (advanced — usually leave OFF)"))
                        .option(boolOpt("Enable inline vein",
                                "Build a COE vein recipe from the fields below instead of referencing one. Turn ON "
                                        + "only if you did NOT set a Vein recipe.",
                                () -> d.hasVein, v -> d.hasVein = v))
                        .option(str("Display name", "Label COE's vein finder shows for this ore. Blank = derived.",
                                () -> d.vein.displayName, v -> d.vein.displayName = v))
                        .option(floatSlider("Amount mult min",
                                "Per-chunk vein size = this x COE's finiteAmountBase (createoreexcavation config, "
                                        + "default 1000). So 2 = ~2000 resources at the low end. In MANAGED mode "
                                        + "coedeposits sets this from 'Per-chunk units' (Core tab) automatically — only "
                                        + "touch it for COE-placed veins.",
                                () -> d.vein.ampMin, v -> d.vein.ampMin = v, 0f, 50f, 0.5f))
                        .option(floatSlider("Amount mult max",
                                "High end of the per-chunk vein size: this x finiteAmountBase (default 1000). Bundled "
                                        + "iron uses 10..30 -> 10000..30000 resources per chunk.",
                                () -> d.vein.ampMax, v -> d.vein.ampMax = v, 0f, 50f, 0.5f))
                        .option(itemOpt("Icon",
                                "Item shown as this vein's icon in the finder / JEI. Empty (air) = first drill output.",
                                () -> d.vein.icon, v -> d.vein.icon = v))
                        .option(intSlider("Icon count", "Stack size drawn on the icon. Cosmetic.",
                                () -> d.vein.iconCount, v -> d.vein.iconCount = v, 1, 64, 1))
                        .option(boolStrOpt("Finite (depletes)",
                                "ON = the vein depletes as you drill it (normal). OFF = an inexhaustible vein.",
                                () -> d.vein.finite, v -> d.vein.finite = v, "always", "never"))
                        .option(boolOpt("Has placement (COE mode)",
                                "Only for Placement = COE. Adds COE's grid-scatter settings below. Leave OFF for "
                                        + "MANAGED deposits.",
                                () -> d.vein.hasPlacement, v -> d.vein.hasPlacement = v))
                        .option(intOpt("Salt",
                                "Random seed offset so different ores don't share chunks. (Only with 'Has placement'.)",
                                () -> d.vein.salt, v -> d.vein.salt = v, Integer.MIN_VALUE, Integer.MAX_VALUE))
                        .option(intSlider("Separation",
                                "Min chunks between two veins of this ore. (Only with 'Has placement'.)",
                                () -> d.vein.separation, v -> d.vein.separation = v, 1, 256, 1))
                        .option(intSlider("Spacing",
                                "Grid cell size in chunks, one vein per cell; >= Separation. (Only with 'Has placement'.)",
                                () -> d.vein.spacing, v -> d.vein.spacing = v, 1, 512, 1))
                        .build())
                .group(OptionGroup.createBuilder().name(Component.literal("Drilling (used when you add outputs below)"))
                        .option(intSlider("Ticks",
                                "Drill cycle length in ticks (20 = 1 s). Lower = faster mining. Bundled = 100.",
                                () -> d.drilling.ticks, v -> d.drilling.ticks = v, 1, 1200, 5))
                        .option(intOpt("Stress", "Create rotational stress the drill consumes. Bundled = ~256.",
                                () -> d.drilling.stress, v -> d.drilling.stress = v, 1, 1_000_000))
                        .option(str("Drill tag",
                                "Item tag of drills allowed to mine this. Blank = createoreexcavation:drills.",
                                () -> d.drilling.drillTag, v -> d.drilling.drillTag = v, itemTagIds))
                        .build())
                .option(listButton("Drilling outputs",
                        "What the drill yields per cycle — each with an item, count and 0-1 chance, rolled "
                                + "independently. Adding any output enables inline drilling. Open to add a guaranteed "
                                + "ore plus chance-based slag.",
                        (s) -> Minecraft.getInstance().setScreen(listScreen(s, "Drilling outputs",
                                d.drilling.outputs, OutputEntry::new, outputRenderer()))))
                .group(OptionGroup.createBuilder().name(Component.literal("Fluid (extractor — advanced)"))
                        .option(boolOpt("Enable fluid extraction",
                                "Make this deposit yield a FLUID, harvested with COE's Extractor instead of the "
                                        + "Drill. Synthesises a COE extracting recipe bound to this type's vein — set a "
                                        + "Vein above too (placement). Leave OFF for solid ores; a deposit can also "
                                        + "have BOTH (drill it for items or pump it for fluid).",
                                () -> d.hasExtracting, v -> d.hasExtracting = v))
                        .option(ButtonOption.createBuilder()
                                .name(Component.literal("Fluid"))
                                .description(OptionDescription.of(Component.literal(
                                        "Source fluid produced. Opens a picker that shows fluids by their own "
                                                + "texture — click one. Vanilla: water, lava, milk.")))
                                .text(Component.literal(fluidLabel(d.extracting.fluid)))
                                .action((s, o) -> Minecraft.getInstance().setScreen(new FluidPickerScreen(
                                        s, d.extracting.fluid, picked -> {
                                            d.extracting.fluid = picked;
                                            d.hasExtracting = true;
                                            persist();
                                            Minecraft.getInstance().setScreen(detail(d, masterParent));
                                        })))
                                .build())
                        .option(intOpt("Amount (mB / cycle)",
                                "Millibuckets yielded per extraction cycle. COE's water extractor uses 500.",
                                () -> d.extracting.amount, v -> d.extracting.amount = v, 1, 1_000_000))
                        .option(intSlider("Ticks",
                                "Extraction cycle length in ticks (20 = 1 s). Lower = faster pumping.",
                                () -> d.extracting.ticks, v -> d.extracting.ticks = v, 1, 1200, 5))
                        .option(intOpt("Stress",
                                "Create rotational stress the extractor consumes. COE bundled ≈ 256.",
                                () -> d.extracting.stress, v -> d.extracting.stress = v, 1, 1_000_000))
                        .option(str("Drill tag",
                                "Item tag of drill heads the extractor accepts. Blank = createoreexcavation:drills.",
                                () -> d.extracting.drillTag, v -> d.extracting.drillTag = v, itemTagIds))
                        .build())
                .build();

        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Deposit: " + d.id))
                .category(core)
                .category(recipes)
                .category(inline)
                .save(DepositEditorScreens::persist)
                .build()
                .generateScreen(back);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Generic list sub-screen — entry groups + a remove button each, plus add.
    // ═══════════════════════════════════════════════════════════════════════

    private static <E> Screen listScreen(Screen parent, String title, List<E> entries,
                                         Supplier<E> factory, EntryRenderer<E> renderer) {
        ConfigCategory.Builder cat = ConfigCategory.createBuilder().name(Component.literal(title));

        cat.option(ButtonOption.createBuilder()
                .name(Component.literal("➕ Add entry"))
                .text(Component.literal("Add"))
                .action((s, o) -> {
                    entries.add(factory.get());
                    persist();
                    Minecraft.getInstance().setScreen(listScreen(parent, title, entries, factory, renderer));
                })
                .build());

        for (int i = 0; i < entries.size(); i++) {
            final int idx = i;
            OptionGroup.Builder g = OptionGroup.createBuilder().name(Component.literal("#" + (i + 1)));
            renderer.render(g, entries, idx);
            g.option(ButtonOption.createBuilder()
                    .name(Component.literal("Remove"))
                    .text(Component.literal("✖ Remove"))
                    .action((s, o) -> {
                        if (idx < entries.size()) entries.remove(idx);
                        persist();
                        Minecraft.getInstance().setScreen(listScreen(parent, title, entries, factory, renderer));
                    })
                    .build());
            cat.group(g.build());
        }

        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal(title))
                .category(cat.build())
                .save(DepositEditorScreens::persist)
                .build()
                .generateScreen(parent);
    }

    private static EntryRenderer<RecipeEntry> recipeRenderer() {
        return (g, list, i) -> {
            RecipeEntry e = list.get(i);
            g.option(str("Recipe", "COE vein recipe id (not an item), e.g. coedeposits:iron_vein.",
                    () -> e.recipe, v -> e.recipe = v, recipeIds));
            g.option(intSlider("Weight", "Relative weight of this recipe in the per-chunk pool.",
                    () -> e.weight, v -> e.weight = v, 1, 64, 1));
        };
    }

    private static EntryRenderer<OutputEntry> outputRenderer() {
        return (g, list, i) -> {
            OutputEntry e = list.get(i);
            g.option(itemOpt("Item", "Item the drill yields for this output.",
                    () -> e.item, v -> e.item = v));
            g.option(intSlider("Count",
                    "Items produced per drill CYCLE (one excavation, lasting 'Ticks' game-ticks) when this drops.",
                    () -> e.count, v -> e.count = v, 1, 64, 1));
            g.option(floatSlider("Chance",
                    "Chance this output drops each drill cycle. 1.0 = every cycle, 0.3 = ~30% of cycles.",
                    () -> e.chance, v -> e.chance = v, 0f, 1f, 0.01f));
        };
    }

    private static EntryRenderer<String> idRenderer(List<String> candidates, String label, String desc) {
        return (g, list, i) -> g.option(
                str(label, desc, () -> list.get(i), v -> list.set(i, v), candidates));
    }

    /**
     * Reusable click-to-edit string-list sub-screen: a dropdown (or plain field
     * when {@code candidates} is empty) per entry plus add/remove, persisting via
     * {@code onChange} on every edit. Entries are normal options (not inline
     * ListOption rows), so the dropdown is clickable. Used by the deposit editor
     * and by the global config's enabled-dimensions list.
     */
    public static Screen stringListScreen(Screen parent, String title, List<String> entries, List<String> candidates,
                                          String entryLabel, String entryDesc, String initial, Runnable onChange) {
        ConfigCategory.Builder cat = ConfigCategory.createBuilder().name(Component.literal(title));

        cat.option(ButtonOption.createBuilder()
                .name(Component.literal("➕ Add entry"))
                .text(Component.literal("Add"))
                .action((s, o) -> {
                    entries.add(initial);
                    onChange.run();
                    Minecraft.getInstance().setScreen(stringListScreen(parent, title, entries, candidates,
                            entryLabel, entryDesc, initial, onChange));
                })
                .build());

        for (int i = 0; i < entries.size(); i++) {
            final int idx = i;
            OptionGroup.Builder g = OptionGroup.createBuilder().name(Component.literal("#" + (i + 1)));
            g.option(Option.<String>createBuilder()
                    .name(Component.literal(entryLabel))
                    .description(OptionDescription.of(Component.literal(entryDesc)))
                    .binding(entries.get(idx), () -> entries.get(idx), v -> entries.set(idx, v))
                    .addListener((opt, e) -> {
                        if (e == OptionEventListener.Event.STATE_CHANGE) {
                            entries.set(idx, opt.pendingValue());
                            onChange.run();
                        }
                    })
                    .controller(opt -> stringController(opt, candidates))
                    .build());
            g.option(ButtonOption.createBuilder()
                    .name(Component.literal("Remove"))
                    .text(Component.literal("✖ Remove"))
                    .action((s, o) -> {
                        if (idx < entries.size()) entries.remove(idx);
                        onChange.run();
                        Minecraft.getInstance().setScreen(stringListScreen(parent, title, entries, candidates,
                                entryLabel, entryDesc, initial, onChange));
                    })
                    .build());
            cat.group(g.build());
        }

        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal(title))
                .category(cat.build())
                .save(onChange::run)
                .build()
                .generateScreen(parent);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Option helpers — every value control writes through + auto-saves.
    // ═══════════════════════════════════════════════════════════════════════

    /** A button that opens a list sub-editor. */
    private static Option<?> listButton(String label, String desc, Consumer<Screen> opener) {
        return ButtonOption.createBuilder()
                .name(Component.literal(label))
                .description(OptionDescription.of(Component.literal(desc)))
                .text(Component.literal("Edit →"))
                .action((s, o) -> opener.accept(s))
                .build();
    }

    private static Option<String> str(String name, String desc, Supplier<String> g, Consumer<String> s) {
        return str(name, desc, g, s, List.of());
    }

    private static Option<String> str(String name, String desc, Supplier<String> g, Consumer<String> s,
                                      List<String> candidates) {
        return Option.<String>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(g.get(), g, s)
                .addListener(apply(s))
                .controller(opt -> stringController(opt, candidates))
                .build();
    }

    private static ControllerBuilder<String> stringController(Option<String> opt, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return StringControllerBuilder.create(opt);
        }
        return DropdownStringControllerBuilder.create(opt).values(candidates).allowAnyValue(true);
    }

    private static Option<Integer> intOpt(String name, String desc, Supplier<Integer> g, Consumer<Integer> s, int min, int max) {
        return Option.<Integer>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(g.get(), g, s)
                .addListener(apply(s))
                .controller(opt -> IntegerFieldControllerBuilder.create(opt).min(min).max(max))
                .build();
    }

    private static Option<Integer> intSlider(String name, String desc, Supplier<Integer> g, Consumer<Integer> s,
                                             int min, int max, int step) {
        return Option.<Integer>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(g.get(), g, s)
                .addListener(apply(s))
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(min, max).step(step))
                .build();
    }

    private static Option<Long> longOpt(String name, String desc, Supplier<Long> g, Consumer<Long> s, long min, long max) {
        return Option.<Long>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(g.get(), g, s)
                .addListener(apply(s))
                .controller(opt -> LongFieldControllerBuilder.create(opt).min(min).max(max))
                .build();
    }

    private static Option<Float> floatSlider(String name, String desc, Supplier<Float> g, Consumer<Float> s,
                                             float min, float max, float step) {
        return Option.<Float>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(g.get(), g, s)
                .addListener(apply(s))
                .controller(opt -> FloatSliderControllerBuilder.create(opt).range(min, max).step(step))
                .build();
    }

    private static Option<Double> dblOpt(String name, String desc, Supplier<Double> g, Consumer<Double> s, double min, double max) {
        return Option.<Double>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(g.get(), g, s)
                .addListener(apply(s))
                .controller(opt -> DoubleFieldControllerBuilder.create(opt).min(min).max(max))
                .build();
    }

    private static Option<Boolean> boolOpt(String name, String desc, Supplier<Boolean> g, Consumer<Boolean> s) {
        return Option.<Boolean>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(g.get(), g, s)
                .addListener(apply(s))
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .build();
    }

    /** A string field exposed as a boolean toggle mapping to onValue/offValue. */
    private static Option<Boolean> boolStrOpt(String name, String desc, Supplier<String> g, Consumer<String> s,
                                              String onValue, String offValue) {
        return Option.<Boolean>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(onValue.equals(g.get()), () -> onValue.equals(g.get()), b -> s.accept(b ? onValue : offValue))
                .addListener(apply(b -> s.accept(b ? onValue : offValue)))
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .build();
    }

    private static Option<Color> colorOpt(String name, String desc, Supplier<Integer> g, Consumer<Integer> s) {
        return Option.<Color>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(new Color(g.get() & 0xFFFFFF),
                        () -> new Color(g.get() & 0xFFFFFF),
                        c -> s.accept(c.getRGB() & 0xFFFFFF))
                .addListener(apply(c -> s.accept(c.getRGB() & 0xFFFFFF)))
                .controller(ColorControllerBuilder::create)
                .build();
    }

    private static Option<Item> itemOpt(String name, String desc, Supplier<String> g, Consumer<String> s) {
        return Option.<Item>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(itemFromId(g.get()), () -> itemFromId(g.get()), item -> s.accept(itemToId(item)))
                .addListener(apply(item -> s.accept(itemToId(item))))
                .controller(ItemControllerBuilder::create)
                .build();
    }

    private static <E extends Enum<E> & StringRepresentable> Option<E> enumOpt(
            String name, String desc, Supplier<E> g, Consumer<E> s) {
        return Option.<E>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(g.get(), g, s)
                .addListener(apply(s))
                .controller(opt -> EnumDropdownControllerBuilder.create(opt)
                        .formatValue(e -> Component.literal(e.getSerializedName())))
                .build();
    }

    /** Inline integer list (numbers, so a plain ListOption works inline). */
    private static ListOption<Integer> intListOption(String name, String desc, List<Integer> backing) {
        return ListOption.<Integer>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(new ArrayList<>(backing),
                        () -> new ArrayList<>(backing),
                        nl -> {
                            backing.clear();
                            backing.addAll(nl);
                        })
                .addListener((opt, event) -> {
                    if (event == OptionEventListener.Event.STATE_CHANGE) {
                        backing.clear();
                        backing.addAll(opt.pendingValue());
                        persist();
                    }
                })
                .controller(opt -> IntegerFieldControllerBuilder.create(opt).min(1).max(100_000))
                .initial(1)
                .build();
    }

    private static Item itemFromId(String id) {
        if (id == null || id.isBlank()) return Items.AIR;
        ResourceLocation rl = ResourceLocation.tryParse(id.trim());
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }

    private static String itemToId(Item item) {
        return item == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** Friendly label for the Fluid button: the fluid's display name + id, or a prompt when unset. */
    private static String fluidLabel(String id) {
        if (id == null || id.isBlank()) return "Pick →";
        ResourceLocation rl = ResourceLocation.tryParse(id.trim());
        if (rl == null || !BuiltInRegistries.FLUID.containsKey(rl)) return id;
        return BuiltInRegistries.FLUID.get(rl).getFluidType().getDescription().getString() + "  §8(" + id + ")";
    }
}
