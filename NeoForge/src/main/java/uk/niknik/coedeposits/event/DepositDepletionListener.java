package uk.niknik.coedeposits.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.tom.createores.OreData;
import com.tom.createores.OreDataAttachment;
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
 * Server-tick listener that does three periodic jobs:
 * <ol>
 *   <li><b>Depletion sweep</b> (every 20 ticks ≈ 1s) — iterates only chunks
 *       tracked by {@link DepositSavedData}, clears OreData when a drill has
 *       fully extracted the vein. After clearing, Vein Finder reports
 *       "Nothing" for that chunk and the drill stops.</li>
 *   <li><b>Replenishment sweep</b> (every 20 ticks ≈ 1s) — for each deposit
 *       whose effective {@code replenish_rate_per_hour} is positive, decrement
 *       {@link OreData#getExtractedAmount} on its loaded ore chunks by the
 *       per-chunk delta derived from the rate and the elapsed wall-clock
 *       time. Bounded at 0 (a deposit can never regenerate beyond its
 *       initial amount). Default rate is 0 so this is a no-op for the
 *       bundled deposit types until an admin opts in via config or
 *       {@code /coedeposits replenish}.</li>
 *   <li><b>Map re-sync</b> (every 100 ticks ≈ 5s) — broadcasts current
 *       snapshots of all overworld deposits to every online player so the
 *       world-map tooltip shows fresh remaining counts as drills extract
 *       (and as the replenish sweep restores).</li>
 * </ol>
 */
@EventBusSubscriber(modid = Coedeposits.MODID)
public final class DepositDepletionListener {
    private DepositDepletionListener() {}

    // Depletion sweep every 20 ticks (~1s). Iterates only chunks tracked by
    // DepositSavedData, not the whole loaded-chunks set.
    private static final int DEPLETION_INTERVAL_TICKS = 20;
    // Replenishment runs on the same cadence — sub-unit rates accumulate via
    // a fractional debt map so a slow replenish (e.g. 100/hour ≈ 0.028 units/sec)
    // still moves over time despite OreData using integer amounts.
    private static final int REPLENISH_INTERVAL_TICKS = 20;
    // World-map overlay re-sync every 100 ticks (~5s) so tooltips show fresh
    // per-chunk remaining as drills extract. Cheap broadcast (one packet per
    // dimension to all online players).
    private static final int SYNC_INTERVAL_TICKS = 100;

    /**
     * Per-deposit per-chunk fractional debt accumulator. Each entry tracks
     * how much extra fractional unit-restoration we owe a chunk between
     * integer applies — keeps slow replenish rates from getting rounded to
     * zero every tick. Transient (in-memory only) so a server restart loses
     * at most one tick of fractional credit; acceptable given typical rates.
     */
    private static final Map<UUID, Map<Long, Double>> REPLENISH_ACCUMULATORS = new ConcurrentHashMap<>();

    /** Game-bus tick handler — gates depletion, replenish, and re-sync work on interval. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        long tick = server.getTickCount();

        // ── Job 1: depletion sweep (every 20 ticks ≈ 1s) ────────────────────
        // Walks only chunks tracked by SavedData (not every loaded chunk in
        // the world), so cost is O(total deposit chunks) per second — usually
        // a few hundred at most. clearIfDepleted is a no-op when the chunk's
        // OreData isn't fully drained, so we don't pay for chunks still being
        // mined.
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
                        // getChunkNow returns null for unloaded chunks — they
                        // get checked on the next sweep that catches them
                        // loaded. No need to force-load just to inspect.
                        LevelChunk chunk = cache.getChunkNow(cp.x, cp.z);
                        if (chunk == null) continue;
                        OreData od = OreDataAttachment.getData(chunk);
                        if (od.getRecipeId() == null) {
                            // Self-heal: a deposit chunk with no recipe AND nothing
                            // extracted was never applied — it was placed over an
                            // already-populated chunk (e.g. /coedeposits regenerate
                            // on explored terrain), so COE's populate (and our
                            // chunk-load apply) never re-ran. Re-apply it. Genuinely
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

        // ── Job 2: replenishment sweep (every 20 ticks ≈ 1s) ────────────────
        // Per-deposit rate (units/hour, from type default or per-deposit
        // override) is divided by the deposit's loaded ore chunks each sweep.
        // Sub-unit per-tick remainders accumulate in REPLENISH_ACCUMULATORS
        // so e.g. a 100-units/hour rate over 1 chunk (0.028 units/sec) still
        // makes progress: after ~36 seconds the accumulator reaches 1 and one
        // unit of extractedAmount comes off.
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
                    if (rate <= 0.0) continue;  // replenish disabled for this deposit

                    replenishDeposit(lvl, cache, dep, type, depositSeed, rate, elapsedSec);
                }
            }
        }

        // ── Job 3: map re-sync broadcast (every 100 ticks ≈ 5s) ─────────────
        // Pushes fresh snapshots so the world-map tooltip shows up-to-date
        // remaining counts as drills extract. Skipped entirely when no players
        // are online — no point computing snapshots no one will receive.
        if (tick % SYNC_INTERVAL_TICKS == 0 && !server.getPlayerList().getPlayers().isEmpty()) {
            for (ServerLevel lvl : PickerInstaller.enabledLevels(server)) {
                DepositSavedData store = DepositSavedData.get(lvl);
                if (store.all().isEmpty()) continue;
                // Reveal-aware per-player broadcast — ON_DISCOVERY/ON_PROSPECT
                // deposits stay hidden for players that haven't unlocked them.
                // broadcastSyncFiltered already filters by player's current dimension.
                CoedepositsNetwork.broadcastSyncFiltered(server, lvl, store.all().values());
            }
        }
    }

    /**
     * Apply one tick of replenishment to all loaded ore chunks of a deposit.
     * Filler chunks (no OreData) and depleted chunks (recipe nulled by the
     * depletion sweep) are skipped. Per-chunk delta = (rate / 3600) × elapsedSec
     * / loadedOreChunkCount, accumulated in {@link #REPLENISH_ACCUMULATORS}
     * until it crosses 1 unit, at which point the integer amount is added
     * back to {@code remaining} by writing a smaller {@code extractedAmount}.
     *
     * <p>OreData doesn't expose a {@code getExtractedAmount()} getter, so we
     * derive it from {@code total - getResourcesRemaining}, where
     * {@code total} comes from re-applying the deposit's per-chunk amountMul
     * formula to the active recipe's min/max bounds. This avoids a reflection
     * read on COE's internals.
     */
    private static void replenishDeposit(
            ServerLevel lvl,
            ServerChunkCache cache,
            Deposit dep,
            DepositType type,
            long depositSeed,
            double ratePerHour,
            double elapsedSec) {

        // ── Step 1: enumerate loaded ore chunks (skip filler + unloaded) ────
        // We need the loaded-count first so the per-chunk distribution is
        // correct — splitting the rate evenly across loaded chunks means
        // unloaded chunks don't "steal" debt that never gets applied.
        java.util.List<LevelChunk> loadedOreChunks = new java.util.ArrayList<>();
        for (ChunkPos cp : dep.chunks()) {
            LevelChunk chunk = cache.getChunkNow(cp.x, cp.z);
            if (chunk == null) continue;
            // Filler chunks have no OreData. We could check OreData.recipe ==
            // null but that's also true for legitimately-cleared depleted
            // chunks during the depletion sweep. Use the placement roll —
            // matches the same logic that decided whether to apply OreData
            // in the first place.
            if (dep.placement() == DepositType.Placement.MANAGED) {
                java.util.Optional<ResourceLocation> rolled =
                        DepositPlacer.rollChunkRecipe(type, depositSeed, cp);
                if (rolled.isEmpty()) continue;
            }
            loadedOreChunks.add(chunk);
        }
        if (loadedOreChunks.isEmpty()) return;  // nothing to replenish right now

        // ── Step 2: distribute the per-second budget across loaded chunks ───
        // Per-deposit budget = rate / 3600 units/sec × elapsedSec.
        // Each chunk's share is even split across loadedOreChunks.size().
        double totalUnitsThisSweep = (ratePerHour / 3600.0) * elapsedSec;
        double perChunkUnits = totalUnitsThisSweep / loadedOreChunks.size();

        // ── Step 3: per-chunk accumulator → integer apply ───────────────────
        // accumulator carries fractional debt across sweeps so slow rates
        // eventually produce an integer subtraction even if perChunkUnits
        // is < 1 per sweep. New-remaining is clamped at initial-total — a
        // deposit can never grow beyond what was originally extracted from it.
        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
        int finiteBase = com.tom.createores.Config.finiteAmountBase;
        Map<Long, Double> depAccum = REPLENISH_ACCUMULATORS.computeIfAbsent(
                dep.id(), k -> new HashMap<>());
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

    /**
     * Subtract {@code integerUnits} from a chunk's extractedAmount, clamped at
     * 0 (cap-at-initial — replenish only fills the hole drilling made, never
     * grows the deposit). Skipped silently when the chunk has no recipe
     * (filler or fully-depleted-and-wiped chunk) or the recipe isn't loaded.
     */
    private static void applyReplenish(
            ServerLevel lvl, LevelChunk chunk, Deposit dep,
            float edgeMul, int finiteBase, long integerUnits) {
        OreData od = OreDataAttachment.getData(chunk);
        // No recipe → either a filler chunk (shouldn't happen because we
        // filtered above) or a depleted+wiped chunk (recipe was nulled by
        // the depletion sweep — nothing to refill). Skip.
        if (od.getRecipeId() == null) return;
        RecipeHolder<VeinRecipe> rh = od.getRecipe(lvl.getRecipeManager());
        if (rh == null) return;
        VeinRecipe vr = rh.value();

        long remaining = od.getResourcesRemaining(vr);
        // Infinite veins (recipe.finite==NEVER) report 0 — replenishing them
        // is pointless. Negative is a depletion sentinel — should already be
        // handled by the recipe-null guard above but defensive.
        if (remaining <= 0) return;

        // total = ((max - min) * perChunkRandomMul + min) * finiteBase.
        // perChunkRandomMul uses the deposit's amountMul + edge gradient —
        // mirrors what applyToOreData computed when the chunk was placed.
        float perChunkRandomMul = dep.amountMulFor(chunk.getPos(), edgeMul);
        long total = Math.round(
                ((vr.getMaxAmount() - vr.getMinAmount()) * perChunkRandomMul + vr.getMinAmount())
                        * (double) finiteBase);
        // Clamp the new remaining at the original total — nothing should grow
        // a deposit beyond its initial yield.
        long newRemaining = Math.min(total, remaining + integerUnits);
        if (newRemaining == remaining) return;  // already at the cap

        long newExtracted = Math.max(0L, total - newRemaining);
        od.setExtractedAmount(newExtracted);
        chunk.setUnsaved(true);

        // Per-action log gated behind LOG_REPLENISH_ACTIONS — off by default
        // because this fires once per (deposit, chunk) per replenish sweep
        // and can flood the log on servers with many replenishing deposits.
        // Useful one-shot diagnostic when verifying a specific deposit is
        // actually being replenished by the sweep.
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
     * already-populated terrain that never got applied). Mirrors the picker's
     * chunk-load apply: per-chunk recipe roll for MANAGED, first recipe for COE;
     * filler rolls and unloaded recipes are skipped.
     * {@link CoedepositsPicker#applyToOreData} resets extractedAmount to 0,
     * which is correct here (the chunk was never mined).
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

    /** If {@code OreData.getResourcesRemaining() == -1}, null out the recipe so the vein looks "empty". */
    private static void clearIfDepleted(ServerLevel lvl, LevelChunk chunk) {
        OreData od = OreDataAttachment.getData(chunk);
        if (od.getRecipeId() == null) return;
        RecipeHolder<VeinRecipe> rh = od.getRecipe(lvl.getRecipeManager());
        if (rh == null) return;
        long remaining = od.getResourcesRemaining(rh.value());
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
