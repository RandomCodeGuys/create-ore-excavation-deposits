package uk.niknik.coedeposits.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.deposit.DepositTypeLoader;

/**
 * Pure-logic deposit placement decisions. Stateless — given the same inputs
 * (world seed + chunk pos + registry + config) it always returns the same
 * result. Used by {@link CoedepositsPicker} on chunk-load, by
 * {@link ProspectScanner} on server start, and by /coedeposits commands for
 * forced placement.
 *
 * <p>This class deliberately does not look up vein recipes — that's the
 * caller's job. {@link Result#amountMul()} is always zero here; callers
 * compute the real {@code randomMul} via {@link #amountMulForTarget} after
 * picking up the chosen type.
 */
public final class DepositPlacer {
    private DepositPlacer() {}

    /**
     * Outcome of a successful pick.
     *
     * @param typeId        chosen deposit type id
     * @param type          the {@link DepositType} instance (avoids re-lookup)
     * @param chunks        Perlin-blob chunk set covering the deposit
     * @param amountMul     placeholder field, always {@code 0f} from this class —
     *                       callers fill it in after recipe resolution
     * @param tierFraction  distance gradient tier in [0,1]
     */
    public record Result(
            ResourceLocation typeId,
            DepositType type,
            Set<ChunkPos> chunks,
            float amountMul,
            float tierFraction) {}

    /**
     * Force-pick a specific type at the given chunk, using tier-based natural
     * size from the type's {@code size_chunks} range. Used by
     * {@code /coedeposits place <type>}.
     */
    public static Result forceType(
            ChunkPos chunk,
            BlockPos spawn,
            int playerY,
            long worldSeed,
            ResourceLocation typeId,
            DepositType type,
            float baseRadius,
            float maxRadius) {
        // ── Step 1: compute the distance tier at the target chunk ───────────
        // Use the player's Y so the chunk centre is at the same elevation as
        // the command issuer — keeps the tier deterministic per-position when
        // the player re-runs the command from the same spot.
        BlockPos center = new BlockPos(
                chunk.getMiddleBlockX(), playerY, chunk.getMiddleBlockZ());
        float tier = DistanceGradient.tierFraction(center, spawn, baseRadius, maxRadius);

        // ── Step 2: pick the blob size from the type's range ────────────────
        // Same lerp the natural picker uses; Math.max(1) protects against
        // pathological configs where size_chunks.min < 1 would compute 0.
        int sizeChunks = Math.max(1, Math.round(DistanceGradient.lerp(
                type.sizeChunks().min(), type.sizeChunks().max(), tier)));

        // ── Step 3: delegate to forceWith for the actual Perlin expansion ───
        return forceWith(chunk, worldSeed, typeId, type, tier, sizeChunks);
    }

    /**
     * Force-pick with explicit chunk count and tier. Used by
     * {@code /coedeposits place <type> <pos> <amount> <chunks>} and by
     * commands that need to override the natural size.
     */
    public static Result forceWith(
            ChunkPos chunk,
            long worldSeed,
            ResourceLocation typeId,
            DepositType type,
            float tier,
            int sizeChunks) {
        // ── Step 1: derive the shape seed deterministically ─────────────────
        // Same setLargeFeatureSeed pattern as tryPick — guarantees that a
        // /coedeposits place at chunk (X,Z) produces the same blob shape every
        // time, even across server restarts. Lets admins copy a layout
        // between worlds with the same world seed.
        WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
        rng.setLargeFeatureSeed(worldSeed, chunk.x, chunk.z);
        long shapeSeed = rng.nextLong();

        // ── Step 2: generate the blob, return result ────────────────────────
        // amountMul=0 here — the caller (CoedepositsCommand.doPlace) computes
        // the real value after recipe lookup; this class is recipe-agnostic.
        Set<ChunkPos> chunks = PerlinShape.generate(chunk, sizeChunks, shapeSeed);
        return new Result(typeId, type, chunks, 0f, tier);
    }

    /**
     * Translate a target per-chunk unit count into the {@code randomMul} value
     * that COE's formula needs to produce that many units, given the chosen
     * vein recipe. The conversion is the inverse of:
     * <pre>units = ((max - min) × randomMul + min) × finiteAmountBase</pre>
     * Floored at 0 (no negative units). Upper bound intentionally absent —
     * unbounded deposits pass arbitrarily large {@code randomMul} values.
     *
     * @param targetUnits  desired per-chunk units (from deposit_type budget)
     * @param recipeMin    COE recipe's {@code amountMultiplierMin}
     * @param recipeMax    COE recipe's {@code amountMultiplierMax}
     * @param finiteBase   COE global {@code Config.finiteAmountBase} (default 1000)
     * @return             {@code randomMul} to write into {@code OreData}
     */

