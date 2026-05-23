package uk.niknik.coedeposits.deposit;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.biome.Biome;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import uk.niknik.coedeposits.Config;

/**
 * Blueprint for one ore type. Loaded by {@link DepositTypeLoader} from
 * the single external config file {@code config/coedeposits/deposits.json}
 * (auto-created on first run from the jar's bundled defaults).
 *
 * @param veinRecipe         id of the COE VeinRecipe to assign to chunks that
 *                            become this deposit. Must exist in the recipe
 *                            manager — otherwise the picker silently skips
 *                            this type
 * @param veinRecipeInfinite optional alternate recipe with {@code finite=NEVER}
 *                            used by {@code /coedeposits place ... -1} to spawn
 *                            never-depleting veins
 * @param items              currently unused — future hook for synthesising
 *                            drilling outputs from a single source of truth
 * @param placement          MANAGED (our blob algorithm) vs COE (let
 *                            {@link com.tom.createores.util.RandomSpreadGenerator}
 *                            place this, we just observe + record for the map)
 * @param distance           block-distance window from spawn where this type
 *                            is eligible to spawn (managed-only). Ignored for
 *                            {@link Placement#COE}
 * @param sizeChunks         number-of-chunks range for the deposit blob
 *                            (managed-only). Tier lerps within
 * @param perChunkUnits      direct ore-budget per chunk (managed-only). See
 *                            {@link PerChunkUnits} for the formula and
 *                            unbounded-max semantics
 * @param weight             relative weight in the weighted random pick
 *                            among eligible managed types. 0 disables this
 *                            type for managed placement (default for COE
 *                            entries — they don't go through {@code DepositPlacer})
 * @param mapColor           optional packed RGB int for the world-map overlay.
 *                            Absent → renderer falls back to hash(typeId)
 * @param biomeFilter        OR-of biome tags this type may spawn in (managed-only).
 *                            Empty list = no biome restriction. For COE
 *                            placement biome filter lives inside the VeinRecipe
 * @param reveal             optional per-type override of {@link Config#REVEAL_MODE};
 *                            absent → use the global default
 * @param dimensions         optional allow-list of dimension ids in which this
 *                            type may spawn. Empty list = any enabled dimension.
 *                            Intersected with {@link Config#ENABLED_DIMENSIONS} —
 *                            a type can only spawn in dimensions that are both
 *                            globally enabled AND (if specified) listed here
 */
