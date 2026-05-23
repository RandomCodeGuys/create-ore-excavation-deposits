package uk.niknik.coedeposits;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.neoforged.neoforge.common.ModConfigSpec;

import com.mojang.serialization.Codec;

/**
 * Common-side mod configuration. Lives at
 * {@code <world>/serverconfig/coedeposits-common.toml} on dedicated servers,
 * or {@code run/config/coedeposits-common.toml} in dev. Values are read by
 * the picker, placer, prospect scanner and renderer.
 *
 * <p>NeoForge does NOT overwrite existing config files when defaults change in
 * code, so bumping a default here only affects worlds that lack the file. To
 * apply a new default to an existing world, delete or edit the toml manually.
 */
public class Config {
    /**
     * Per-deposit visibility on the world map.
     * <ul>
     *   <li>{@link #ALWAYS} — visible to every player immediately after placement;
     *       chat notification on placement.</li>
     *   <li>{@link #ON_DISCOVERY} — hidden until the player physically enters
     *       any chunk belonging to the deposit (per-player); chat at that
     *       moment for that player only.</li>
     *   <li>{@link #ON_PROXIMITY} — visible only while the player is within
     *       {@link Config#PROXIMITY_REVEAL_BLOCKS} of any of the deposit's
     *       chunks. No persistent per-player state; pure client-side filter.</li>
     *   <li>{@link #ON_PROSPECT} — hidden until the player uses a COE Vein
     *       Finder that resolves to this deposit (per-player); chat at that
     *       moment for that player only.</li>
     * </ul>
     * Values can be overridden per deposit type via the {@code reveal} field
     * in {@code config/coedeposits/deposits.json}.
     */
    public enum RevealMode implements StringRepresentable {
        ALWAYS, ON_DISCOVERY, ON_PROXIMITY, ON_PROSPECT;

        public static final Codec<RevealMode> CODEC = StringRepresentable.fromEnum(RevealMode::values);