    /**
     * Per-chunk deterministic weighted pick from a type's combined
     * {@code recipes + fillers} pool. Returns the chosen vein recipe id, or
     * {@link Optional#empty()} when the roll lands on a filler entry (or the
     * type has an empty pool — misconfig).
     *
     * <p>Deterministic on {@code (depositSeed, chunkX, chunkZ)} via the
     * {@link WorldgenRandom#setLargeFeatureSeed} primitive, salted with a
     * fixed constant so the recipe roll doesn't correlate with the shape
     * seed or core-spawn roll done by {@link #tryPick} on the same inputs.
     * Every callsite that needs "which recipe does this chunk of this
     * deposit have?" should go through this method.
     *
     * @param type         the deposit type — pool is {@link DepositType#veinRecipes}
     *                     and {@link DepositType#fillers} combined
     * @param depositSeed  per-dimension deposit seed from
     *                     {@link uk.niknik.coedeposits.store.DepositSavedData#effectiveSeed}
     * @param cp           chunk being evaluated
     * @return             chosen recipe id (ore chunk) or empty (filler chunk)
     */
    public static Optional<ResourceLocation> rollChunkRecipe(DepositType type, long depositSeed, ChunkPos cp) {
        int total = type.totalChunkPoolWeight();
        if (total <= 0) return Optional.empty();

        // Fixed salt decorrelates this roll from the shape RNG and the
        // core-spawn-probability roll in tryPick, both of which feed the
        // same (worldSeed, chunkX, chunkZ) triple into setLargeFeatureSeed.
        // Without the salt the per-chunk recipe would correlate with whether
        // the chunk was eligible to be a core, which would cluster recipes.
        WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
        rng.setLargeFeatureSeed(depositSeed ^ 0xC0EDE057A17EAL, cp.x, cp.z);
        int roll = rng.nextInt(total);
        int acc = 0;
        // Walk recipes first; if the roll lands here we return the recipe.
        for (DepositType.WeightedRecipe wr : type.veinRecipes()) {
            acc += wr.weight();
            if (roll < acc) return Optional.of(wr.recipe());
        }
        // Fell past all recipes — landed in the filler slice of the pool.
        return Optional.empty();
    }

    public static float amountMulForTarget(double targetUnits, float recipeMin, float recipeMax, int finiteBase) {
        // Algebraic inverse of COE's amount formula:
        //   units = ((recipeMax - recipeMin) × randomMul + recipeMin) × finiteBase
        // Solving for randomMul:
        //   randomMul = (units/finiteBase - recipeMin) / (recipeMax - recipeMin)
        double perChunkRecipe = targetUnits / finiteBase;
        double computed = (perChunkRecipe - recipeMin) / (recipeMax - recipeMin);
        // Floor at 0 — recipeMin alone already provides minimum-units floor,
        // and feeding COE a negative randomMul would compute negative units
        // which break the depletion arithmetic. No upper clamp: unbounded
        // deposits intentionally feed very large randomMul values.
        return (float) Math.max(0.0, computed);
    }

