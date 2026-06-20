package uk.niknik.coedeposits.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import com.tom.createores.OreDataCapability;
import com.tom.createores.recipe.VeinRecipe;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.gen.CoedepositsPicker;
import uk.niknik.coedeposits.gen.DepositPlacer;
import uk.niknik.coedeposits.gen.PickerInstaller;
import uk.niknik.coedeposits.network.CoedepositsNetwork;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Server-tick listener: depletion sweep (clear OreData of fully-drilled chunks),
 * replenishment sweep, and map re-sync.
 *
 * <p><b>1.20.1 line:</b> Forge {@code @Mod.EventBusSubscriber} +
 * {@code TickEvent.ServerTickEvent} (phase END); OreData read through
 * {@link OreDataCapability} (Forge Capabilities); {@code OreData.getRecipe} returns
 * a bare {@link VeinRecipe} (no {@code RecipeHolder}).
 */
@Mod.EventBusSubscriber(modid = Coedeposits.MODID)
public final class DepositDepletionListener {
    private DepositDepletionListener() {}

    private static final int DEPLETION_INTERVAL_TICKS = 20;
    private static final int REPLENISH_INTERVAL_TICKS = 20;
    private static final int SYNC_INTERVAL_TICKS = 100;

    private static final Map<UUID, Map<Long, Double>> REPLENISH_ACCUMULATORS = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long tick = server.getTickCount();

        // ── Job 1: depletion sweep ──────────────────────────────────────────
        if (tick % DEPLETION_INTERVAL_TICKS == 0) {
            float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
            for (ServerLevel lvl : PickerInstaller.enabledLevels(server)) {
                DepositSavedData store = DepositSavedData.get(lvl);
                if (store.all().isEmpty()) continue;
                ServerChunkCache cache = lvl.getChunkSource();
                long depositSeed = store.effectiveSeed(lvl);

                for (Deposit dep : store.all().values()) {
                    DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
                    for (ChunkPos cp : dep.chunks()) {
                        LevelChunk chunk = cache.getChunkNow(cp.x, cp.z);
                        if (chunk == null) continue;
                        OreDataCapability.OreData od = OreDataCapability.getData(chunk);
                        if (od.getRecipeId() == null) {
                            // Self-heal: a deposit chunk with no recipe AND nothing
                            // extracted was never applied — it was placed over an
                            // already-populated chunk (e.g. /coedeposits regenerate
                            // on explored terrain), so COE's populate (and our
                            // Phase-1 apply) never re-ran. Re-apply it. Genuinely
                            // mined-out chunks (extracted > 0) stay depleted.
                            if (type != null && !type.veinRecipes().isEmpty()
                                    && CoedepositsPicker.getExtractedAmount(od) == 0L) {
                                reapplyOreData(lvl, chunk, dep, type, depositSeed, edgeMul);
                            }
                        } else {
                            clearIfDepleted(lvl, chunk);
                        }
                    }
                }
            }
        }

        // ── Job 2: replenishment sweep ──────────────────────────────────────
        if (tick % REPLENISH_INTERVAL_TICKS == 0) {
            double elapsedSec = REPLENISH_INTERVAL_TICKS / 20.0;
            for (ServerLevel lvl : PickerInstaller.enabledLevels(server)) {
                DepositSavedData store = DepositSavedData.get(lvl);
                if (store.all().isEmpty()) continue;
                long depositSeed = store.effectiveSeed(lvl);
                ServerChunkCache cache = lvl.getChunkSource();

                for (Deposit dep : store.all().values()) {
                    DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
                    if (type == null) continue;
                    double rate = dep.effectiveReplenishRate(type);
                    if (rate <= 0.0) continue;
                    replenishDeposit(lvl, cache, dep, type, depositSeed, rate, elapsedSec);
                }
            }
        }

