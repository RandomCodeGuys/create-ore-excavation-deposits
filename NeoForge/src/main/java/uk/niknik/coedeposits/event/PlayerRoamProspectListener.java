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
import uk.niknik.coedeposits.gen.ProspectScanner;
import uk.niknik.coedeposits.network.CoedepositsNetwork;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Two server-tick jobs piggy-backed on the same per-player loop:
 * <ol>
 *   <li><b>Prospect scan</b> — re-runs the dry-run picker around each player as
 *       they explore so the world map fills in beyond the initial spawn area.
 *       Triggered when the player drifts more than half a prospect-radius from
 *       their last scan centre.</li>
 *   <li><b>ON_DISCOVERY reveal</b> — for every saved deposit whose effective
 *       reveal mode is {@link Config.RevealMode#ON_DISCOVERY}, checks whether
 *       the player is now within {@link Config#DISCOVERY_RADIUS_BLOCKS} of any
 *       of its chunks and, if so, marks it revealed for that player and pushes
 *       the one-shot sync + chat notification.</li>
 * </ol>
 *
 * <p>Both jobs use the same 200-tick (10s) cadence — discovery latency of up
 * to 10s when walking up to a deposit is acceptable and saves a per-tick scan.
 */
@EventBusSubscriber(modid = Coedeposits.MODID)
public final class PlayerRoamProspectListener {
    private PlayerRoamProspectListener() {}

    /** How often we check player positions — 200 ticks = 10 seconds. */
    private static final int CHECK_INTERVAL_TICKS = 200;

    /** Per-player last position where we ran a scan; reset when player rejoins. */
    private static final Map<UUID, BlockPos> lastScanCenter = new HashMap<>();

    /**
     * Tick handler — every 200 ticks, for each online player:
     * <ol>
     *   <li>If they've roamed more than {@code prospect_radius / 2} blocks
     *       since the previous scan, run an incremental prospect scan.</li>
     *   <li>Walk the deposit registry once and reveal any ON_DISCOVERY deposit
     *       within {@code discovery_radius_blocks} of the player.</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server.getTickCount() % CHECK_INTERVAL_TICKS != 0) return;
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

            // Job 1: incremental prospect scan
            if (prospectRadius > 0) {
                BlockPos last = lastScanCenter.get(p.getUUID());
                if (last == null || distSq(last, current) > prospectTriggerSq) {
                    ProspectScanner.scanAround(lvl, current, prospectRadius);
                    lastScanCenter.put(p.getUUID(), current);
                }
            }

            // Job 2: ON_DISCOVERY reveal sweep
            tryRevealDiscoveryNear(lvl, p, current, discoveryRadiusSq);
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
        for (Deposit dep : store.all().values()) {
            if (store.isRevealed(pid, dep.id())) continue;
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
            Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
            if (mode != Config.RevealMode.ON_DISCOVERY) continue;
            if (!withinAnyChunk(current, dep, discoveryRadiusSq)) continue;
            if (CoedepositsNetwork.revealAndNotify(lvl, player, dep)) {
                Coedeposits.LOGGER.info("[coedeposits] {} discovered {} via walk",
                        player.getName().getString(), dep.name());
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