public record DepositType(
        ResourceLocation veinRecipe,
        Optional<ResourceLocation> veinRecipeInfinite,
        List<ItemEntry> items,
        Placement placement,
        IntRange distance,
        IntRange sizeChunks,
        PerChunkUnits perChunkUnits,
        int weight,
        Optional<Integer> mapColor,
        List<TagKey<Biome>> biomeFilter,
        Optional<Config.RevealMode> reveal,
        List<ResourceLocation> dimensions) {

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
     * {@link Config#ENABLED_DIMENSIONS} allow-list, which is checked
     * separately at the picker entry point).
     */
    public boolean matchesDimension(ResourceLocation dim) {
        return dimensions.isEmpty() || dimensions.contains(dim);
    }

    /**
     * Who is responsible for deciding the chunk position of each vein:
     * <ul>
     *   <li>{@link #MANAGED} — our {@link uk.niknik.coedeposits.gen.DepositPlacer}
     *       picks a core chunk and builds a Perlin-blob deposit; {@code distance},
     *       {@code size_chunks}, {@code per_chunk_units}, {@code weight} and
     *       {@code biome_filter} are all honoured.</li>
     *   <li>{@link #COE} — COE's own {@code RandomSpreadGenerator} chooses
     *       chunks via the VeinRecipe's spread placement. We do nothing during
     *       placement but record a single-chunk {@link Deposit} into SavedData
     *       so the chunk shows up on the map. Managed-only fields are ignored.</li>
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
     * Accepts either a single string ({@code "c:is_mountain"}) or an array
     * ({@code ["c:is_mountain", "minecraft:is_taiga"]}) for {@code biome_filter}
     * to keep the JSON readable for both single-biome and multi-biome ores.
     */
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

    /**
     * Same single-string-or-array convenience for the {@code dimensions}
     * field — readable both as {@code "dimensions": "minecraft:the_nether"}
     * and as {@code "dimensions": ["minecraft:overworld", ...]}.
     */
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

    /** Default value for {@code distance} on COE-placement entries (no distance gate). */
    private static final IntRange DEFAULT_DISTANCE = new IntRange(0, Integer.MAX_VALUE);
    /** Default value for {@code size_chunks} on COE-placement entries (single chunk). */
    private static final IntRange DEFAULT_SIZE_CHUNKS = new IntRange(1, 1);
    /** Default value for {@code per_chunk_units} on COE-placement entries (unused). */
    private static final PerChunkUnits DEFAULT_PER_CHUNK_UNITS = new PerChunkUnits(0L, Optional.of(0L));

    /** Codec used by {@link DepositTypeLoader} when parsing config JSON. */
    public static final Codec<DepositType> CODEC = RecordCodecBuilder.create(b -> b.group(
            ResourceLocation.CODEC.fieldOf("vein_recipe").forGetter(DepositType::veinRecipe),
            ResourceLocation.CODEC.optionalFieldOf("vein_recipe_infinite").forGetter(DepositType::veinRecipeInfinite),
            Codec.list(ItemEntry.CODEC).optionalFieldOf("items", List.of()).forGetter(DepositType::items),
            Placement.CODEC.optionalFieldOf("placement", Placement.MANAGED).forGetter(DepositType::placement),
            IntRange.CODEC.optionalFieldOf("distance", DEFAULT_DISTANCE).forGetter(DepositType::distance),
            IntRange.CODEC.optionalFieldOf("size_chunks", DEFAULT_SIZE_CHUNKS).forGetter(DepositType::sizeChunks),
            PerChunkUnits.CODEC.optionalFieldOf("per_chunk_units", DEFAULT_PER_CHUNK_UNITS).forGetter(DepositType::perChunkUnits),
            ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("weight", 0).forGetter(DepositType::weight),
            Codec.INT.optionalFieldOf("map_color").forGetter(DepositType::mapColor),
            BIOME_TAGS_CODEC.optionalFieldOf("biome_filter", List.of()).forGetter(DepositType::biomeFilter),
            Config.RevealMode.CODEC.optionalFieldOf("reveal").forGetter(DepositType::reveal),
            DIMENSIONS_CODEC.optionalFieldOf("dimensions", List.of()).forGetter(DepositType::dimensions)
    ).apply(b, DepositType::new));

    /**
     * One entry in the future {@code items} pool — kept for forward-compatibility
     * with weighted drilling outputs.
     */
    public record ItemEntry(ResourceLocation item, int weight, int minCount, int maxCount) {
        /** JSON codec — defaults missing weight/min/max to 1. */
        public static final Codec<ItemEntry> CODEC = RecordCodecBuilder.create(b -> b.group(
                ResourceLocation.CODEC.fieldOf("item").forGetter(ItemEntry::item),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(ItemEntry::weight),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("min", 1).forGetter(ItemEntry::minCount),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("max", 1).forGetter(ItemEntry::maxCount)
        ).apply(b, ItemEntry::new));
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
     * Per-chunk ore budget for this deposit type. {@code min} sets the
     * floor at tier=0 (near spawn). {@code max}, if present, sets the
     * tier=1 ceiling via linear lerp. If absent, the formula becomes
     * {@code min × (1 + tier × unbounded_growth)} from Config — open-ended
     * growth so far-away deposits scale richly without a hardcoded cap.
     */
    public record PerChunkUnits(long min, Optional<Long> max) {
        /** Codec: {@code min} required, {@code max} optional. */
        public static final Codec<PerChunkUnits> CODEC = RecordCodecBuilder.create(b -> b.group(
                Codec.LONG.fieldOf("min").forGetter(PerChunkUnits::min),
                Codec.LONG.optionalFieldOf("max").forGetter(PerChunkUnits::max)
        ).apply(b, PerChunkUnits::new));

        /**
         * Compute the target per-chunk unit count for a given tier.
         *
         * @param tier              distance tier in {@code [0,1]}
         * @param unboundedGrowth   coefficient from {@link uk.niknik.coedeposits.Config#UNBOUNDED_GROWTH},
         *                          only used when {@code max} is empty
         * @return                  unit count to aim for; converted to
         *                          {@code OreData.randomMul} downstream via
         *                          {@link uk.niknik.coedeposits.gen.DepositPlacer#amountMulForTarget}
         */
        public double computeTarget(float tier, double unboundedGrowth) {
            if (max.isPresent()) {
                return min + (max.get() - min) * tier;
            }
            return min * (1.0 + tier * unboundedGrowth);
        }
    }
}
