package uk.niknik.coedeposits.deposit;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.biome.Biome;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import uk.niknik.coedeposits.Config;

/**
 * Blueprint for one ore type. Loaded by {@link DepositTypeLoader} from the
 * datapack {@code data/<ns>/deposit_type/*.json} files (the 14
 * standard ores ship in the jar) plus the optional
 * {@code config/coedeposits/deposits.json} overlay applied on top.
 *
 * <p><b>1.20.1 line:</b> in {@code platform-shared}; identical to the 1.21.1
 * source except {@code ResourceLocation} is built with its (still-public) 1.20.1
 * constructor rather than {@code fromNamespaceAndPath}. Codec/ExtraCodecs/mapPair
 * APIs are unchanged in 1.20.1.
 *
 * <h2>Unified inline schema (0.1.2+)</h2>
 * A type can carry inline {@link VeinSpec} and {@link DrillingSpec} blocks
 * that fully describe what's normally a separate COE vein + drilling recipe.
 * When present, the {@link uk.niknik.coedeposits.pack.BundledRecipePack}
 * virtual datapack auto-synthesises the recipes at server start.
 *
 * <p>Legacy schema still works for entries that reference an external recipe:
 * set {@link #veinRecipes} via {@code "vein_recipe": "X"} (singular) or
 * {@code "vein_recipes": [...]} (multi-recipe pool) and omit the inline blocks.
 *
 * <h2>Multi-recipe + fillers</h2>
 * A type can carry multiple vein recipes ({@link #veinRecipes}) and/or
 * filler entries ({@link #fillers}). Each chunk of a placed deposit blob
 * rolls its own outcome from this combined weighted pool.
 *
 * <h2>Self-replenishment</h2>
 * Optional {@link #replenishRatePerHour} field (default 0 = off) lets a
 * deposit's chunks regenerate {@code rate / 3600} units per second while loaded.
 */