    /**
     * Decide whether this chunk should spawn a new deposit core, and if so,
     * which type. Deterministic for the same {@code (worldSeed, chunk)} input.
     *
     * @param chunk                  chunk being evaluated
     * @param spawn                  world spawn for tier/distance computation
     * @param worldSeed              world seed for deterministic RNG
     * @param registry               loaded {@link DepositType} registry
     * @param baseRadius             {@link uk.niknik.coedeposits.Config#BASE_RADIUS}
     * @param maxRadius              {@link uk.niknik.coedeposits.Config#MAX_RADIUS}
     * @param coreSpawnProbability   {@link uk.niknik.coedeposits.Config#CORE_SPAWN_PROBABILITY}
     * @param biome                  chunk centre biome holder for biome-filter
     *                               matching (may be {@code null} to skip filter)
     * @param dimension              current dimension id; used to filter types
     *                               whose {@code dimensions} field excludes
     *                               this dimension (may be {@code null} to
     *                               skip dimension filtering)
     * @return                       placement result or {@code null} if the
     *                               chunk did not roll a core or no type was
     *                               eligible (biome/distance/dimension filtered out)
     */
    public static Result tryPick(
            ChunkPos chunk,
            BlockPos spawn,
            long worldSeed,
            DepositTypeLoader registry,
            float baseRadius,
            float maxRadius,
            float coreSpawnProbability,
            Holder<Biome> biome,
            ResourceLocation dimension) {

        // ── Phase 1: deterministic RNG ──────────────────────────────────────
        // Proper avalanche of (worldSeed, chunkX, chunkZ) — naive XOR with the
        // packed chunk coords correlates neighbouring Z values under LegacyRandom
        // and causes vertical streaks of placement. setLargeFeatureSeed is the
        // same primitive vanilla MC uses for feature placement.
        WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
        rng.setLargeFeatureSeed(worldSeed, chunk.x, chunk.z);
        // Reserve one long for the shape RNG up front. We always pull it (even
        // when the chunk loses the probability gate) so the shape RNG isn't
        // sensitive to whether core_spawn_probability changes between runs.
        long shapeSeed = rng.nextLong();

        // ── Phase 2: probability gate ───────────────────────────────────────
        // Single per-chunk roll. Vast majority of chunks fall out here, which
        // is why the whole picker is cheap enough to run on every chunk-load
        // and on the prospect scanner's grid.
        if (rng.nextFloat() >= coreSpawnProbability) return null;

        // ── Phase 3: compute distance + tier ────────────────────────────────
        // Centre coordinates use spawn Y so the gradient is purely horizontal —
        // mountainous spawns don't artificially inflate distance.
        BlockPos center = new BlockPos(
                chunk.getMiddleBlockX(), spawn.getY(), chunk.getMiddleBlockZ());
        double distFromSpawn = DistanceGradient.distance(center, spawn);
        float tier = DistanceGradient.tierFraction(center, spawn, baseRadius, maxRadius);

        // ── Phase 4: filter the registry to eligible types ──────────────────
        // Walk every loaded DepositType and keep only the ones whose
        // placement / dimension / distance / biome constraints all match this
        // chunk. Reject counters feed the debug-log fallthrough below so a
        // user staring at an empty map can diagnose what filter killed them.
        List<Map.Entry<ResourceLocation, DepositType>> eligible = new ArrayList<>();
        int totalWeight = 0;
        int rejectedDistance = 0, rejectedBiome = 0, rejectedPlacement = 0, rejectedDimension = 0;
        for (var e : registry.all().entrySet()) {
            DepositType t = e.getValue();
            // Filter 1: placement mode. COE-placement types don't go through
            // the blob algorithm — their chunks are chosen by COE's
            // RandomSpreadGenerator and tracked separately by
            // CoedepositsPicker's super.pick() delegation branch.
            if (t.placement() != DepositType.Placement.MANAGED) {
                rejectedPlacement++;
                continue;
            }
            // Filter 2: per-type dimension allow-list. Empty list == any.
            if (dimension != null && !t.matchesDimension(dimension)) {
                rejectedDimension++;
                continue;
            }
            // Filter 3: distance window. Min/max bounds in blocks from spawn.
            if (distFromSpawn < t.distance().min() || distFromSpawn > t.distance().max()) {
                rejectedDistance++;
                continue;
            }
            // Filter 4: biome tag OR-match. Empty biome filter means any biome.
            // Null biome arg disables this filter entirely (callers pass null
            // when they can't cheaply sample the biome).
            if (biome != null && !t.biomeFilter().isEmpty()) {
                boolean any = false;
                for (var tag : t.biomeFilter()) {
                    if (biome.is(tag)) { any = true; break; }
                }
                if (!any) { rejectedBiome++; continue; }
            }
            eligible.add(e);
            totalWeight += t.weight();
        }
        if (eligible.isEmpty() || totalWeight <= 0) {
            // This chunk WAS a candidate (passed core_spawn_probability) but no
            // deposit type matched. Useful to spot biome/distance misconfiguration —
            // flip `log_scan_rejections` on in coedeposits-common.toml to see these.
            if (uk.niknik.coedeposits.Config.LOG_SCAN_REJECTIONS.get()) {
                uk.niknik.coedeposits.Coedeposits.LOGGER.info(
                        "[coedeposits] chunk {},{} candidate but no eligible type (registry={}, placementRejected={}, dimensionRejected={}, distRejected={}, biomeRejected={}, dist={}, biome={}, dim={})",
                        chunk.x, chunk.z, registry.all().size(), rejectedPlacement, rejectedDimension, rejectedDistance, rejectedBiome,
                        (int) distFromSpawn, biome != null ? biome.unwrapKey().map(k -> k.location().toString()).orElse("?") : "null",
                        dimension);
            }
            return null;
        }

        // ── Phase 5: weighted random pick among eligible types ──────────────
        // Standard weighted-prefix-sum trick: roll in [0, totalWeight), walk
        // the list accumulating weight, take the first entry whose running
        // sum exceeds the roll. Stable order across runs because the registry
        // map is built from a JSON file with deterministic iteration.
        int roll = rng.nextInt(totalWeight);
        Map.Entry<ResourceLocation, DepositType> chosen = null;
        int acc = 0;
        for (var e : eligible) {
            acc += e.getValue().weight();
            if (roll < acc) { chosen = e; break; }
        }
        if (chosen == null) return null;  // defensive — should be unreachable

        // ── Phase 6: size the blob from the type's size_chunks range ────────
        // Lerp by tier so far-from-spawn picks get bigger blobs. Math.max(1)
        // protects against pathological configs where size_chunks.min < 1.
        DepositType type = chosen.getValue();
        int sizeChunks = Math.round(DistanceGradient.lerp(
                type.sizeChunks().min(), type.sizeChunks().max(), tier));
        sizeChunks = Math.max(1, sizeChunks);

        // ── Phase 7: expand the blob via Perlin shape ───────────────────────
        Set<ChunkPos> chunks = PerlinShape.generate(chunk, sizeChunks, shapeSeed);

        // Result holds the placement decision (type/chunks/tier). amountMul
        // (= randomMul for COE) stays 0 here because it needs a recipe lookup;
        // see amountMulForTarget for the conversion, called from the caller.
        return new Result(chosen.getKey(), type, chunks, 0f, tier);
    }
}