        // ── Job 3: map re-sync broadcast ────────────────────────────────────
        if (tick % SYNC_INTERVAL_TICKS == 0 && !server.getPlayerList().getPlayers().isEmpty()) {
            for (ServerLevel lvl : PickerInstaller.enabledLevels(server)) {
                DepositSavedData store = DepositSavedData.get(lvl);
                if (store.all().isEmpty()) continue;
                CoedepositsNetwork.broadcastSyncFiltered(server, lvl, store.all().values());
            }
        }
    }

    private static void replenishDeposit(
            ServerLevel lvl, ServerChunkCache cache, Deposit dep, DepositType type,
            long depositSeed, double ratePerHour, double elapsedSec) {

        java.util.List<LevelChunk> loadedOreChunks = new java.util.ArrayList<>();
        for (ChunkPos cp : dep.chunks()) {
            LevelChunk chunk = cache.getChunkNow(cp.x, cp.z);
            if (chunk == null) continue;
            if (dep.placement() == DepositType.Placement.MANAGED) {
                java.util.Optional<ResourceLocation> rolled =
                        DepositPlacer.rollChunkRecipe(type, depositSeed, cp);
                if (rolled.isEmpty()) continue;
            }
            loadedOreChunks.add(chunk);
        }
        if (loadedOreChunks.isEmpty()) return;

        double totalUnitsThisSweep = (ratePerHour / 3600.0) * elapsedSec;
        double perChunkUnits = totalUnitsThisSweep / loadedOreChunks.size();

        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
        int finiteBase = com.tom.createores.Config.finiteAmountBase;
        Map<Long, Double> depAccum = REPLENISH_ACCUMULATORS.computeIfAbsent(dep.id(), k -> new HashMap<>());
        for (LevelChunk chunk : loadedOreChunks) {
            long packedPos = chunk.getPos().toLong();
            double accum = depAccum.getOrDefault(packedPos, 0.0) + perChunkUnits;
            long integerUnits = (long) accum;
            if (integerUnits > 0) {
                applyReplenish(lvl, chunk, dep, edgeMul, finiteBase, integerUnits);
                accum -= integerUnits;
            }
            depAccum.put(packedPos, accum);
        }
    }

    private static void applyReplenish(
            ServerLevel lvl, LevelChunk chunk, Deposit dep,
            float edgeMul, int finiteBase, long integerUnits) {
        OreDataCapability.OreData od = OreDataCapability.getData(chunk);
        if (od.getRecipeId() == null) return;
        VeinRecipe vr = od.getRecipe(lvl.getRecipeManager());
        if (vr == null) return;

        long remaining = od.getResourcesRemaining(vr);
        if (remaining <= 0) return;

        float perChunkRandomMul = dep.amountMulFor(chunk.getPos(), edgeMul);
        long total = Math.round(
                ((vr.getMaxAmount() - vr.getMinAmount()) * perChunkRandomMul + vr.getMinAmount())
                        * (double) finiteBase);
        long newRemaining = Math.min(total, remaining + integerUnits);
        if (newRemaining == remaining) return;

        long newExtracted = Math.max(0L, total - newRemaining);
        od.setExtractedAmount(newExtracted);
        chunk.setUnsaved(true);

        if (Config.LOG_REPLENISH_ACTIONS.get()) {
            long delta = newRemaining - remaining;
            Coedeposits.LOGGER.info(
                    "[coedeposits] replenished {} chunk {},{}: +{} units ({} → {} of {})",
                    dep.name(), chunk.getPos().x, chunk.getPos().z,
                    delta, remaining, newRemaining, total);
        }
    }

    /**
     * Re-apply a deposit chunk's OreData (self-heal for chunks placed over
     * already-populated terrain that never got applied). Mirrors pick()'s Phase 1:
     * per-chunk recipe roll for MANAGED, first recipe for COE; filler rolls and
     * unloaded recipes are skipped. {@link CoedepositsPicker#applyToOreData} resets
     * extractedAmount to 0, which is correct here (the chunk was never mined).
     */
    private static void reapplyOreData(ServerLevel lvl, LevelChunk chunk, Deposit dep,
                                       DepositType type, long depositSeed, float edgeMul) {
        ResourceLocation recipeId;
        if (dep.placement() == DepositType.Placement.COE) {
            recipeId = type.veinRecipes().get(0).recipe();
        } else {
            java.util.Optional<ResourceLocation> rolled =
                    DepositPlacer.rollChunkRecipe(type, depositSeed, chunk.getPos());
            if (rolled.isEmpty()) return;  // filler — legitimately no ore
            recipeId = rolled.get();
        }
        if (CoedepositsPicker.resolveRecipeValue(lvl, recipeId) == null) return;  // recipe not loaded
        float perChunk = dep.amountMulFor(chunk.getPos(), edgeMul);
        CoedepositsPicker.applyToOreData(chunk, recipeId, perChunk);
        chunk.setUnsaved(true);
        if (Config.LOG_DEPLETION.get()) {
            Coedeposits.LOGGER.info("[coedeposits] healed unapplied chunk {},{} of {} ({})",
                    chunk.getPos().x, chunk.getPos().z, dep.name(), recipeId);
        }
    }

    /** If the chunk's vein is fully extracted ({@code getResourcesRemaining == -1}), null its recipe. */
    private static void clearIfDepleted(ServerLevel lvl, LevelChunk chunk) {
        OreDataCapability.OreData od = OreDataCapability.getData(chunk);
        if (od.getRecipeId() == null) return;
        VeinRecipe vr = od.getRecipe(lvl.getRecipeManager());
        if (vr == null) return;
        long remaining = od.getResourcesRemaining(vr);
        if (remaining != -1L) return;

        od.setRecipe(null);
        od.setRandomMul(0f);
        chunk.setUnsaved(true);
        if (Config.LOG_DEPLETION.get()) {
            Coedeposits.LOGGER.info("[coedeposits] depleted chunk {},{} — cleared OreData",
                    chunk.getPos().x, chunk.getPos().z);
        }
    }
}
