package uk.niknik.coedeposits;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraftforge.common.ForgeConfigSpec;

import com.mojang.serialization.Codec;

/**
 * Common-side mod configuration — Forge 1.20.1 line. Lives at
 * {@code <world>/serverconfig/coedeposits-common.toml} on dedicated servers,
 * or {@code run/config/coedeposits-common.toml} in dev. Values are read by
 * the picker, placer, prospect scanner and renderer.
 *
 * <p>In {@code platform-shared}: written against Forge's {@link ForgeConfigSpec},
 * which <b>Forge Config API Port</b> exposes verbatim on the Fabric module, so the
 * same file serves both loaders (NeoForge 1.21.1 forked the identical spec API as
 * {@code ModConfigSpec}). Only the loader-specific {@code registerConfig(...)} call
 * differs and lives in each loader's mod entry, not here.
 *
 * <p>Forge does NOT overwrite existing config files when defaults change in code,
 * so bumping a default here only affects worlds that lack the file.
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

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ═══════════════════════════════════════════════════════════════════════
    // Distance gradient — drives how tier scales with distance from spawn.
    // Tier in turn drives deposit size, per-chunk units, and type eligibility.
    // ═══════════════════════════════════════════════════════════════════════

    /** Distance gradient base — at d=base, tier is roughly 0.5 of the way to max. */
    public static final ForgeConfigSpec.DoubleValue BASE_RADIUS = BUILDER
            .comment("Base radius (blocks) for the distance-gradient log curve.")
            .defineInRange("base_radius", 1000.0, 1.0, 1_000_000.0);

    /** Distance at which tier saturates to ~1.0. Beyond this point, far-side bonuses plateau. */
    public static final ForgeConfigSpec.DoubleValue MAX_RADIUS = BUILDER
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
    public static final ForgeConfigSpec.DoubleValue CORE_SPAWN_PROBABILITY = BUILDER
            .comment("Per-chunk probability of triggering a new deposit core candidate.",
                    "Default 0.0005 — 1 candidate roll per ~2000 chunks. After biome + distance",
                    "filters reject most candidates and the surviving ones expand into 4-30-chunk",
                    "blobs, expect roughly:",
                    "  • 1 placed deposit per ~5,000-10,000 explored chunks",
                    "  • ~1 chunk in ~300-500 ends up belonging to some deposit",
                    "Bump up to 0.005-0.05 for denser maps; drop to 0.0001 for true-rarity worlds.")
            .defineInRange("core_spawn_probability", 0.0005, 0.0, 1.0);

    // ═══════════════════════════════════════════════════════════════════════
    // Per-deposit shape — how rich the core is vs the edges of a blob.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Per-deposit gradient floor. Core chunk gets 100% of its rolled amountMul,
     * the most-distant chunk in the deposit gets {@code edge_amount_mul × core}.
     * Intermediate chunks are linearly interpolated by Chebyshev distance.
     */
    public static final ForgeConfigSpec.DoubleValue EDGE_AMOUNT_MUL = BUILDER
            .comment("Per-chunk gradient — core uses the deposit's full amountMul, ",
                    "outer chunks lerp down to (amountMul × edge_amount_mul). Default 0.3 ",
                    "means edges have 30% of the core's richness.")
            .defineInRange("edge_amount_mul", 0.3, 0.0, 1.0);

    // ═══════════════════════════════════════════════════════════════════════
    // Prospect scan — pre-discovers deposits so the world map fills in
    // ahead of player exploration. Async since 0.1.2 (ProspectScanQueue).
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * On {@code ServerStartedEvent} {@link uk.niknik.coedeposits.gen.ProspectScanner}
     * dry-runs the picker on every chunk in this radius around spawn and
     * persists detected deposits to SavedData. Players see them on the world
     * map immediately without walking.
     */
    public static final ForgeConfigSpec.IntValue PROSPECT_RADIUS = BUILDER
            .comment("Block radius around spawn pre-scanned for deposits on server start. ",
                    "All deposits in this radius are materialized in SavedData immediately so ",
                    "they show on the world map without players needing to walk there. ",
                    "Set to 0 to disable (deposits only appear after chunk-load). ",
                    "Cost ≈ (radius/16)² tryPick calls per start, ~10µs each. ",
                    "Upper bound capped at 16000 (≈1M chunks, ~10s) to keep server start responsive. ",
                    "Default 2000 (≈62k chunks, sub-second).")
            .defineInRange("prospect_radius", 2000, 0, 16000);

    /**
     * How many pending deposit placements the {@link uk.niknik.coedeposits.gen.ProspectScanQueue}
     * may materialize on the server thread per tick.
     */
    public static final ForgeConfigSpec.IntValue PROSPECT_CHUNKS_PER_TICK = BUILDER
            .comment("Maximum number of pending deposit placements materialized on the server ",
                    "thread per tick. The dry-run pick runs off-thread; this only throttles the ",
                    "hand-off (SavedData.add + OreData apply on loaded blob chunks). Raise to drain ",
                    "the queue faster after a large /coedeposits regenerate or fresh-world prospect; ",
                    "lower if you see tick spikes on weak CPUs. Default 256 ≈ ~3ms/tick worst case.")
            .defineInRange("prospect_chunks_per_tick", 256, 16, 4096);

    /**
     * Growth coefficient used by {@link uk.niknik.coedeposits.deposit.DepositType.PerChunkUnits#computeTarget}
     * when a deposit_type's {@code per_chunk_units.max} is absent — yields
     * {@code min × (1 + tier × unbounded_growth)} so far-away deposits scale
     * open-endedly with distance.
     */
    public static final ForgeConfigSpec.DoubleValue UNBOUNDED_GROWTH = BUILDER
            .comment("Growth coefficient when a deposit_type's per_chunk_units.max is absent. ",
                    "Formula: target = min × (1 + tier × unbounded_growth). At tier=1 this ",
                    "yields (1 + unbounded_growth) × min. Default 50 means tier=1 deposits ",
                    "have 51× the min units of tier=0. Crank up for richer far-away veins.")
            .defineInRange("unbounded_growth", 50.0, 0.0, 100000.0);

    // ═══════════════════════════════════════════════════════════════════════
    // Reveal modes — visibility policy per deposit type.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Global default reveal mode applied when a deposit_type doesn't set
     * {@code reveal} explicitly. See {@link RevealMode} for semantics.
     */
    public static final ForgeConfigSpec.EnumValue<RevealMode> REVEAL_MODE = BUILDER
            .comment("Default reveal policy when a deposit_type doesn't override `reveal`.",
                    "  ALWAYS       — visible to everyone immediately + chat notification.",
                    "  ON_DISCOVERY — hidden until the player walks into the deposit; per-player.",
                    "  ON_PROXIMITY — visible only while player is within proximity_reveal_blocks.",
                    "  ON_PROSPECT  — hidden until the player uses a Vein Finder that resolves to it.")
            .defineEnum("reveal_mode", RevealMode.ALWAYS);

    /**
     * Block-radius for {@link RevealMode#ON_PROXIMITY}. Client-side filter
     * inside {@link uk.niknik.coedeposits.client.WorldMapDepositRenderer}.
     */
    public static final ForgeConfigSpec.IntValue PROXIMITY_REVEAL_BLOCKS = BUILDER
            .comment("Block-radius for ON_PROXIMITY reveal mode. Default 256 blocks ≈ 16 chunks.",
                    "Deposit becomes visible on the world map when the player is within this ",
                    "distance of any of its chunks. Has no effect on other reveal modes.")
            .defineInRange("proximity_reveal_blocks", 256, 16, 100000);

    /**
     * Maximum block-distance from a player at which an ON_DISCOVERY-mode
     * deposit will trigger its own discovery on that player.
     */
    public static final ForgeConfigSpec.IntValue DISCOVERY_RADIUS_BLOCKS = BUILDER
            .comment("Block-radius around the player checked for ON_DISCOVERY reveals on tick.",
                    "Default 24 blocks ≈ 1.5 chunks — roughly 'stand on top of a chunk of it'. ",
                    "Has no effect on other reveal modes.")
            .defineInRange("discovery_radius_blocks", 24, 8, 1024);

    // ═══════════════════════════════════════════════════════════════════════
    // Dimensions — which worlds run the picker.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Dimensions in which the mod is active. In every listed dimension the
     * picker runs managed/COE placement and the prospect scanner sweeps on
     * server start. Dimensions <i>not</i> in the list see purely vanilla COE
     * behaviour.
     *
     * <p><b>1.20.1 delta:</b> Forge's {@code defineList} takes
     * {@code (path, defaultValue, elementValidator)} — the {@code newElementSupplier}
     * argument NeoForge added for its config UI does not exist here.
     */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENABLED_DIMENSIONS = BUILDER
            .comment("Dimensions where coedeposits is active.",
                    "Format: list of namespaced dimension ids. Default: vanilla overworld + nether + end.",
                    "Each enabled dimension keeps its own DepositSavedData and runs its own prospect scan.",
                    "Per-type targeting is controlled by the `dimensions` field in deposits.json.",
                    "Dimensions not in this list are left to vanilla COE (no managed generation, no map tracking).")
            .defineList("enabled_dimensions",
                    List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                    o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

    // ═══════════════════════════════════════════════════════════════════════
    // Logging toggles — granular on/off for each log category.
    // ═══════════════════════════════════════════════════════════════════════

    /** Per-deposit placement events. */
    public static final ForgeConfigSpec.BooleanValue LOG_PLACEMENT = BUILDER
            .comment("Log when a new deposit is placed (managed blob or COE-tracked single chunk).",
                    "One line per placement. Useful for verifying density / type distribution.")
            .define("log_placement", true);

    /** Per-player discovery events (ON_DISCOVERY walk-into, ON_PROSPECT vein-finder). */
    public static final ForgeConfigSpec.BooleanValue LOG_DISCOVERY = BUILDER
            .comment("Log when a player triggers a reveal — ON_DISCOVERY walk-into or ON_PROSPECT",
                    "vein finder use. One line per reveal. Off if you want chat-only feedback.")
            .define("log_discovery", true);

    /** Chunk depletion. */
    public static final ForgeConfigSpec.BooleanValue LOG_DEPLETION = BUILDER
            .comment("Log when a chunk's vein is fully extracted and its OreData is wiped.",
                    "One line per chunk. Off can quiet very active server with many drills.")
            .define("log_depletion", true);

    /** Per-deposit replenish actions (verbose; default off). */
    public static final ForgeConfigSpec.BooleanValue LOG_REPLENISH_ACTIONS = BUILDER
            .comment("Log when the replenish sweep restores units on a chunk. One line per",
                    "(deposit, chunk) where units actually changed each tick — VERY noisy on",
                    "servers with many replenishing deposits. Default off; flip on briefly to",
                    "confirm replenish is firing for a specific deposit.")
            .define("log_replenish_actions", false);

    /** Prospect-scan summary. */
    public static final ForgeConfigSpec.BooleanValue LOG_SCAN_SUMMARY = BUILDER
            .comment("Log a one-line summary at the end of each prospect scan job.",
                    "Includes scan centre, radius, chunks scanned, placements queued, and elapsed",
                    "time. Useful for performance tuning prospect_radius.")
            .define("log_scan_summary", true);

    /** Per-chunk scan rejection diagnostics (very verbose; default off). */
    public static final ForgeConfigSpec.BooleanValue LOG_SCAN_REJECTIONS = BUILDER
            .comment("Log per-chunk diagnostics when a chunk passed the spawn-probability roll",
                    "but no eligible deposit type matched. Shows which filter (placement / dimension /",
                    "distance / biome) rejected each type. Default off.")
            .define("log_scan_rejections", false);

    /** Mod lifecycle events. */
    public static final ForgeConfigSpec.BooleanValue LOG_LIFECYCLE = BUILDER
            .comment("Log mod lifecycle events: picker installed on COE, deposit types loaded",
                    "(datapack + config-overlay counts), scan-queue shutdown drain on server stop.",
                    "Sparse — only a handful of lines per server cycle.")
            .define("log_lifecycle", true);

    /** Built once at class-init, registered with the mod container at startup. */
    public static final ForgeConfigSpec SPEC = BUILDER.build();

    /** Cached parsed view of {@link #ENABLED_DIMENSIONS}, rebuilt when the source list reference changes. */
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
