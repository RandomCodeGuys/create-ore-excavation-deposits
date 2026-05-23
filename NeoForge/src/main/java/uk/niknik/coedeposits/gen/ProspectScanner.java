package uk.niknik.coedeposits.gen;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

import com.tom.createores.recipe.VeinRecipe;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * One-shot scan that runs our deposit picker (dry, no OreData write) across all
 * chunks in {@code blockRadius} around the world spawn. Every successful pick
 * is persisted to {@link DepositSavedData}. Players see the resulting deposits
 * on the world-map overlay immediately on login — no need to walk into chunks
 * to "discover" them.
 *
 * <p>Idempotent: chunks already in SavedData are skipped, so re-runs on
 * subsequent server starts only fill in newly-eligible chunks (e.g., after a
 * config density bump).
 *
 * <p>Cost: O((radius/16)²) tryPick calls — ~140k for radius=3000, ~1.4s
 * single-threaded on a typical CPU. Runs once on ServerStartedEvent.
 */
public final class ProspectScanner {
    private ProspectScanner() {}

    /**
     * Scan all chunks within {@code blockRadius} of the level's shared spawn,
     * persist any successful deposit picks to SavedData. Convenience wrapper
     * for the server-start path; calls {@link #scanAround} with spawn as centre.
     *
     * @param lvl          level to scan (usually overworld — distance gradient is centred here)
     * @param blockRadius  scan radius in blocks. A radius of 2000 covers ~62.5k chunks
     */
    public static void scan(ServerLevel lvl, int blockRadius) {
        scanAround(lvl, lvl.getSharedSpawnPos(), blockRadius);
    }

    /**
     * Scan all chunks within {@code blockRadius} of {@code center}, persist
     * any new deposits. Idempotent — already-placed chunks are skipped, so
     * this is safe to call repeatedly as a player roams.
     *
     * @param lvl          level to scan
     * @param center       centre of the scan box in world blocks
     * @param blockRadius  scan radius in blocks; {@code <= 0} disables the scan
     */
    public static void scanAround(ServerLevel lvl, BlockPos center, int blockRadius) {
        if (blockRadius <= 0) {
            Coedeposits.LOGGER.info("[coedeposits] prospect scan disabled (prospect_radius=0)");
            return;
        }

        DepositSavedData store = DepositSavedData.get(lvl);
        BlockPos spawn = lvl.getSharedSpawnPos();
        ChunkPos centerCp = new ChunkPos(center);
        int chunkRadius = blockRadius / 16;
        long worldSeed = store.effectiveSeed(lvl);
        float baseR = Config.BASE_RADIUS.get().floatValue();
        float maxR = Config.MAX_RADIUS.get().floatValue();
        float prob = Config.CORE_SPAWN_PROBABILITY.get().floatValue();

        long startMs = System.currentTimeMillis();
        int scanned = 0, skipped = 0, placed = 0;

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                scanned++;
                ChunkPos cp = new ChunkPos(centerCp.x + dx, centerCp.z + dz);
                if (store.isOccupied(cp)) { skipped++; continue; }

                BlockPos chunkCenter = new BlockPos(
                        cp.getMiddleBlockX(), spawn.getY(), cp.getMiddleBlockZ());
                Holder<Biome> biome = lvl.getNoiseBiome(
                        QuartPos.fromBlock(chunkCenter.getX()),
                        QuartPos.fromBlock(64),
                        QuartPos.fromBlock(chunkCenter.getZ()));

                DepositPlacer.Result result = DepositPlacer.tryPick(
                        cp, spawn, worldSeed, Coedeposits.DEPOSIT_TYPES,
                        baseR, maxR, prob, biome,
                        lvl.dimension().location());
                if (result == null) continue;

                // Convert per_chunk_units target → COE-randomMul via the recipe.
                VeinRecipe vr = CoedepositsPicker.resolveRecipeValue(lvl, result.type().veinRecipe());
                if (vr == null) continue;  // recipe missing, skip silently
                DepositType type = result.type();
                double targetUnits = type.perChunkUnits().computeTarget(
                        result.tierFraction(), Config.UNBOUNDED_GROWTH.get());
                int finiteBase = com.tom.createores.Config.finiteAmountBase;
                float amountMul = DepositPlacer.amountMulForTarget(
                        targetUnits, vr.getMinAmount(), vr.getMaxAmount(), finiteBase);

                Deposit dep = new Deposit(
                        UUID.randomUUID(),
                        result.typeId(),
                        result.typeId().getPath() + "@" + (cp.x * 16) + "," + (cp.z * 16),
                        cp,
                        result.chunks(),
                        amountMul,
                        result.tierFraction(),
                        uk.niknik.coedeposits.deposit.DepositType.Placement.MANAGED);
                store.add(dep);
                placed++;

                // If any chunk of the new blob is currently loaded, materialize
                // its OreData immediately — otherwise the picker would only run
                // on the next chunk reload, leaving the deposit invisible at
                // ground level despite the map marker. This matters most after
                // /coedeposits regenerate when the player's current chunks were
                // just wiped and need fresh OreData without a server restart.
                float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
                for (ChunkPos cc : dep.chunks()) {
                    var loadedChunk = lvl.getChunkSource().getChunkNow(cc.x, cc.z);
                    if (loadedChunk == null) continue;
                    float perChunkMul = dep.amountMulFor(cc, edgeMul);
                    CoedepositsPicker.applyToOreData(loadedChunk, type.veinRecipe(), perChunkMul);
                    loadedChunk.setUnsaved(true);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        if (placed > 0 || elapsed > 500) {
            // Only log when something happened or it was slow — roving scans
            // would otherwise spam the log with zero-placed lines.
            Coedeposits.LOGGER.info(
                    "[coedeposits] prospect scan at ({},{}): radius={} blocks, scanned={} chunks, " +
                            "skipped={} (already placed), placed={} new deposits in {} ms",
                    center.getX(), center.getZ(),
                    blockRadius, scanned, skipped, placed, elapsed);
        }
    }
}
