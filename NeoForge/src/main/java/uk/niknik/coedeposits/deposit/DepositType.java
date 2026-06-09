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
 * <h2>Unified inline schema (0.1.2+)</h2>
 * A type can carry inline {@link VeinSpec} and {@link DrillingSpec} blocks
 * that fully describe what's normally a separate COE vein + drilling recipe.
 * When present, the {@link uk.niknik.coedeposits.pack.BundledRecipePack}
 * virtual datapack auto-synthesises the recipes at server start — admins
 * configure everything (placement, ore distribution, drill outputs, slag,
 * fillers, replenish) in ONE file without touching datapack-level recipe
 * JSONs.
 *
 * <p>Legacy schema still works for entries that reference an external recipe
 * (bundled in another mod or shipped in a datapack): set
 * {@link #veinRecipes} via {@code "vein_recipe": "X"} (singular) or
 * {@code "vein_recipes": [...]} (multi-recipe pool) and omit the inline
 * {@code vein:} / {@code drilling:} blocks. The picker doesn't care which
 * path provided the recipe — it just looks up by recipe id.
 *
 * <h2>Multi-recipe + fillers</h2>
 * A type can carry multiple vein recipes ({@link #veinRecipes}) and/or
 * filler entries ({@link #fillers}). Each chunk of a placed deposit blob
 * rolls its own outcome from this combined weighted pool — deterministic
 * on (depositSeed, chunkX, chunkZ) so the layout is stable across restarts.
 * Filler chunks have no OreData (drill yields nothing) and render with a
 * distinct grey marker on the world map.
 *
 * <h2>Backward compatibility</h2>
 * Old configs using the singular {@code vein_recipe: "X"} field still load.
 * The codec folds them into a single-entry {@link #veinRecipes} list with
 * weight 1 and no fillers. If both {@code vein_recipe} and {@code vein_recipes}
 * are present, the new array wins and the legacy field is ignored with a
 * warning in the log. The pre-0.1.2 {@code items} field is silently
 * ignored — its role is now played by {@code drilling.outputs}.
 *
 * <h2>Self-replenishment</h2>
 * Optional {@link #replenishRatePerHour} field (default 0 = off) lets a
 * deposit's chunks regenerate {@code rate / 3600} units per second while
 * loaded. Capped at the chunk's initial extracted amount so a deposit can
 * never grow beyond its original size — replenishment only fills the hole
 * that drilling created.
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
     * Friendly, human-readable label for a deposit of {@code typeId}: the inline
     * vein's {@code display_name} if the type sets one, otherwise a prettified
     * type id. Used for the discovery chat notification's {@code %name%}.
     *
     * @param type   the resolved type, or {@code null} if unknown
     * @param typeId the deposit's type id (used for the prettified fallback)
     */
    public static String displayNameOf(DepositType type, ResourceLocation typeId) {
        if (type != null) {
            Optional<String> dn = type.vein()
                    .flatMap(VeinSpec::displayName)
                    .filter(s -> !s.isBlank());
            if (dn.isPresent()) return dn.get();
        }
        return prettyTypeName(typeId);
    }

    /**
     * Title-cased label from a type id, dropping COE's {@code ore_vein_type/}
     * folder and trailing {@code _vein}: {@code createoreexcavation:ore_vein_type/redstone}
     * → "Redstone"; {@code coedeposits:nether_gold} → "Nether Gold".
     */
    public static String prettyTypeName(ResourceLocation typeId) {
        String path = typeId.getPath();
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        if (path.endsWith("_vein")) path = path.substring(0, path.length() - "_vein".length());
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : typeId.getPath();
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
     * One entry in the per-chunk recipe pool. Weight is relative to other
     * recipes + fillers in the same pool — a recipe with weight 50 vs a
     * filler with weight 50 means 50/50 ore vs empty chunks on average.
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
     * One filler entry in the per-chunk pool. Chunks that roll a filler are
     * part of the deposit footprint (visible on the map, tracked in SavedData)
     * but have no OreData — drilling yields nothing. Use to make deposits
     * feel less than uniformly rich, simulating tailings or low-grade rock.
     *
     * @param weight  relative weight (default 1 when omitted in JSON)
     */
    public record WeightedFiller(int weight) {
        public static final Codec<WeightedFiller> CODEC = RecordCodecBuilder.create(b -> b.group(
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(WeightedFiller::weight)
        ).apply(b, WeightedFiller::new));
    }

    /**
     * Inline COE vein recipe spec. When present on a deposit type, our
     * virtual datapack synthesises a {@code createoreexcavation:vein} JSON
     * recipe with these fields at server start, removing the need to ship a
     * separate datapack for the vein placement metadata.
     *
     * @param displayName            user-facing label for vein-finder / tooltips
     *                                (default: derived from the type id)
     * @param amountMultiplierMin    lower bound of COE's per-chunk randomMul range.
     *                                Our picker inverts this to compute chunk units;
     *                                values outside the [min, max] bound produce
     *                                negative or out-of-range units (clamped to 0)
     * @param amountMultiplierMax    upper bound of COE's per-chunk randomMul range
     * @param icon                   ItemStack id displayed by the vein finder
     *                                (default: derived from drilling.outputs[0] if present)
     * @param iconCount              icon stack size (default 1)
     * @param placement              optional COE spread-placement spec; required
     *                                for {@link Placement#COE} types, ignored by
     *                                {@link Placement#MANAGED}
     * @param finite                 {@code "always"} (default; depletes after
     *                                {@code per_chunk_units} are drilled) or
     *                                {@code "never"} (infinite vein)
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
     * Mirrors COE's {@code RandomSpreadStructurePlacement} so the generated
     * vein recipe places the same way COE's own bundled recipes do.
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
     * Inline COE drilling recipe spec. When present on a deposit type, our
     * virtual datapack synthesises a {@code createoreexcavation:drilling} JSON
     * recipe with these outputs. Each output rolls its own chance independently
     * per drill cycle — multiple outputs simulate a "primary ore + slag" or
     * "weighted mix" behaviour without needing custom code.
     *
     * @param outputs        list of items the drill can yield per cycle. Order
     *                        matters only for display (tooltip uses the order
     *                        as provided)
     * @param ticks          drill cycle duration (default 100)
     * @param stress         Create stress units required for the drill
     *                        (default 256)
     * @param drillTag       item tag of drill machines that can mine this
     *                        recipe (default {@code createoreexcavation:drills})
     */
    public record DrillingSpec(
            List<DrillOutputSpec> outputs,
            int ticks,
            int stress,
            Optional<ResourceLocation> drillTag) {

        private static final ResourceLocation DEFAULT_DRILL_TAG =
                ResourceLocation.fromNamespaceAndPath("createoreexcavation", "drills");

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
     * One item entry in a {@link DrillingSpec#outputs} list. Each entry rolls
     * its chance independently per drill cycle — for "primary ore + slag"
     * patterns, set the primary to {@code chance = 1.0} (guaranteed) and the
     * slag items to fractional chances.
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
     * Inline COE fluid-extraction spec — the fluid analogue of {@link DrillingSpec}.
     * When present, the {@link uk.niknik.coedeposits.pack.BundledRecipePack} virtual
     * datapack synthesises a {@code createoreexcavation:extracting} recipe bound to
     * this type's vein. The deposit is placed exactly like a solid one (same vein +
     * {@code per_chunk_units} budget — COE's Extractor and Drill share the same vein
     * consumption logic); the player harvests it with COE's <b>Extractor</b> machine
     * instead of the Drill, getting {@code amount} mB of {@code fluid} per cycle.
     *
     * <p>A type may carry {@code drilling} and {@code extracting} both (the vein can
     * then be drilled for items OR pumped for fluid), but the common case is one or
     * the other.
     *
     * @param fluid     fluid id produced, e.g. {@code minecraft:water} (source fluid)
     * @param amount    millibuckets yielded per extraction cycle (default 500)
     * @param ticks     extraction cycle duration in ticks (default 20)
     * @param stress    Create stress units the extractor consumes (default 256)
     * @param drillTag  item tag of drill heads allowed
     *                   (default {@code createoreexcavation:drills})
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

    /**
     * Same single-object-or-array convenience for {@code vein_recipes}:
     * accept a single {@code WeightedRecipe} object as syntactic sugar for a
     * one-element list. Lets admins write
     * {@code "vein_recipes": {"recipe": "coedeposits:iron_vein"}} for the
     * common single-ore case without forced array brackets.
     */
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
    // sets from the SAME flat JSON object. The on-disk schema is unchanged; the split
    // is purely to stay within DFU's arity limit. CORE_CODEC / INLINE_CODEC must be
    // declared before CODEC (static-init order) or CODEC sees them null.

    /** First 14 fields (recipe pool / placement / budget / filters); see {@link #CODEC}. */
    private static final MapCodec<CoreFields> CORE_CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
            // New: weighted recipe array. Optional with empty default so the
            // legacy single-recipe path below can kick in when this is absent.
            VEIN_RECIPES_CODEC.optionalFieldOf("vein_recipes", List.of()).forGetter(CoreFields::veinRecipes),
            // Legacy: single recipe. Folded into veinRecipes by merge() at decode
            // time; CoreFields.from always encodes it empty so output stays canonical.
            ResourceLocation.CODEC.optionalFieldOf("vein_recipe").forGetter(CoreFields::legacyVeinRecipe),
            ResourceLocation.CODEC.optionalFieldOf("vein_recipe_infinite").forGetter(CoreFields::veinRecipeInfinite),
            // New: filler pool. Empty default means "no filler chunks" (current behaviour).
            Codec.list(WeightedFiller.CODEC).optionalFieldOf("fillers", List.of()).forGetter(CoreFields::fillers),
            // New: replenish rate. 0 (default) = disabled, matching pre-0.2 behaviour.
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
            // When present, the BundledRecipePack virtual datapack synthesises the
            // corresponding COE recipe (vein → vein, drilling → drilling output items).
            VeinSpec.CODEC.optionalFieldOf("vein").forGetter(InlineFields::vein),
            DrillingSpec.CODEC.optionalFieldOf("drilling").forGetter(InlineFields::drilling),
            // Inline fluid spec → synthesises a createoreexcavation:extracting recipe.
            // Keyed "fluid" in deposits.json (the user-facing block name); distinct
            // from COE's own recipe-level "fluid" coolant field (different file).
            ExtractingSpec.CODEC.optionalFieldOf("fluid").forGetter(InlineFields::extracting)
    ).apply(b, InlineFields::new));

    /**
     * Codec used by {@link DepositTypeLoader} when parsing config JSON.
     *
     * <p>Backward-compatibility note: the legacy singular {@code vein_recipe}
     * field is accepted alongside the new {@code vein_recipes} array. If only
     * the legacy field is present, it's folded into a single-element list
     * with weight 1. If both are present, the new array wins and a warning
     * is logged. If neither is present AND no inline {@code vein:} block,
     * the type loads with an empty pool and won't spawn anything (admin
     * misconfig — log will show "no eligible type" for chunks the picker
     * tries to place).
     *
     * <p>The pre-0.1.2 {@code items} field is silently ignored — replaced by
     * {@code drilling.outputs}.
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

        /** Split a built type into core fields for encoding. The legacy field is
         *  decode-only, so it always encodes empty (canonical form writes vein_recipes). */
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
     * Not public because the record's canonical constructor is the intended factory
     * for direct callers.
     */
    private static DepositType merge(CoreFields c, InlineFields i) {
        // Fold legacy → new. Three cases:
        //   1. Only legacy present: synthesize a one-entry list with weight 1.
        //   2. Only new array present (or empty): use as-is.
        //   3. Both present: keep new array, log a warning so admin notices the
        //      duplicate config and removes the legacy field.
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