        /** Convenience for callers using the value as a per-player gate key. */
        public boolean isPerPlayer() {
            return this == ON_DISCOVERY || this == ON_PROSPECT;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Distance gradient base — at d=base, tier is roughly 0.5 of the way to max. */
    public static final ModConfigSpec.DoubleValue BASE_RADIUS = BUILDER
            .comment("Base radius (blocks) for the distance-gradient log curve.")
            .defineInRange("base_radius", 1000.0, 1.0, 1_000_000.0);

    /** Distance at which tier saturates to ~1.0. Beyond this point, far-side bonuses plateau. */
    public static final ModConfigSpec.DoubleValue MAX_RADIUS = BUILDER
            .comment("Saturation radius (blocks). At and beyond this distance tier ~= 1.0.")
            .defineInRange("max_radius", 25_000.0, 1.0, 10_000_000.0);

    /**
     * Roll per loaded chunk — if {@code rng.nextFloat() &lt; this}, the chunk
     * tries to become a deposit core. Combined with biome/distance filters,
     * not every roll produces a deposit.
     *
     * <p>Default 0.0005 ≈ 1 candidate-core per 2000 chunks. With the typical
     * blob size (4–30 chunks) and biome filtering, this lands ~1 placed
     * deposit per 5–10k chunks, which leaves room for exploration without
     * tripping over an ore vein every few minutes.
     */
    public static final ModConfigSpec.DoubleValue CORE_SPAWN_PROBABILITY = BUILDER
            .comment("Per-chunk probability of triggering a new deposit core candidate.",
                    "Default 0.0005 — 1 candidate roll per ~2000 chunks. After biome + distance",
                    "filters reject most candidates and the surviving ones expand into 4-30-chunk",
                    "blobs, expect roughly:",
                    "  • 1 placed deposit per ~5,000-10,000 explored chunks",
                    "  • ~1 chunk in ~300-500 ends up belonging to some deposit",
                    "Bump up to 0.005-0.05 for denser maps; drop to 0.0001 for true-rarity worlds.")
            .defineInRange("core_spawn_probability", 0.0005, 0.0, 1.0);

    /**
     * Per-deposit gradient floor. Core chunk gets 100% of its rolled amountMul,
     * the most-distant chunk in the deposit gets {@code edge_amount_mul × core}.
     * Intermediate chunks are linearly interpolated by Chebyshev distance.
     */
    public static final ModConfigSpec.DoubleValue EDGE_AMOUNT_MUL = BUILDER
            .comment("Per-chunk gradient — core uses the deposit's full amountMul, ",
                    "outer chunks lerp down to (amountMul × edge_amount_mul). Default 0.3 ",
                    "means edges have 30% of the core's richness.")
            .defineInRange("edge_amount_mul", 0.3, 0.0, 1.0);

    /**
     * On {@code ServerStartedEvent} {@link uk.niknik.coedeposits.gen.ProspectScanner}
     * dry-runs the picker on every chunk in this radius around spawn and
     * persists detected deposits to SavedData. Players see them on the world
     * map immediately without walking.
     */
    public static final ModConfigSpec.IntValue PROSPECT_RADIUS = BUILDER
            .comment("Block radius around spawn pre-scanned for deposits on server start. ",
                    "All deposits in this radius are materialized in SavedData immediately so ",
                    "they show on the world map without players needing to walk there. ",
                    "Set to 0 to disable (deposits only appear after chunk-load). ",
                    "Cost ≈ (radius/16)² tryPick calls per start, ~10µs each. ",
                    "Upper bound capped at 16000 (≈1M chunks, ~10s) to keep server start responsive. ",
                    "Default 2000 (≈62k chunks, sub-second).")
            .defineInRange("prospect_radius", 2000, 0, 16000);

    /**
     * Growth coefficient used by {@link uk.niknik.coedeposits.deposit.DepositType.PerChunkUnits#computeTarget}
     * when a deposit_type's {@code per_chunk_units.max} is absent — yields
     * {@code min × (1 + tier × unbounded_growth)} so far-away deposits scale
     * open-endedly with distance.
     */
    public static final ModConfigSpec.DoubleValue UNBOUNDED_GROWTH = BUILDER
            .comment("Growth coefficient when a deposit_type's per_chunk_units.max is absent. ",
                    "Formula: target = min × (1 + tier × unbounded_growth). At tier=1 this ",
                    "yields (1 + unbounded_growth) × min. Default 50 means tier=1 deposits ",
                    "have 51× the min units of tier=0. Crank up for richer far-away veins.")
            .defineInRange("unbounded_growth", 50.0, 0.0, 100000.0);

    /**
     * Global default reveal mode applied when a deposit_type doesn't set
     * {@code reveal} explicitly. See {@link RevealMode} for semantics.
     */
    public static final ModConfigSpec.EnumValue<RevealMode> REVEAL_MODE = BUILDER
            .comment("Default reveal policy when a deposit_type doesn't override `reveal`.",
                    "  ALWAYS       — visible to everyone immediately + chat notification.",
                    "  ON_DISCOVERY — hidden until the player walks into the deposit; per-player.",
                    "  ON_PROXIMITY — visible only while player is within proximity_reveal_blocks.",
                    "  ON_PROSPECT  — hidden until the player uses a Vein Finder that resolves to it.")
            .defineEnum("reveal_mode", RevealMode.ALWAYS);

    /**
     * Block-radius for {@link RevealMode#ON_PROXIMITY}. Client-side filter
     * inside {@link uk.niknik.coedeposits.client.WorldMapDepositRenderer}.
     * Computed against the nearest chunk of the deposit (centre block).
     */
    public static final ModConfigSpec.IntValue PROXIMITY_REVEAL_BLOCKS = BUILDER
            .comment("Block-radius for ON_PROXIMITY reveal mode. Default 256 blocks ≈ 16 chunks.",
                    "Deposit becomes visible on the world map when the player is within this ",
                    "distance of any of its chunks. Has no effect on other reveal modes.")
            .defineInRange("proximity_reveal_blocks", 256, 16, 100000);

    /**
     * Maximum block-distance from a player at which an ON_DISCOVERY-mode
     * deposit will trigger its own discovery on that player. Should be small
     * — the intent of ON_DISCOVERY is "you have to walk to it". Used by
     * {@link uk.niknik.coedeposits.event.PlayerRoamProspectListener}.
     */
    public static final ModConfigSpec.IntValue DISCOVERY_RADIUS_BLOCKS = BUILDER
            .comment("Block-radius around the player checked for ON_DISCOVERY reveals on tick.",
                    "Default 24 blocks ≈ 1.5 chunks — roughly 'stand on top of a chunk of it'. ",
                    "Has no effect on other reveal modes.")
            .defineInRange("discovery_radius_blocks", 24, 8, 1024);

    /**
     * Dimensions in which the mod is active. In every listed dimension the
     * picker runs managed/COE placement, {@link uk.niknik.coedeposits.gen.ProspectScanner}
     * sweeps on server start, and {@link uk.niknik.coedeposits.event.DepositDepletionListener}
     * / {@link uk.niknik.coedeposits.event.PlayerRoamProspectListener} keep state in sync.
     *
     * <p>Dimensions <i>not</i> in the list see purely vanilla behaviour —
     * {@link uk.niknik.coedeposits.gen.CoedepositsPicker#pick} early-returns
     * {@code null}, leaving COE's own picker logic (and any third-party
     * VeinRecipes) untouched.
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ENABLED_DIMENSIONS = BUILDER
            .comment("Dimensions where coedeposits is active.",
                    "Format: list of namespaced dimension ids. Default: vanilla overworld + nether + end.",
                    "Each enabled dimension keeps its own DepositSavedData and runs its own prospect scan.",
                    "Per-type targeting is controlled by the `dimensions` field in deposits.json.",
                    "Dimensions not in this list are left to vanilla COE (no managed generation, no map tracking).")
            .defineList("enabled_dimensions",
                    List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                    () -> "minecraft:overworld",
                    o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

    /** Built once at class-init, registered with the mod container at startup. */
    static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * Cached parsed view of {@link #ENABLED_DIMENSIONS}. Lazily rebuilt when
     * the underlying list changes (size mismatch triggers rebuild — config
     * objects are immutable after each reload so identity-equality on the
     * source list works as a cheap dirty check).
     */
    private static volatile Set<ResourceLocation> enabledDimensionsCache = null;
    private static volatile List<? extends String> enabledDimensionsCacheSource = null;

    /** Parsed enabled dimensions, cached until the config list reference changes. */
    public static Set<ResourceLocation> enabledDimensions() {
        List<? extends String> source = ENABLED_DIMENSIONS.get();
        Set<ResourceLocation> cached = enabledDimensionsCache;
        if (cached != null && enabledDimensionsCacheSource == source) return cached;
        Set<ResourceLocation> rebuilt = new HashSet<>();
        for (String s : source) {
            ResourceLocation rl = ResourceLocation.tryParse(s);
            if (rl != null) rebuilt.add(rl);
        }
        enabledDimensionsCache = rebuilt;
        enabledDimensionsCacheSource = source;
        return rebuilt;
    }

    /** Convenience: is this dimension key in the configured allowlist? */
    public static boolean isDimensionEnabled(ResourceLocation dim) {
        return enabledDimensions().contains(dim);
    }
}
