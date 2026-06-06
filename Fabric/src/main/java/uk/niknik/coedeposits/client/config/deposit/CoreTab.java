package uk.niknik.coedeposits.client.config.deposit;

import java.awt.Color;
import java.util.ArrayList;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.ListOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionEventListener;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumDropdownControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.LongFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;

import uk.niknik.coedeposits.client.DepositAuthoring.Draft;
import uk.niknik.coedeposits.client.config.deposit.DepositBindings.RevealChoice;
import uk.niknik.coedeposits.deposit.DepositType;

/** The deposit detail editor's <b>Core</b> tab: identity, placement, gradient, density, reveal, recipes &amp; filters. */
public final class CoreTab {
    private CoreTab() {}

    public static ConfigCategory build(Draft d) {
        ConfigCategory.Builder cat = ConfigCategory.createBuilder().name(Component.literal("Core"));

        Option<String> id = Option.<String>createBuilder()
                .name(Component.literal("Id"))
                .description(OptionDescription.of(Component.literal("Unique name for this deposit as namespace:path — also its key in deposits.json. Use your own namespace (not 'minecraft') for custom ores, e.g. mypack:ruby.")))
                .binding(d.id, () -> d.id, v -> d.id = v)
                .addListener(DepositBindings.persistOnChange(v -> d.id = v))
                .controller(StringControllerBuilder::create)
                .build();
        cat.option(id);

        Option<DepositType.Placement> placement = Option.<DepositType.Placement>createBuilder()
                .name(Component.literal("Placement"))
                .description(OptionDescription.of(Component.literal("Who decides where the ore goes.\nMANAGED (default): coedeposits builds a multi-chunk blob using the fields below.\nCOE: hand placement to Create Ore Excavation's grid scatter (needs an inline vein 'placement' on the Inline tab; the blob fields are ignored).")))
                .binding(d.placement, () -> d.placement, v -> d.placement = v)
                .addListener(DepositBindings.persistOnChange(v -> d.placement = v))
                .controller(o -> EnumDropdownControllerBuilder.create(o).formatValue(e -> Component.literal(e.getSerializedName())))
                .build();
        cat.option(placement);

        Option<Integer> weight = Option.<Integer>createBuilder()
                .name(Component.literal("Weight"))
                .description(OptionDescription.of(Component.literal("Spawn frequency relative to other deposit types — 200 spawns ~10x as often as 20. 0 = never spawns naturally (only via /coedeposits place).")))
                .binding(d.weight, () -> d.weight, v -> d.weight = v)
                .addListener(DepositBindings.persistOnChange(v -> d.weight = v))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(0).max(1_000_000))
                .build();
        cat.option(weight);

