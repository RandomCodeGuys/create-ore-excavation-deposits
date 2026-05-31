package uk.niknik.coedeposits.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

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
 *       around their current position into {@link ProspectScanQueue}.</li>
 *   <li><b>ON_DISCOVERY reveal</b> — for every saved deposit whose effective
 *       reveal mode is {@link Config.RevealMode#ON_DISCOVERY}, checks whether
 *       the player is now within {@link Config#DISCOVERY_RADIUS_BLOCKS} of any
 *       of its chunks and, if so, marks it revealed for that player.</li>
 * </ol>
 *
 * <p>This listener also owns the per-tick {@link ProspectScanQueue} materialize
 * drain (every tick at {@code prospect_chunks_per_tick} budget) — the single
 * place it happens, so it doesn't double-drain with {@link PickerInstaller}.
 *
 * <p><b>1.20.1 line:</b> Forge {@code @Mod.EventBusSubscriber} +
 * {@code TickEvent.ServerTickEvent} (phase END); the server handle comes from
 * {@link ServerLifecycleHooks#getCurrentServer()} (Forge 1.20.1's
 * {@code ServerTickEvent} has no portable {@code getServer()}).
 */
@Mod.EventBusSubscriber(modid = Coedeposits.MODID)
public final class PlayerRoamProspectListener {
    private PlayerRoamProspectListener() {}

    /** How often we re-check player positions for scan-enqueue + ON_DISCOVERY reveal — 200 ticks = 10 seconds. */
    private static final int CHECK_INTERVAL_TICKS = 200;

    /** Per-player last position where we enqueued a scan; reset when player rejoins. */
    private static final Map<UUID, BlockPos> lastScanCenter = new HashMap<>();

    /**
     * Tick handler — two cadences: every tick drains the materialize queue;
     * every 200 ticks sweeps roam-enqueue + ON_DISCOVERY reveals.
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        // Per-tick: drain materialize queue for every managed level. Cheap when
        // idle so unconditional invocation is fine.
        int budget = Config.PROSPECT_CHUNKS_PER_TICK.get();
        for (ServerLevel lvl : PickerInstaller.enabledLevels(server)) {
            ProspectScanQueue.INSTANCE.tickMaterialize(lvl, budget);
        }

        if (server.getTickCount() % CHECK_INTERVAL_TICKS != 0) return;
        int prospectRadius = Config.PROSPECT_RADIUS.get();
        int discoveryRadius = Config.DISCOVERY_RADIUS_BLOCKS.get();
        long discoveryRadiusSq = (long) discoveryRadius * discoveryRadius;
        long prospectTriggerSq = (long) (prospectRadius / 2) * (prospectRadius / 2);

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerLevel lvl = p.serverLevel();
            // Skip players in dimensions we don't manage.
            if (!Config.isDimensionEnabled(lvl.dimension().location())) continue;
            BlockPos current = p.blockPosition();

            // Job 1: incremental prospect scan (enqueue — actual work is async).
            if (prospectRadius > 0) {
                BlockPos last = lastScanCenter.get(p.getUUID());
                if (last == null || distSq(last, current) > prospectTriggerSq) {
                    ProspectScanQueue.INSTANCE.enqueue(lvl, current, prospectRadius);
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
            // Filter 1: skip deposits this player already discovered.
            if (store.isRevealed(pid, dep.id())) continue;
            // Filter 2: skip non-ON_DISCOVERY modes.
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
            Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
            if (mode != Config.RevealMode.ON_DISCOVERY) continue;
            // Filter 3: spatial proximity to any of the deposit's chunks.
            if (!withinAnyChunk(current, dep, discoveryRadiusSq)) continue;
            // Trigger the reveal — revealAndNotify is idempotent.
            if (CoedepositsNetwork.revealAndNotify(lvl, player, dep)) {
                if (Config.LOG_DISCOVERY.get()) {
                    Coedeposits.LOGGER.info("[coedeposits] {} discovered {} via walk",
                            player.getName().getString(), dep.name());
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

    /** XZ-plane squared distance between two BlockPos. */
    private static long distSq(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Drop the player's last-scan-centre entry on logout. Per-player
     * <em>reveal</em> history in {@link DepositSavedData} deliberately survives
     * logouts so discovered map markers don't reset each session.
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            lastScanCenter.remove(sp.getUUID());
        }
    }
}
