package uk.niknik.coedeposits.gen;

import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import com.tom.createores.recipe.VeinRecipe;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Stateless scan helpers — dry-run (off-thread safe) + materialize (main-thread
 * only) so {@link ProspectScanQueue} can move picker work off the server tick.
 *
 * <p>Loader-agnostic (platform-shared); ports verbatim — the biome-source
 * sampling API ({@code getBiomeSource}, {@code randomState().sampler()},
 * {@code getNoiseBiome(x,y,z,sampler)}) is identical in 1.20.1.
 */
public final class ProspectScanner {
    private ProspectScanner() {}

    /** Immutable snapshot of level/config state the dry-run phase needs (server-thread captured, any-thread read). */
    public record ScanSnapshot(
            BlockPos spawn,
            long worldSeed,
            ResourceLocation dimension,
            float baseR,
            float maxR,
            float prob,
            BiomeSource biomeSource,
            Climate.Sampler sampler) {

        public static ScanSnapshot capture(ServerLevel lvl) {
            DepositSavedData store = DepositSavedData.get(lvl);
            return new ScanSnapshot(
                    lvl.getSharedSpawnPos(),
                    store.effectiveSeed(lvl),
                    lvl.dimension().location(),
                    Config.BASE_RADIUS.get().floatValue(),
                    Config.MAX_RADIUS.get().floatValue(),
                    Config.CORE_SPAWN_PROBABILITY.get().floatValue(),
                    lvl.getChunkSource().getGenerator().getBiomeSource(),
                    lvl.getChunkSource().randomState().sampler());
        }
    }

    /** Pure outcome of a dry run — what materialize needs to persist the deposit. */
    public record PendingPlacement(
            ChunkPos coreChunk,
            ResourceLocation typeId,
            Set<ChunkPos> chunks,
            float tierFraction) {}

    /** Off-thread-safe per-chunk dry run. Returns null when no core rolled / no type matched. */
    public static PendingPlacement dryRunChunk(ScanSnapshot snap, ChunkPos cp) {
        BlockPos chunkCenter = new BlockPos(
                cp.getMiddleBlockX(), snap.spawn().getY(), cp.getMiddleBlockZ());
        Holder<Biome> biome = snap.biomeSource().getNoiseBiome(
                QuartPos.fromBlock(chunkCenter.getX()),
                QuartPos.fromBlock(64),
                QuartPos.fromBlock(chunkCenter.getZ()),
                snap.sampler());

        DepositPlacer.Result result = DepositPlacer.tryPick(
                cp, snap.spawn(), snap.worldSeed(), Coedeposits.DEPOSIT_TYPES,
                snap.baseR(), snap.maxR(), snap.prob(), biome, snap.dimension());
        if (result == null) return null;

        return new PendingPlacement(cp, result.typeId(), result.chunks(), result.tierFraction());
    }

    /** Main-thread materialize — persists the placement + writes OreData on loaded blob chunks. */
    public static boolean materialize(ServerLevel lvl, PendingPlacement p) {
        DepositSavedData store = DepositSavedData.get(lvl);
        if (store.isOccupied(p.coreChunk())) return false;

        DepositType type = Coedeposits.DEPOSIT_TYPES.get(p.typeId());
        if (type == null) return false;
        if (type.veinRecipes().isEmpty()) return false;

        ResourceLocation referenceRecipeId = type.veinRecipes().get(0).recipe();
        VeinRecipe vr = CoedepositsPicker.resolveRecipeValue(lvl, referenceRecipeId);
        if (vr == null) return false;

        double targetUnits = type.perChunkUnits().computeTarget(
                p.tierFraction(), Config.UNBOUNDED_GROWTH.get());
        int finiteBase = com.tom.createores.Config.finiteAmountBase;
        float amountMul = DepositPlacer.amountMulForTarget(
                targetUnits, vr.getMinAmount(), vr.getMaxAmount(), finiteBase);

        Deposit candidate = new Deposit(
                UUID.randomUUID(),
                p.typeId(),
                p.typeId().getPath() + "@" + (p.coreChunk().x * 16) + "," + (p.coreChunk().z * 16),
                p.coreChunk(),
                p.chunks(),
                amountMul,
                p.tierFraction(),
                DepositType.Placement.MANAGED,
                0.0);
        DepositSavedData.OverlapResult res =
                store.addResolvingOverlap(candidate, CoedepositsPicker::weightOf);
        Deposit dep = res.placed();
        if (dep == null) return false;
        CoedepositsPicker.syncOverlap(lvl, store, res, candidate.id());

        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
        long depositSeed = store.effectiveSeed(lvl);
        for (ChunkPos cc : dep.chunks()) {
            var loadedChunk = lvl.getChunkSource().getChunkNow(cc.x, cc.z);
            if (loadedChunk == null) continue;
            java.util.Optional<ResourceLocation> recipeId =
                    DepositPlacer.rollChunkRecipe(type, depositSeed, cc);
            if (recipeId.isEmpty()) continue;
            float perChunkMul = dep.amountMulFor(cc, edgeMul);
            CoedepositsPicker.applyToOreData(loadedChunk, recipeId.get(), perChunkMul);
            loadedChunk.setUnsaved(true);
        }
        return true;
    }

    /** Synchronous one-shot scan around the level's shared spawn (used by commands). */
    public static void scan(ServerLevel lvl, int blockRadius) {
        scanAround(lvl, lvl.getSharedSpawnPos(), blockRadius);
    }

    /** Synchronous one-shot scan centred on {@code center}. */
    public static void scanAround(ServerLevel lvl, BlockPos center, int blockRadius) {
        if (blockRadius <= 0) {
            if (Config.LOG_SCAN_SUMMARY.get()) {
                Coedeposits.LOGGER.info("[coedeposits] prospect scan disabled (prospect_radius=0)");
            }
            return;
        }

        ScanSnapshot snap = ScanSnapshot.capture(lvl);
        DepositSavedData store = DepositSavedData.get(lvl);
        ChunkPos centerCp = new ChunkPos(center);
        int chunkRadius = blockRadius / 16;

        long startMs = System.currentTimeMillis();
        int scanned = 0, skipped = 0, placed = 0;

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                scanned++;
                ChunkPos cp = new ChunkPos(centerCp.x + dx, centerCp.z + dz);
                if (store.isOccupied(cp)) { skipped++; continue; }

                PendingPlacement p = dryRunChunk(snap, cp);
                if (p == null) continue;

                if (materialize(lvl, p)) placed++;
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        if ((placed > 0 || elapsed > 500) && Config.LOG_SCAN_SUMMARY.get()) {
            Coedeposits.LOGGER.info(
                    "[coedeposits] prospect scan at ({},{}): radius={} blocks, scanned={} chunks, " +
                            "skipped={} (already placed), placed={} new deposits in {} ms",
                    center.getX(), center.getZ(),
                    blockRadius, scanned, skipped, placed, elapsed);
        }
    }
}
