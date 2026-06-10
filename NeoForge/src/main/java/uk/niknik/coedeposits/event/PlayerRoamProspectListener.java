package uk.niknik.coedeposits.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.gen.PickerInstaller;
import uk.niknik.coedeposits.gen.ProspectScanQueue;
import uk.niknik.coedeposits.network.CoedepositsNetwork;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Two server-tick jobs piggy-backed on the same per-player loop:
 * <ol>
 *   <li><b>Prospect scan enqueue</b> — when a player has roamed more than half
 *       a prospect-radius from their last scan centre, enqueue an async scan
 *       around their current position into {@link ProspectScanQueue}. Cheap on
 *       the tick — the actual picker work runs on the queue's worker thread.</li>
 *   <li><b>ON_DISCOVERY reveal</b> — for every saved deposit whose effective
 *       reveal mode is {@link Config.RevealMode#ON_DISCOVERY}, checks whether
 *       the player is now within {@link Config#DISCOVERY_RADIUS_BLOCKS} of any
 *       of its chunks and, if so, marks it revealed for that player and pushes
 *       the one-shot sync + chat notification.</li>
 * </ol>
 *
 * <p>The 200-tick cadence applies to both jobs — discovery latency of up to
 * 10s when walking up to a deposit is acceptable, and the prospect-scan
 * trigger doesn't need to fire faster than the player can cross a prospect
 * radius. The queue's materialize phase, however, runs <i>every</i> tick at
 * {@code prospect_chunks_per_tick} budget so pending placements drain
 * smoothly without bursting on the same 200-tick beat.
 */
@EventBusSubscriber(modid = Coedeposits.MODID)
public final class PlayerRoamProspectListener {
    private PlayerRoamProspectListener() {}

    /** How often we re-check player positions for the (expensive) prospect scan-enqueue — 200 ticks = 10 seconds. */
    private static final int CHECK_INTERVAL_TICKS = 200;

    /** How often we sweep ON_DISCOVERY reveals — 20 ticks ≈ 1s, so walking into a deposit reveals it promptly (cheap: O(deposits), short-circuits on already-revealed / wrong-mode). */
    private static final int DISCOVERY_SWEEP_TICKS = 20;

    /** Per-player last position where we enqueued a scan; reset when player rejoins. */
    private static final Map<UUID, BlockPos> lastScanCenter = new HashMap<>();

    /**
     * Tick handler — two cadences:
     * <ul>
     *   <li><b>Every tick</b>: drain up to {@link Config#PROSPECT_CHUNKS_PER_TICK}
     *       placements from each enabled level's queue. Cheap when the queue is
     *       empty (early return on {@code pendingPlacements.isEmpty()}).</li>
     *   <li><b>Every 200 ticks</b>: for each online player, enqueue a scan if
     *       they've roamed past the trigger, then sweep ON_DISCOVERY reveals.</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();

        // Per-tick: drain materialize queue for every managed level. Cheap when
        // idle so unconditional invocation is fine.
        int budget = Config.PROSPECT_CHUNKS_PER_TICK.get();
        for (ServerLevel lvl : PickerInstaller.enabledLevels(server)) {
            ProspectScanQueue.INSTANCE.tickMaterialize(lvl, budget);
        }

        long tick = server.getTickCount();
        boolean doScan = tick % CHECK_INTERVAL_TICKS == 0;
        boolean doDiscovery = tick % DISCOVERY_SWEEP_TICKS == 0;
        if (!doScan && !doDiscovery) return;

        int prospectRadius = Config.PROSPECT_RADIUS.get();
        int discoveryRadius = Config.DISCOVERY_RADIUS_BLOCKS.get();
        long discoveryRadiusSq = (long) discoveryRadius * discoveryRadius;
        long prospectTriggerSq = (long) (prospectRadius / 2) * (prospectRadius / 2);

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerLevel lvl = p.serverLevel();
            // Skip players in dimensions we don't manage — vanilla COE handles
            // those, and we shouldn't scan/discover there.
            if (!Config.isDimensionEnabled(lvl.dimension().location())) continue;
            BlockPos current = p.blockPosition();

            // Job 1: incremental prospect scan (enqueue — actual work is async).
            // Expensive, so kept on the slow 200-tick cadence.
            if (doScan && prospectRadius > 0) {
                BlockPos last = lastScanCenter.get(p.getUUID());
                if (last == null || distSq(last, current) > prospectTriggerSq) {
                    ProspectScanQueue.INSTANCE.enqueue(lvl, current, prospectRadius);
                    lastScanCenter.put(p.getUUID(), current);
                }
            }

            // Job 2: ON_DISCOVERY reveal sweep — fast cadence so "walk into it"
            // reveals within ~1s.
            if (doDiscovery) {
                tryRevealDiscoveryNear(lvl, p, current, discoveryRadiusSq);
            }
        }
    }

    /**
     * For each not-yet-revealed ON_DISCOVERY deposit, check whether the player
     * is within {@code discoveryRadiusSq} (squared blocks) of any of its
     * chunks. Reveals matches via {@link CoedepositsNetwork#revealAndNotify}.
     */
    private static void tryRevealDiscoveryNear(ServerLevel lvl, ServerPlayer player, BlockPos current, long discoveryRadiusSq) {
        DepositSavedData store = DepositSavedData.get(lvl);
        if (store.all().isEmpty()) return;
        UUID pid = player.getUUID();
        // O(deposits) per player per check interval. Filters short-circuit in
        // this order: already-revealed > wrong-mode > out-of-range > network.
        // Most deposits hit "already revealed" fast and the loop body is cheap.
        int proximityRadius = Config.PROXIMITY_REVEAL_BLOCKS.get();
        long proximityRadiusSq = (long) proximityRadius * proximityRadius;
        for (Deposit dep : store.all().values()) {
            // Filter 1: skip deposits this player already discovered (for
            // ON_PROXIMITY the revealed set is just a "notified once" marker —
            // visibility stays purely distance-based on the client).
            if (store.isRevealed(pid, dep.id())) continue;
            // Filter 2: mode dispatch — ON_PROSPECT is handled by
            // VeinFinderListener, ALWAYS never needs a reveal.
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
            Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
            if (mode == Config.RevealMode.ON_DISCOVERY) {
                // Filter 3: spatial proximity to any of the deposit's chunks.
                if (!withinAnyChunk(current, dep, discoveryRadiusSq)) continue;
                // Trigger the reveal — revealAndNotify is idempotent (returns
                // false if the reveal already happened in a race), so logging
                // only fires on the genuine first discovery.
                if (CoedepositsNetwork.revealAndNotify(lvl, player, dep)) {
                    if (Config.LOG_DISCOVERY.get()) {
                        Coedeposits.LOGGER.info("[coedeposits] {} discovered {} via walk",
                                player.getName().getString(), dep.name());
                    }
                }
            } else if (mode == Config.RevealMode.ON_PROXIMITY) {
                // Personal chat notice when the player first comes within the
                // proximity radius (the same radius the map filter uses). Always
                // per-player regardless of reveal_scope — proximity visibility is
                // inherently personal-by-distance, the chat just mirrors it.
                if (!withinAnyChunk(current, dep, proximityRadiusSq)) continue;
                if (store.reveal(pid, dep.id())) {
                    CoedepositsNetwork.sendProximityNotice(lvl, player, dep);
                    if (Config.LOG_DISCOVERY.get()) {
                        Coedeposits.LOGGER.info("[coedeposits] {} came near {} (proximity notice)",
                                player.getName().getString(), dep.name());
                    }
                }
            }
        }
    }

    /** True if {@code from} is within {@code radiusSq} (squared blocks) of any chunk's centre in {@code dep}. */
    private static boolean withinAnyChunk(BlockPos from, Deposit dep, long radiusSq) {
        int fx = from.getX();
        int fz = from.getZ();
        for (ChunkPos cp : dep.chunks()) {
            long dx = cp.getMiddleBlockX() - fx;
            long dz = cp.getMiddleBlockZ() - fz;
            if (dx * dx + dz * dz <= radiusSq) return true;
        }
        return false;
    }

    /** XZ-plane squared distance between two BlockPos. Cheaper than sqrt for distance threshold checks. */
    private static long distSq(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Drop the player's last-scan-centre entry on logout — otherwise the map
     * grows by one entry per unique player UUID for the lifetime of the
     * process. Per-player <em>reveal</em> history in {@link DepositSavedData}
     * deliberately survives logouts so discovered map markers don't reset
     * each session.
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            lastScanCenter.remove(sp.getUUID());
        }
    }
}