        OptionGroup.Builder distance = OptionGroup.createBuilder().name(Component.literal("Distance (blocks from spawn)"));
        Option<Integer> distanceMin = Option.<Integer>createBuilder()
                .name(Component.literal("Min"))
                .description(OptionDescription.of(Component.literal("Nearest distance from world spawn this ore may appear. 0 = from spawn.")))
                .binding(d.distanceMin, () -> d.distanceMin, v -> d.distanceMin = v)
                .addListener(DepositBindings.persistOnChange(v -> d.distanceMin = v))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(0).max(Integer.MAX_VALUE))
                .build();
        distance.option(distanceMin);
        Option<Integer> distanceMax = Option.<Integer>createBuilder()
                .name(Component.literal("Max"))
                .description(OptionDescription.of(Component.literal("Farthest it may appear. 2147483647 (default) = no outer limit.")))
                .binding(d.distanceMax, () -> d.distanceMax, v -> d.distanceMax = v)
                .addListener(DepositBindings.persistOnChange(v -> d.distanceMax = v))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(0).max(Integer.MAX_VALUE))
                .build();
        distance.option(distanceMax);
        cat.group(distance.build());

        OptionGroup.Builder size = OptionGroup.createBuilder().name(Component.literal("Size (chunks)"));
        Option<Integer> sizeMin = Option.<Integer>createBuilder()
                .name(Component.literal("Min"))
                .description(OptionDescription.of(Component.literal("Smallest deposit footprint in chunks.")))
                .binding(d.sizeMin, () -> d.sizeMin, v -> d.sizeMin = v)
                .addListener(DepositBindings.persistOnChange(v -> d.sizeMin = v))
                .controller(o -> IntegerSliderControllerBuilder.create(o).range(1, 64).step(1))
                .build();
        size.option(sizeMin);
        Option<Integer> sizeMax = Option.<Integer>createBuilder()
                .name(Component.literal("Max"))
                .description(OptionDescription.of(Component.literal("Largest footprint; each placement rolls a size between Min and Max.")))
                .binding(d.sizeMax, () -> d.sizeMax, v -> d.sizeMax = v)
                .addListener(DepositBindings.persistOnChange(v -> d.sizeMax = v))
                .controller(o -> IntegerSliderControllerBuilder.create(o).range(1, 64).step(1))
                .build();
        size.option(sizeMax);
        cat.group(size.build());

        OptionGroup.Builder units = OptionGroup.createBuilder().name(Component.literal("Per-chunk units"));
        Option<Long> unitsMin = Option.<Long>createBuilder()
                .name(Component.literal("Min"))
                .description(OptionDescription.of(Component.literal("How much ore one chunk holds before it runs dry, near spawn — the drill 'budget'. 0 = empty (won't yield ore).")))
                .binding(d.unitsMin, () -> d.unitsMin, v -> d.unitsMin = v)
                .addListener(DepositBindings.persistOnChange(v -> d.unitsMin = v))
                .controller(o -> LongFieldControllerBuilder.create(o).min(0L).max(Long.MAX_VALUE))
                .build();
        units.option(unitsMin);
        Option<Boolean> unitsHasMax = Option.<Boolean>createBuilder()
                .name(Component.literal("Has max"))
                .description(OptionDescription.of(Component.literal("ON: budget grows from Min (near spawn) to Max (far away). OFF: keeps growing with distance via the global 'unbounded_growth'.")))
                .binding(d.unitsHasMax, () -> d.unitsHasMax, v -> d.unitsHasMax = v)
                .addListener(DepositBindings.persistOnChange(v -> d.unitsHasMax = v))
                .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                .build();
        units.option(unitsHasMax);
        Consumer<Long> unitsMaxSetter = v -> { d.unitsMax = v; d.unitsHasMax = true; };   // setting Max turns Has max on
        Option<Long> unitsMax = Option.<Long>createBuilder()
                .name(Component.literal("Max"))
                .description(OptionDescription.of(Component.literal("Per-chunk budget far from spawn (setting it turns 'Has max' on).")))
                .binding(d.unitsMax, () -> d.unitsMax, unitsMaxSetter)
                .addListener(DepositBindings.persistOnChange(unitsMaxSetter))
                .controller(o -> LongFieldControllerBuilder.create(o).min(0L).max(Long.MAX_VALUE))
                .build();
        units.option(unitsMax);
        cat.group(units.build());

        Option<Double> replenish = Option.<Double>createBuilder()
                .name(Component.literal("Replenish /hour"))
                .description(OptionDescription.of(Component.literal("Slow regen: units restored per real hour to drilled chunks while loaded, capped at the chunk's original budget. 0 (default) = no regen.")))
                .binding(d.replenishRatePerHour, () -> d.replenishRatePerHour, v -> d.replenishRatePerHour = v)
                .addListener(DepositBindings.persistOnChange(v -> d.replenishRatePerHour = v))
                .controller(o -> DoubleFieldControllerBuilder.create(o).min(0.0).max(10_000_000.0))
                .build();
        cat.option(replenish);

        // Reveal: the UI choice DEFAULT clears the per-type override; any other value sets it.
        Consumer<RevealChoice> revealSetter = v -> {
            if (v == RevealChoice.DEFAULT) {
                d.overrideReveal = false;
            } else {
                d.overrideReveal = true;
                d.reveal = v.toMode();
            }
        };
        Option<RevealChoice> reveal = Option.<RevealChoice>createBuilder()
                .name(Component.literal("Reveal mode"))
                .description(OptionDescription.of(Component.literal("When this deposit appears on the world map.\nDEFAULT: follow the global reveal_mode setting.\nALWAYS: immediately · ON_DISCOVERY: after you walk into it · ON_PROXIMITY: only while nearby · ON_PROSPECT: after a vein finder hits it.")))
                .binding(d.overrideReveal ? RevealChoice.fromMode(d.reveal) : RevealChoice.DEFAULT,
                        () -> d.overrideReveal ? RevealChoice.fromMode(d.reveal) : RevealChoice.DEFAULT,
                        revealSetter)
                .addListener(DepositBindings.persistOnChange(revealSetter))
                .controller(o -> EnumDropdownControllerBuilder.create(o).formatValue(e -> Component.literal(e.getSerializedName())))
                .build();
        cat.option(reveal);

        Consumer<Color> colorSetter = c -> d.mapColor = c.getRGB() & 0xFFFFFF;
        Option<Color> mapColor = Option.<Color>createBuilder()
                .name(Component.literal("Map color"))
                .description(OptionDescription.of(Component.literal("Marker color for this deposit on the world map. Alpha ignored.")))
                .binding(new Color(d.mapColor & 0xFFFFFF), () -> new Color(d.mapColor & 0xFFFFFF), colorSetter)
                .addListener(DepositBindings.persistOnChange(colorSetter))
                .controller(ColorControllerBuilder::create)
                .build();
        cat.option(mapColor);

        OptionGroup.Builder recipes = OptionGroup.createBuilder().name(Component.literal("Recipes & filters"));
        ButtonOption veinRecipes = ButtonOption.createBuilder()
                .name(Component.literal("Vein recipes"))
                .description(OptionDescription.of(Component.literal("COE vein recipe ids (createoreexcavation:vein), NOT items — each placed chunk rolls one. Leave empty only if you build the ore on the Inline tab.")))
                .text(Component.literal("Edit →"))
                .action((s, o) -> Minecraft.getInstance().setScreen(DepositPickers.veinRecipeScreen(s, d, DepositBindings::persist)))
                .build();
        recipes.option(veinRecipes);
        Option<String> infinite = Option.<String>createBuilder()
                .name(Component.literal("Infinite vein recipe"))
                .description(OptionDescription.of(Component.literal("Optional, advanced. A vein recipe used by '/coedeposits place' with a negative amount to make a never-depleting vein. Blank = none.")))
                .binding(d.veinRecipeInfinite, () -> d.veinRecipeInfinite, v -> d.veinRecipeInfinite = v)
                .addListener(DepositBindings.persistOnChange(v -> d.veinRecipeInfinite = v))
                .controller(o -> DropdownStringControllerBuilder.create(o).values(DepositBindings.recipeIds).allowAnyValue(true))
                .build();
        recipes.option(infinite);
        ButtonOption biomeFilter = ButtonOption.createBuilder()
                .name(Component.literal("Biome filter"))
                .description(OptionDescription.of(Component.literal("Spawn ONLY in biomes that have one of these tags (any one is enough). Empty = any biome. Tags, not names — e.g. c:is_mountain.")))
                .text(Component.literal("Edit →"))
                .action((s, o) -> Minecraft.getInstance().setScreen(DepositPickers.stringListScreen(s, "Biome filter",
                        d.biomeFilter, DepositBindings.biomeTagIds, "Biome tag",
                        "A biome tag the deposit may spawn in, e.g. c:is_mountain.", DepositBindings::persist)))
                .build();
        recipes.option(biomeFilter);
        ButtonOption dimensions = ButtonOption.createBuilder()
                .name(Component.literal("Dimensions"))
                .description(OptionDescription.of(Component.literal("Spawn ONLY in these dimensions. Empty = any (still within the global Enabled dimensions list).")))
                .text(Component.literal("Edit →"))
                .action((s, o) -> Minecraft.getInstance().setScreen(DepositPickers.stringListScreen(s, "Dimensions",
                        d.dimensions, DepositBindings.dimensionIds, "Dimension",
                        "A dimension id, e.g. minecraft:overworld.", DepositBindings::persist)))
                .build();
        recipes.option(dimensions);
        cat.group(recipes.build());

        // Fillers — numeric weights, so a plain inline ListOption works (no sub-screen).
        ListOption<Integer> fillers = ListOption.<Integer>createBuilder()
                .name(Component.literal("Fillers"))
                .description(OptionDescription.of(Component.literal("Composition, not a filter: each entry adds a 'barren chunk' weight to the per-chunk mix, competing with the vein-recipe weights. More fillers = patchier deposit. Empty = every chunk is ore.")))
                .binding(new ArrayList<>(d.fillers), () -> new ArrayList<>(d.fillers),
                        nl -> { d.fillers.clear(); d.fillers.addAll(nl); })
                .addListener((opt, event) -> {
                    if (event == OptionEventListener.Event.STATE_CHANGE) {
                        d.fillers.clear();
                        d.fillers.addAll(opt.pendingValue());
                        DepositBindings.persist();
                    }
                })
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(1).max(100_000))
                .initial(1)
                .build();
        cat.group(fillers);

        return cat.build();
    }
}
