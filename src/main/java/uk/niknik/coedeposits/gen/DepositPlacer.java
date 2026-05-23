package uk.niknik.coedeposits.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        BlockPos center = new BlockPos(
                chunk.getMiddleBlockX(), playerY, chunk.getMiddleBlockZ());
        float tier = DistanceGradient.tierFraction(center, spawn, baseRadius, maxRadius);
        int sizeChunks = Math.max(1, Math.round(DistanceGradient.lerp(
                type.sizeChunks().min(), type.sizeChunks().max(), tier)));
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
        WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
        rng.setLargeFeatureSeed(worldSeed, chunk.x, chunk.z);
        long shapeSeed = rng.nextLong();
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
    public static float amountMulForTarget(double targetUnits, float recipeMin, float recipeMax, int finiteBase) {
        double perChunkRecipe = targetUnits / finiteBase;
        double computed = (perChunkRecipe - recipeMin) / (recipeMax - recipeMin);
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

        // Proper avalanche of (worldSeed, chunkX, chunkZ) — naive XOR with the
        // packed chunk coords correlates neighbouring Z values under LegacyRandom
        // and causes vertical streaks of placement. setLargeFeatureSeed is the
        // same primitive vanilla MC uses for feature placement.
        WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
        rng.setLargeFeatureSeed(worldSeed, chunk.x, chunk.z);
        long shapeSeed = rng.nextLong();

        if (rng.nextFloat() >= coreSpawnProbability) return null;

        BlockPos center = new BlockPos(
                chunk.getMiddleBlockX(), spawn.getY(), chunk.getMiddleBlockZ());
        double distFromSpawn = DistanceGradient.distance(center, spawn);
        float tier = DistanceGradient.tierFraction(center, spawn, baseRadius, maxRadius);

        List<Map.Entry<ResourceLocation, DepositType>> eligible = new ArrayList<>();
        int totalWeight = 0;
        int rejectedDistance = 0, rejectedBiome = 0, rejectedPlacement = 0, rejectedDimension = 0;
        for (var e : registry.all().entrySet()) {
            DepositType t = e.getValue();
            // Skip COE-placement types — they don't go through the blob algorithm;
            // their chunks are chosen by COE's RandomSpreadGenerator and tracked
            // separately by CoedepositsPicker's super.pick() delegation branch.
            if (t.placement() != DepositType.Placement.MANAGED) {
                rejectedPlacement++;
                continue;
            }
            // Per-type dimension allow-list. Empty list == any dimension.
            if (dimension != null && !t.matchesDimension(dimension)) {
                rejectedDimension++;
                continue;
            }
            if (distFromSpawn < t.distance().min() || distFromSpawn > t.distance().max()) {
                rejectedDistance++;
                continue;
            }
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
            // deposit type matched. Useful to spot biome/distance misconfiguration.
            uk.niknik.coedeposits.Coedeposits.LOGGER.debug(
                    "[coedeposits] chunk {},{} candidate but no eligible type (registry={}, placementRejected={}, dimensionRejected={}, distRejected={}, biomeRejected={}, dist={}, biome={}, dim={})",
                    chunk.x, chunk.z, registry.all().size(), rejectedPlacement, rejectedDimension, rejectedDistance, rejectedBiome,
                    (int) distFromSpawn, biome != null ? biome.unwrapKey().map(k -> k.location().toString()).orElse("?") : "null",
                    dimension);
            return null;
        }

        int roll = rng.nextInt(totalWeight);
        Map.Entry<ResourceLocation, DepositType> chosen = null;
        int acc = 0;
        for (var e : eligible) {
            acc += e.getValue().weight();
            if (roll < acc) { chosen = e; break; }
        }
        if (chosen == null) return null;

        DepositType type = chosen.getValue();
        int sizeChunks = Math.round(DistanceGradient.lerp(
                type.sizeChunks().min(), type.sizeChunks().max(), tier));
        sizeChunks = Math.max(1, sizeChunks);

        Set<ChunkPos> chunks = PerlinShape.generate(chunk, sizeChunks, shapeSeed);

        // Result holds the placement decision (type/chunks/tier). amountMul (=
        // randomMul for COE) is computed by the caller because it needs recipe
        // lookup (different responsibility, separable for testing).
        return new Result(chosen.getKey(), type, chunks, 0f, tier);
    }
}
