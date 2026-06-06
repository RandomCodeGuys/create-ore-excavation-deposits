package uk.niknik.coedeposits.client.config.general;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;

import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.client.config.Bindings;
import uk.niknik.coedeposits.client.config.deposit.DepositPickers;

/** "Generation" group of the General tab: distance gradient, density, prospect scan, dimensions. */
public final class GenerationGroup {
    private GenerationGroup() {}

    /** Working copy of enabled_dimensions while its sub-editor is open. */
    private static List<String> workingDims = new ArrayList<>();

    public static OptionGroup build() {
        OptionGroup.Builder group = OptionGroup.createBuilder()
                .name(Component.literal("Generation"));

        Option<Double> baseRadius = Option.<Double>createBuilder()
                .name(Component.literal("Base radius"))
                .description(OptionDescription.of(Component.literal("Base radius (blocks) for the distance-gradient log curve.")))
                .binding(Config.BASE_RADIUS.getDefault(), Config.BASE_RADIUS::get, v -> Bindings.persist(Config.BASE_RADIUS, v, "Base radius"))
                .controller(o -> DoubleFieldControllerBuilder.create(o).min(1.0).max(1_000_000.0)
                        .formatValue(d -> Component.literal(String.format(Locale.ROOT, "%.0f", d))))
                .build();
        group.option(baseRadius);

        Option<Double> maxRadius = Option.<Double>createBuilder()
                .name(Component.literal("Max radius"))
                .description(OptionDescription.of(Component.literal("Saturation radius (blocks): tier ~= 1.0 at and beyond this distance.")))
                .binding(Config.MAX_RADIUS.getDefault(), Config.MAX_RADIUS::get, v -> Bindings.persist(Config.MAX_RADIUS, v, "Max radius"))
                .controller(o -> DoubleFieldControllerBuilder.create(o).min(1.0).max(10_000_000.0)
                        .formatValue(d -> Component.literal(String.format(Locale.ROOT, "%.0f", d))))
                .build();
        group.option(maxRadius);

        // Edited as a whole number (probability x 100 000) so fine values like 0.0005 stay enterable.
        double coreScale = 100_000.0;
        Option<Integer> coreSpawn = Option.<Integer>createBuilder()
                .name(Component.literal("Core spawn (per 100k chunks)"))
                .description(OptionDescription.of(Component.literal("Deposit-core candidate rolls per 100,000 chunks. Default 50 (= 0.0005/chunk, ~1 per 2000). Higher = denser maps (500 ~ 0.005); lower = rarer (10 ~ 0.0001).")))
                .binding((int) Math.round(Config.CORE_SPAWN_PROBABILITY.getDefault() * coreScale),
                        () -> (int) Math.round(Config.CORE_SPAWN_PROBABILITY.get() * coreScale),
                        v -> Bindings.persist(Config.CORE_SPAWN_PROBABILITY, v / coreScale, "Core spawn (per 100k chunks)"))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(0).max(100_000)
                        .formatValue(i -> Component.literal(String.format(Locale.ROOT, "%d", i))))
                .build();
        group.option(coreSpawn);

        // Edited as a percentage (mul x 100).
        double edgeScale = 100.0;
        Option<Integer> edgeRichness = Option.<Integer>createBuilder()
                .name(Component.literal("Edge richness (%)"))
                .description(OptionDescription.of(Component.literal("Outer-chunk richness as a percentage of the core's amountMul. Default 30 (= 0.30).")))
                .binding((int) Math.round(Config.EDGE_AMOUNT_MUL.getDefault() * edgeScale),
                        () -> (int) Math.round(Config.EDGE_AMOUNT_MUL.get() * edgeScale),
                        v -> Bindings.persist(Config.EDGE_AMOUNT_MUL, v / edgeScale, "Edge richness (%)"))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(0).max(100)
                        .formatValue(i -> Component.literal(String.format(Locale.ROOT, "%d", i))))
                .build();
        group.option(edgeRichness);

        Option<Double> unboundedGrowth = Option.<Double>createBuilder()
                .name(Component.literal("Unbounded growth"))
                .description(OptionDescription.of(Component.literal("Far-deposit scaling used when a type's per_chunk_units.max is absent.")))
                .binding(Config.UNBOUNDED_GROWTH.getDefault(), Config.UNBOUNDED_GROWTH::get, v -> Bindings.persist(Config.UNBOUNDED_GROWTH, v, "Unbounded growth"))
                .controller(o -> DoubleFieldControllerBuilder.create(o).min(0.0).max(100_000.0)
                        .formatValue(d -> Component.literal(String.format(Locale.ROOT, "%.1f", d))))
                .build();
        group.option(unboundedGrowth);

        Option<Integer> prospectRadius = Option.<Integer>createBuilder()
                .name(Component.literal("Prospect radius"))
                .description(OptionDescription.of(Component.literal("Block radius pre-scanned around spawn on server start (0 = off).")))
                .binding(Config.PROSPECT_RADIUS.getDefault(), Config.PROSPECT_RADIUS::get, v -> Bindings.persist(Config.PROSPECT_RADIUS, v, "Prospect radius"))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(0).max(16_000)
                        .formatValue(i -> Component.literal(String.format(Locale.ROOT, "%d", i))))
                .build();
        group.option(prospectRadius);

        Option<Integer> chunksPerTick = Option.<Integer>createBuilder()
                .name(Component.literal("Prospect chunks/tick"))
                .description(OptionDescription.of(Component.literal("Max pending deposit placements materialized per server tick.")))
                .binding(Config.PROSPECT_CHUNKS_PER_TICK.getDefault(), Config.PROSPECT_CHUNKS_PER_TICK::get, v -> Bindings.persist(Config.PROSPECT_CHUNKS_PER_TICK, v, "Prospect chunks/tick"))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(16).max(4_096)
                        .formatValue(i -> Component.literal(String.format(Locale.ROOT, "%d", i))))
                .build();
        group.option(chunksPerTick);

        group.option(dimensionsButton());

        return group.build();
    }

    private static ButtonOption dimensionsButton() {
        return ButtonOption.createBuilder()
                .name(Component.literal("Enabled dimensions"))
                .description(OptionDescription.of(Component.literal("Namespaced dimension ids where coedeposits runs managed/COE placement and map tracking.")))
                .text(Component.literal("Edit →"))
                .action((screen, opt) -> {
                    workingDims = new ArrayList<>(Config.ENABLED_DIMENSIONS.get());
                    Minecraft.getInstance().setScreen(DepositPickers.stringListScreen(
                            screen, "Enabled dimensions", workingDims, dimIds(),
                            "Dimension", "A dimension id, e.g. minecraft:overworld.",
                            () -> {
                                Config.ENABLED_DIMENSIONS.set(workingDims);
                                Config.SPEC.save();
                            }));
                })
                .build();
    }

    /** Known dimension ids for the dropdown (from the active connection, else vanilla). */
    private static List<String> dimIds() {
        try {
            var c = Minecraft.getInstance().getConnection();
            if (c != null) {
                return c.levels().stream().map(k -> k.location().toString()).sorted().toList();
            }
        } catch (Exception ignored) {
            // fall through to defaults
        }
        return List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
    }
}
