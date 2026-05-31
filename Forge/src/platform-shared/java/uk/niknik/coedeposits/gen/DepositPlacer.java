package uk.niknik.coedeposits.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.resources.ResourceLocation;

import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.deposit.DepositTypeLoader;

/**
 * Pure-logic deposit placement decisions. Stateless — same inputs always yield
 * the same result. Used by {@link CoedepositsPicker}, {@link ProspectScanner},
 * and /coedeposits commands.
 *
 * <p>Loader-agnostic (platform-shared); ports verbatim — only vanilla worldgen
 * RNG + {@code Holder<Biome>} + coedeposits internals, no COE / loader APIs.
 */
public final class DepositPlacer {
    private DepositPlacer() {}

    /**
     * Outcome of a successful pick.
     *
     * @param typeId        chosen deposit type id
     * @param type          the {@link DepositType} instance (avoids re-lookup)
     * @param chunks        Perlin-blob chunk set covering the deposit
     * @param amountMul     placeholder, always {@code 0f} here — callers fill it in
     * @param tierFraction  distance gradient tier in [0,1]
     */
    public record Result(
            ResourceLocation typeId,
            DepositType type,
            Set<ChunkPos> chunks,
            float amountMul,
            float tierFraction) {}

    /** Force-pick a specific type at a chunk, sizing from the type's {@code size_chunks} range. */
    public static Result forceType(
            ChunkPos chunk, BlockPos spawn, int playerY, long worldSeed,
            ResourceLocation typeId, DepositType type, float baseRadius, float maxRadius) {
        BlockPos center = new BlockPos(chunk.getMiddleBlockX(), playerY, chunk.getMiddleBlockZ());
        float tier = DistanceGradient.tierFraction(center, spawn, baseRadius, maxRadius);
        int sizeChunks = Math.max(1, Math.round(DistanceGradient.lerp(
                type.sizeChunks().min(), type.sizeChunks().max(), tier)));
        return forceWith(chunk, worldSeed, typeId, type, tier, sizeChunks);
    }

    /** Force-pick with explicit chunk count and tier. */
    public static Result forceWith(
            ChunkPos chunk, long worldSeed, ResourceLocation typeId,
            DepositType type, float tier, int sizeChunks) {
        WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
        rng.setLargeFeatureSeed(worldSeed, chunk.x, chunk.z);
        long shapeSeed = rng.nextLong();
        Set<ChunkPos> chunks = PerlinShape.generate(chunk, sizeChunks, shapeSeed);
        return new Result(typeId, type, chunks, 0f, tier);
    }

    /**
     * Per-chunk deterministic weighted pick from a type's {@code recipes + fillers}
     * pool. Returns the chosen vein recipe id, or empty on a filler entry.
     *
     * @param type         the deposit type
     * @param depositSeed  per-dimension deposit seed
     * @param cp           chunk being evaluated
     */
    public static Optional<ResourceLocation> rollChunkRecipe(DepositType type, long depositSeed, ChunkPos cp) {
        int total = type.totalChunkPoolWeight();
        if (total <= 0) return Optional.empty();

        WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
        rng.setLargeFeatureSeed(depositSeed ^ 0xC0EDE057A17EAL, cp.x, cp.z);
        int roll = rng.nextInt(total);
        int acc = 0;
        for (DepositType.WeightedRecipe wr : type.veinRecipes()) {
            acc += wr.weight();
            if (roll < acc) return Optional.of(wr.recipe());
        }
        return Optional.empty();
    }

    /**
     * Translate a target per-chunk unit count into COE's {@code randomMul}:
     * inverse of {@code units = ((max-min)×randomMul + min) × finiteBase}. Floored at 0.
     */
    public static float amountMulForTarget(double targetUnits, float recipeMin, float recipeMax, int finiteBase) {
        double perChunkRecipe = targetUnits / finiteBase;
        double computed = (perChunkRecipe - recipeMin) / (recipeMax - recipeMin);
        return (float) Math.max(0.0, computed);
    }

    /**
     * Decide whether this chunk spawns a new managed deposit core, and which type.
     * Deterministic for the same {@code (worldSeed, chunk)}. Returns null if the chunk
     * didn't roll a core or no type was eligible.
     */
    public static Result tryPick(
            ChunkPos chunk, BlockPos spawn, long worldSeed, DepositTypeLoader registry,
            float baseRadius, float maxRadius, float coreSpawnProbability,
            Holder<Biome> biome, ResourceLocation dimension) {

        WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
        rng.setLargeFeatureSeed(worldSeed, chunk.x, chunk.z);
        long shapeSeed = rng.nextLong();

        if (rng.nextFloat() >= coreSpawnProbability) return null;

        BlockPos center = new BlockPos(chunk.getMiddleBlockX(), spawn.getY(), chunk.getMiddleBlockZ());
        double distFromSpawn = DistanceGradient.distance(center, spawn);
        float tier = DistanceGradient.tierFraction(center, spawn, baseRadius, maxRadius);

        List<Map.Entry<ResourceLocation, DepositType>> eligible = new ArrayList<>();
        int totalWeight = 0;
        int rejectedDistance = 0, rejectedBiome = 0, rejectedPlacement = 0, rejectedDimension = 0;
        for (var e : registry.all().entrySet()) {
            DepositType t = e.getValue();
            if (t.placement() != DepositType.Placement.MANAGED) { rejectedPlacement++; continue; }
            if (dimension != null && !t.matchesDimension(dimension)) { rejectedDimension++; continue; }
            if (distFromSpawn < t.distance().min() || distFromSpawn > t.distance().max()) { rejectedDistance++; continue; }
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
            if (uk.niknik.coedeposits.Config.LOG_SCAN_REJECTIONS.get()) {
                uk.niknik.coedeposits.Coedeposits.LOGGER.info(
                        "[coedeposits] chunk {},{} candidate but no eligible type (registry={}, placementRejected={}, dimensionRejected={}, distRejected={}, biomeRejected={}, dist={}, biome={}, dim={})",
                        chunk.x, chunk.z, registry.all().size(), rejectedPlacement, rejectedDimension, rejectedDistance, rejectedBiome,
                        (int) distFromSpawn, biome != null ? biome.unwrapKey().map(k -> k.location().toString()).orElse("?") : "null",
                        dimension);
            }
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
        return new Result(chosen.getKey(), type, chunks, 0f, tier);
    }
}