public record DepositType(
        List<WeightedRecipe> veinRecipes,
        Optional<ResourceLocation> veinRecipeInfinite,
        List<WeightedFiller> fillers,
        double replenishRatePerHour,
        Placement placement,
        IntRange distance,
        IntRange sizeChunks,
        PerChunkUnits perChunkUnits,
        int weight,
        Optional<Integer> mapColor,
        List<TagKey<Biome>> biomeFilter,
        Optional<Config.RevealMode> reveal,
        List<ResourceLocation> dimensions,
        Optional<VeinSpec> vein,
        Optional<DrillingSpec> drilling,
        Optional<ExtractingSpec> extracting) {

    /**
     * Convenience: resolve the effective reveal mode for this type by falling
     * back to {@link Config#REVEAL_MODE} when the per-type field is unset.
     */
    public Config.RevealMode effectiveReveal() {
        return reveal.orElseGet(Config.REVEAL_MODE::get);
    }

    /**
     * True when this type may spawn in {@code dim}. An empty
     * {@link #dimensions} list means "any dimension" (subject to the global
     * {@link Config#ENABLED_DIMENSIONS} allow-list, checked at the picker entry).
     */
    public boolean matchesDimension(ResourceLocation dim) {
        return dimensions.isEmpty() || dimensions.contains(dim);
    }

    /**
     * Total weight of the per-chunk pool — recipes + fillers combined.
     * Used by the picker's roll arithmetic; returns 0 if the type is
     * pathologically empty (no recipes AND no fillers).
     */
    public int totalChunkPoolWeight() {
        int total = 0;
        for (WeightedRecipe wr : veinRecipes) total += wr.weight();
        for (WeightedFiller wf : fillers) total += wf.weight();
        return total;
    }

    /**
     * Who is responsible for deciding the chunk position of each vein:
     * <ul>
     *   <li>{@link #MANAGED} — our {@link uk.niknik.coedeposits.gen.DepositPlacer}
     *       picks a core chunk and builds a Perlin-blob deposit.</li>
     *   <li>{@link #COE} — COE's own spread generator chooses chunks; we record a
     *       single-chunk deposit into SavedData so the chunk shows up on the map.</li>
     * </ul>
     */
    public enum Placement implements StringRepresentable {
        MANAGED, COE;

        public static final Codec<Placement> CODEC = StringRepresentable.fromEnum(Placement::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One entry in the per-chunk recipe pool.
     *
     * @param recipe  COE vein recipe id assigned to chunks that roll this entry
     * @param weight  relative weight (default 1 when omitted in JSON)
     */
    public record WeightedRecipe(ResourceLocation recipe, int weight) {
        public static final Codec<WeightedRecipe> CODEC = RecordCodecBuilder.create(b -> b.group(
                ResourceLocation.CODEC.fieldOf("recipe").forGetter(WeightedRecipe::recipe),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(WeightedRecipe::weight)
        ).apply(b, WeightedRecipe::new));
    }

    /**
     * One filler entry in the per-chunk pool — part of the footprint but with no
     * OreData (drilling yields nothing).
     *
     * @param weight  relative weight (default 1 when omitted in JSON)
     */
    public record WeightedFiller(int weight) {
        public static final Codec<WeightedFiller> CODEC = RecordCodecBuilder.create(b -> b.group(
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(WeightedFiller::weight)
        ).apply(b, WeightedFiller::new));
    }

    /**
     * Inline COE vein recipe spec — synthesised into a {@code createoreexcavation:vein}
     * recipe by the virtual datapack.
     *
     * @param displayName            vein-finder / tooltip label (default: derived)
     * @param amountMultiplierMin    lower bound of COE's per-chunk randomMul range
     * @param amountMultiplierMax    upper bound of COE's per-chunk randomMul range
     * @param icon                   ItemStack id shown by the vein finder
     * @param iconCount              icon stack size (default 1)
     * @param placement              optional COE spread-placement (COE-mode only)
     * @param finite                 {@code "always"} (depletes) or {@code "never"}
     */
    public record VeinSpec(
            Optional<String> displayName,
            float amountMultiplierMin,
            float amountMultiplierMax,
            Optional<ResourceLocation> icon,
            int iconCount,
            Optional<PlacementSpec> placement,
            String finite) {

        public static final Codec<VeinSpec> CODEC = RecordCodecBuilder.create(b -> b.group(
                Codec.STRING.optionalFieldOf("display_name").forGetter(VeinSpec::displayName),
                Codec.FLOAT.optionalFieldOf("amount_multiplier_min", 2.0f).forGetter(VeinSpec::amountMultiplierMin),
                Codec.FLOAT.optionalFieldOf("amount_multiplier_max", 25.0f).forGetter(VeinSpec::amountMultiplierMax),
                ResourceLocation.CODEC.optionalFieldOf("icon").forGetter(VeinSpec::icon),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("icon_count", 1).forGetter(VeinSpec::iconCount),
                PlacementSpec.CODEC.optionalFieldOf("placement").forGetter(VeinSpec::placement),
                Codec.STRING.optionalFieldOf("finite", "always").forGetter(VeinSpec::finite)
        ).apply(b, VeinSpec::new));
    }

    /**
     * COE spread-placement spec — only used by {@link Placement#COE} types.
     *
     * @param salt       deterministic RNG seed component
     * @param separation minimum chunks between adjacent placements
     * @param spacing    grid cell size; one placement per cell, jittered
     */
    public record PlacementSpec(int salt, int separation, int spacing) {
        public static final Codec<PlacementSpec> CODEC = RecordCodecBuilder.create(b -> b.group(
                Codec.INT.fieldOf("salt").forGetter(PlacementSpec::salt),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("separation", 8).forGetter(PlacementSpec::separation),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("spacing", 256).forGetter(PlacementSpec::spacing)
        ).apply(b, PlacementSpec::new));
    }

    /**
     * Inline COE drilling recipe spec — synthesised into a
     * {@code createoreexcavation:drilling} recipe. Each output rolls its own chance
     * per drill cycle.
     *
     * @param outputs        items the drill can yield per cycle
     * @param ticks          drill cycle duration (default 100)
     * @param stress         Create stress units required (default 256)
     * @param drillTag       item tag of drill machines (default createoreexcavation:drills)
     */
    public record DrillingSpec(
            List<DrillOutputSpec> outputs,
            int ticks,
            int stress,
            Optional<ResourceLocation> drillTag) {

        private static final ResourceLocation DEFAULT_DRILL_TAG =
                new ResourceLocation("createoreexcavation", "drills");

        public ResourceLocation effectiveDrillTag() {
            return drillTag.orElse(DEFAULT_DRILL_TAG);
        }

        public static final Codec<DrillingSpec> CODEC = RecordCodecBuilder.create(b -> b.group(
                Codec.list(DrillOutputSpec.CODEC).fieldOf("outputs").forGetter(DrillingSpec::outputs),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("ticks", 100).forGetter(DrillingSpec::ticks),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("stress", 256).forGetter(DrillingSpec::stress),
                ResourceLocation.CODEC.optionalFieldOf("drill_tag").forGetter(DrillingSpec::drillTag)
        ).apply(b, DrillingSpec::new));
    }

    /**
     * One item entry in a {@link DrillingSpec#outputs} list.
     *
     * @param item    item id to drop
     * @param count   stack size per drop (default 1)
     * @param chance  per-cycle drop probability, 0.0..1.0 (default 1.0)
     */
    public record DrillOutputSpec(ResourceLocation item, int count, float chance) {
        public static final Codec<DrillOutputSpec> CODEC = RecordCodecBuilder.create(b -> b.group(
                ResourceLocation.CODEC.fieldOf("item").forGetter(DrillOutputSpec::item),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("count", 1).forGetter(DrillOutputSpec::count),
                Codec.FLOAT.optionalFieldOf("chance", 1.0f).forGetter(DrillOutputSpec::chance)
        ).apply(b, DrillOutputSpec::new));
    }

    /**
     * Inline COE fluid-extraction spec — the fluid analogue of {@link DrillingSpec},
     * synthesised into a {@code createoreexcavation:extracting} recipe. Placed exactly
     * like a solid deposit (same vein + {@code per_chunk_units} budget); harvested with
     * COE's Extractor for {@code amount} mB of {@code fluid} per cycle.
     *
     * @param fluid     fluid id produced, e.g. {@code minecraft:water} (source fluid)
     * @param amount    millibuckets yielded per extraction cycle (default 500)
     * @param ticks     extraction cycle duration in ticks (default 20)
     * @param stress    Create stress units the extractor consumes (default 256)
     * @param drillTag  item tag of drill heads allowed (default createoreexcavation:drills)
     */
    public record ExtractingSpec(
            ResourceLocation fluid,
            int amount,
            int ticks,
            int stress,
            Optional<ResourceLocation> drillTag) {

        public static final Codec<ExtractingSpec> CODEC = RecordCodecBuilder.create(b -> b.group(
                ResourceLocation.CODEC.fieldOf("fluid").forGetter(ExtractingSpec::fluid),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("amount", 500).forGetter(ExtractingSpec::amount),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("ticks", 20).forGetter(ExtractingSpec::ticks),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("stress", 256).forGetter(ExtractingSpec::stress),
                ResourceLocation.CODEC.optionalFieldOf("drill_tag").forGetter(ExtractingSpec::drillTag)
        ).apply(b, ExtractingSpec::new));
    }

    /** Accepts either a single biome tag string or an array for {@code biome_filter}. */
    private static final Codec<List<TagKey<Biome>>> BIOME_TAGS_CODEC =
            Codec.either(
                    TagKey.codec(Registries.BIOME),
                    Codec.list(TagKey.codec(Registries.BIOME))
            ).xmap(
                    e -> e.map(List::of, l -> l),
                    l -> l.size() == 1
                            ? com.mojang.datafixers.util.Either.left(l.get(0))
                            : com.mojang.datafixers.util.Either.right(l)
            );

    /** Same single-string-or-array convenience for the {@code dimensions} field. */
    private static final Codec<List<ResourceLocation>> DIMENSIONS_CODEC =
            Codec.either(
                    ResourceLocation.CODEC,
                    Codec.list(ResourceLocation.CODEC)
            ).xmap(
                    e -> e.map(List::of, l -> l),
                    l -> l.size() == 1
                            ? com.mojang.datafixers.util.Either.left(l.get(0))
                            : com.mojang.datafixers.util.Either.right(l)
            );

    /** Same single-object-or-array convenience for {@code vein_recipes}. */
    private static final Codec<List<WeightedRecipe>> VEIN_RECIPES_CODEC =
            Codec.either(
                    WeightedRecipe.CODEC,
                    Codec.list(WeightedRecipe.CODEC)
            ).xmap(
                    e -> e.map(List::of, l -> l),
                    l -> l.size() == 1
                            ? com.mojang.datafixers.util.Either.left(l.get(0))
                            : com.mojang.datafixers.util.Either.right(l)
            );

    /** Default value for {@code distance} on COE-placement entries (no distance gate). */
    private static final IntRange DEFAULT_DISTANCE = new IntRange(0, Integer.MAX_VALUE);
    /** Default value for {@code size_chunks} on COE-placement entries (single chunk). */
    private static final IntRange DEFAULT_SIZE_CHUNKS = new IntRange(1, 1);
    /** Default value for {@code per_chunk_units} on COE-placement entries (unused). */
    private static final PerChunkUnits DEFAULT_PER_CHUNK_UNITS = new PerChunkUnits(0L, Optional.of(0L));

    // ── Codec ────────────────────────────────────────────────────────────────
    // DataFixerUpper's group(...) maxes out at 16 fields, but this type has 17 JSON
    // fields. They're split into two flattened MapCodec halves — CORE_CODEC (14) and
    // INLINE_CODEC (3) — joined with Codec.mapPair, which reads/writes BOTH field
    // sets from the SAME flat JSON object. CORE_CODEC / INLINE_CODEC must be declared
    // before CODEC (static-init order) or CODEC sees them null.

    /** First 14 fields (recipe pool / placement / budget / filters); see {@link #CODEC}. */
    private static final MapCodec<CoreFields> CORE_CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
            VEIN_RECIPES_CODEC.optionalFieldOf("vein_recipes", List.of()).forGetter(CoreFields::veinRecipes),
            // Legacy: single recipe. Folded into veinRecipes by merge() at decode time.
            ResourceLocation.CODEC.optionalFieldOf("vein_recipe").forGetter(CoreFields::legacyVeinRecipe),
            ResourceLocation.CODEC.optionalFieldOf("vein_recipe_infinite").forGetter(CoreFields::veinRecipeInfinite),
            Codec.list(WeightedFiller.CODEC).optionalFieldOf("fillers", List.of()).forGetter(CoreFields::fillers),
            Codec.DOUBLE.optionalFieldOf("replenish_rate_per_hour", 0.0).forGetter(CoreFields::replenishRatePerHour),
            Placement.CODEC.optionalFieldOf("placement", Placement.MANAGED).forGetter(CoreFields::placement),
            IntRange.CODEC.optionalFieldOf("distance", DEFAULT_DISTANCE).forGetter(CoreFields::distance),
            IntRange.CODEC.optionalFieldOf("size_chunks", DEFAULT_SIZE_CHUNKS).forGetter(CoreFields::sizeChunks),
            PerChunkUnits.CODEC.optionalFieldOf("per_chunk_units", DEFAULT_PER_CHUNK_UNITS).forGetter(CoreFields::perChunkUnits),
            ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("weight", 0).forGetter(CoreFields::weight),
            Codec.INT.optionalFieldOf("map_color").forGetter(CoreFields::mapColor),
            BIOME_TAGS_CODEC.optionalFieldOf("biome_filter", List.of()).forGetter(CoreFields::biomeFilter),
            Config.RevealMode.CODEC.optionalFieldOf("reveal").forGetter(CoreFields::reveal),
            DIMENSIONS_CODEC.optionalFieldOf("dimensions", List.of()).forGetter(CoreFields::dimensions)
    ).apply(b, CoreFields::new));

    /** Last 3 fields — the inline-synthesis blocks, flattened into the SAME object. */
    private static final MapCodec<InlineFields> INLINE_CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
            VeinSpec.CODEC.optionalFieldOf("vein").forGetter(InlineFields::vein),
            DrillingSpec.CODEC.optionalFieldOf("drilling").forGetter(InlineFields::drilling),
            // Keyed "fluid" in deposits.json (user-facing); distinct from COE's recipe-level coolant field.
            ExtractingSpec.CODEC.optionalFieldOf("fluid").forGetter(InlineFields::extracting)
    ).apply(b, InlineFields::new));

    /**
     * Codec used by {@link DepositTypeLoader} when parsing config JSON. The legacy
     * singular {@code vein_recipe} field is folded into {@code vein_recipes} at decode.
     */
    public static final Codec<DepositType> CODEC =
            Codec.mapPair(CORE_CODEC, INLINE_CODEC).codec().xmap(
                    p -> merge(p.getFirst(), p.getSecond()),
                    t -> Pair.of(CoreFields.from(t), InlineFields.from(t)));

    /** Carrier for {@link #CORE_CODEC}'s 14 fields (the codec split's first half). */
    private record CoreFields(
            List<WeightedRecipe> veinRecipes,
            Optional<ResourceLocation> legacyVeinRecipe,
            Optional<ResourceLocation> veinRecipeInfinite,
            List<WeightedFiller> fillers,
            double replenishRatePerHour,
            Placement placement,
            IntRange distance,
            IntRange sizeChunks,
            PerChunkUnits perChunkUnits,
            int weight,
            Optional<Integer> mapColor,
            List<TagKey<Biome>> biomeFilter,
            Optional<Config.RevealMode> reveal,
            List<ResourceLocation> dimensions) {

        /** Split a built type into core fields for encoding (legacy field stays empty). */
        static CoreFields from(DepositType t) {
            return new CoreFields(t.veinRecipes(), Optional.empty(), t.veinRecipeInfinite(),
                    t.fillers(), t.replenishRatePerHour(), t.placement(), t.distance(),
                    t.sizeChunks(), t.perChunkUnits(), t.weight(), t.mapColor(),
                    t.biomeFilter(), t.reveal(), t.dimensions());
        }
    }

    /** Carrier for {@link #INLINE_CODEC}'s 3 inline-synthesis fields (the second half). */
    private record InlineFields(
            Optional<VeinSpec> vein,
            Optional<DrillingSpec> drilling,
            Optional<ExtractingSpec> extracting) {

        static InlineFields from(DepositType t) {
            return new InlineFields(t.vein(), t.drilling(), t.extracting());
        }
    }

    /**
     * Codec apply target — folds the legacy {@code vein_recipe} field into the new
     * {@code veinRecipes} list and assembles the record from the two codec halves.
     */
    private static DepositType merge(CoreFields c, InlineFields i) {
        List<WeightedRecipe> effective = c.veinRecipes();
        if (c.veinRecipes().isEmpty() && c.legacyVeinRecipe().isPresent()) {
            effective = List.of(new WeightedRecipe(c.legacyVeinRecipe().get(), 1));
        } else if (!c.veinRecipes().isEmpty() && c.legacyVeinRecipe().isPresent()) {
            uk.niknik.coedeposits.Coedeposits.LOGGER.warn(
                    "[coedeposits] deposits.json entry has both 'vein_recipe' (legacy) and 'vein_recipes' (new) — " +
                    "using the new array and ignoring the legacy field. Remove 'vein_recipe' to silence this warning.");
        }
        return new DepositType(effective, c.veinRecipeInfinite(), c.fillers(), c.replenishRatePerHour(),
                c.placement(), c.distance(), c.sizeChunks(), c.perChunkUnits(), c.weight(), c.mapColor(),
                c.biomeFilter(), c.reveal(), c.dimensions(), i.vein(), i.drilling(), i.extracting());
    }

    /** Inclusive integer interval — used for {@code distance} and {@code size_chunks}. */
    public record IntRange(int min, int max) {
        public static final Codec<IntRange> CODEC = RecordCodecBuilder.create(b -> b.group(
                Codec.INT.fieldOf("min").forGetter(IntRange::min),
                Codec.INT.fieldOf("max").forGetter(IntRange::max)
        ).apply(b, IntRange::new));

        /** Uniform integer sample in [min, max]. */
        public int sample(net.minecraft.util.RandomSource rng) {
            return min == max ? min : min + rng.nextInt(max - min + 1);
        }
    }

    /**
     * Per-chunk ore budget. {@code min} sets the floor at tier=0; {@code max} (if
     * present) sets the tier=1 ceiling via lerp, else open-ended growth via Config.
     */
    public record PerChunkUnits(long min, Optional<Long> max) {
        public static final Codec<PerChunkUnits> CODEC = RecordCodecBuilder.create(b -> b.group(
                Codec.LONG.fieldOf("min").forGetter(PerChunkUnits::min),
                Codec.LONG.optionalFieldOf("max").forGetter(PerChunkUnits::max)
        ).apply(b, PerChunkUnits::new));

        /**
         * Compute the target per-chunk unit count for a given tier.
         *
         * @param tier              distance tier in {@code [0,1]}
         * @param unboundedGrowth   {@link uk.niknik.coedeposits.Config#UNBOUNDED_GROWTH}, used when max is empty
         */
        public double computeTarget(float tier, double unboundedGrowth) {
            if (max.isPresent()) {
                return min + (max.get() - min) * tier;
            }
            return min * (1.0 + tier * unboundedGrowth);
        }
    }
}
