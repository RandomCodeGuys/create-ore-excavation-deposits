package uk.niknik.coedeposits.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.client.DepositClientCache;
import uk.niknik.coedeposits.compat.xaero.XaeroBridge;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * NeoForge network plumbing for the mod. Registers both packet types
 * (discovery + bulk sync) and exposes server-side broadcast helpers.
 *
 * <p>{@link DepositDiscoveryPayload} triggers chat/Xaero waypoint on the
 * client; {@link DepositSyncPayload} keeps the world-map cache in sync.
 *
 * <p>{@link #buildVisible} is the canonical reveal-mode filter used by every
 * server-to-client sync — per-player-reveal deposits (ON_DISCOVERY /
 * ON_PROSPECT) are excluded for players who haven't unlocked them yet.
 */
@EventBusSubscriber(modid = Coedeposits.MODID)
public final class CoedepositsNetwork {
    private CoedepositsNetwork() {}

    /** Mod-bus hook — registers all payload codecs + client-side handlers at boot. */
    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar(Coedeposits.MODID).versioned("1");
        reg.playToClient(
                DepositDiscoveryPayload.TYPE,
                DepositDiscoveryPayload.STREAM_CODEC,
                CoedepositsNetwork::handleDiscovery);
        reg.playToClient(
                DepositSyncPayload.TYPE,
                DepositSyncPayload.STREAM_CODEC,
                CoedepositsNetwork::handleSync);
        reg.playToClient(
                DepositRemovePayload.TYPE,
                DepositRemovePayload.STREAM_CODEC,
                CoedepositsNetwork::handleRemoval);
    }

    /** Client-side: enqueue chat + Xaero waypoint attempt onto main thread. */
    private static void handleDiscovery(DepositDiscoveryPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> XaeroBridge.onDepositDiscovered(payload));
    }

    /** Client-side: merge incoming snapshots into the render-thread cache. */
    private static void handleSync(DepositSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DepositClientCache.applyUpdate(payload.deposits()));
    }

    /** Client-side: drop removed deposits from the render-thread cache. */
    private static void handleRemoval(DepositRemovePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DepositClientCache.applyRemoval(payload.ids()));
    }

    /** Broadcast a chat-notification packet to every online player. */
    public static void broadcastDiscovery(MinecraftServer server, DepositDiscoveryPayload payload) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, payload);
        }
    }

    /** Send a chat-notification packet to a single player. Used by per-player reveal triggers. */
    public static void sendDiscovery(ServerPlayer player, DepositDiscoveryPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** Send the bulk cache state to a single player. Used on player login. */
    public static void sendSync(ServerPlayer player, DepositSyncPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * Tell every online player on the given level that a batch of deposits
     * is gone. Used by {@code /coedeposits delete *} so the world-map overlay
     * stops drawing them without waiting for the next periodic re-sync.
     */
    public static void broadcastRemoval(net.minecraft.server.level.ServerLevel lvl, java.util.List<java.util.UUID> ids) {
        // Empty-id short-circuit — `/coedeposits regenerate` calls this with
        // the result of removeAll(), which returns an empty list when there
        // was nothing to wipe. Skip the per-player loop in that case.
        if (ids.isEmpty()) return;
        DepositRemovePayload payload = new DepositRemovePayload(ids);
        for (ServerPlayer p : lvl.getServer().getPlayerList().getPlayers()) {
            // Removal is dimension-scoped — only players currently IN the
            // deposit's dimension hold these uuids in their cache. A player
            // in the nether doesn't need to hear about overworld deletes
            // (they get a fresh sync when they portal back).
            if (!p.serverLevel().dimension().equals(lvl.dimension())) continue;
            PacketDistributor.sendToPlayer(p, payload);
        }
    }

    /**
     * Push the bulk cache state to every online player without filtering.
     * Used only by {@link uk.niknik.coedeposits.gen.CoedepositsPicker#broadcastDiscovery}
     * for non-per-player reveal modes — callers that want reveal-aware
     * delivery should use {@link #broadcastSyncFiltered} instead.
     */
    public static void broadcastSync(MinecraftServer server, DepositSyncPayload payload) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, payload);
        }
    }

    /**
     * Per-player reveal-aware bulk sync. For every online player on
     * {@code lvl}'s dimension, builds the list of deposits they're allowed
     * to see ({@link #buildVisible}) and dispatches it as a single
     * {@link DepositSyncPayload}. Skips players whose visible list is empty.
     */
    public static void broadcastSyncFiltered(MinecraftServer server, ServerLevel lvl, Collection<Deposit> deposits) {
        // ── Step 1: snapshot the SavedData for visibility lookups ───────────
        // buildVisible needs it to check per-player reveal records. Pulled
        // once outside the player loop to avoid repeated DataStorage lookups.
        DepositSavedData store = DepositSavedData.get(lvl);

        // ── Step 2: per-player tailored sync ────────────────────────────────
        // Each player gets a different snapshot list because reveal state is
        // per-player. We skip:
        //   - players in other dimensions (their cache holds other dims' data)
        //   - players whose visible list is empty (no point sending an empty packet)
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.serverLevel().dimension().equals(lvl.dimension())) continue;
            var visible = buildVisible(lvl, store, p, deposits);
            if (visible.isEmpty()) continue;
            PacketDistributor.sendToPlayer(p, new DepositSyncPayload(visible));
        }
    }

    /**
     * Mark a deposit revealed for this player, then push the one-shot sync +
     * chat notification. No-ops when {@link DepositSavedData#reveal} returns
     * false (deposit already revealed for this player), so the listeners can
     * call this every tick without spam.
     *
     * @return  true when a fresh reveal happened (caller may want to log)
     */
    public static boolean revealAndNotify(ServerLevel lvl, ServerPlayer player, Deposit dep) {
        // ── Step 1: claim the reveal (idempotent), honouring reveal_scope ───
        // GLOBAL marks the deposit revealed for everyone; PER_PLAYER for just
        // this player. Either way we bail when it was already revealed, so the
        // ON_DISCOVERY / ON_PROSPECT listeners can call this every tick without
        // spamming.
        DepositSavedData store = DepositSavedData.get(lvl);
        boolean global = Config.REVEAL_SCOPE.get() == Config.RevealScope.GLOBAL;
        if (global) {
            if (!store.revealGlobal(dep.id())) return false;
        } else {
            if (!store.reveal(player.getUUID(), dep.id())) return false;
        }

        // ── Step 2: build the snapshot + discovery packet ───────────────────
        // Coord Y uses spawn Y so the `/tp` suggestion lands at a sensible
        // elevation rather than the chunk-core's possibly-weird Y. %player% is
        // the discoverer's name (used by GLOBAL "X discovered Y" formats).
        String name = DepositType.displayNameOf(Coedeposits.DEPOSIT_TYPES.get(dep.typeId()), dep.typeId());
        BlockPos pos = new BlockPos(
                dep.core().getMiddleBlockX(),
                lvl.getSharedSpawnPos().getY(),
                dep.core().getMiddleBlockZ());
        DepositDiscoveryPayload discovery =
                new DepositDiscoveryPayload(name, pos, dep.typeId(), player.getName().getString());
        DepositSnapshot snap = DepositSnapshot.fromDeposit(lvl, dep);

        // ── Step 3: deliver — to everyone (GLOBAL) or just this player ──────
        // GLOBAL: the deposit is now globally revealed, so push the marker + chat
        // to every player in this dimension. Clients with discovery_chat off still
        // get the marker (the chat line is suppressed client-side).
        if (global) {
            for (ServerPlayer p : lvl.getServer().getPlayerList().getPlayers()) {
                if (!p.serverLevel().dimension().equals(lvl.dimension())) continue;
                sendSync(p, new DepositSyncPayload(List.of(snap)));
                sendDiscovery(p, discovery);
            }
        } else {
            sendSync(player, new DepositSyncPayload(List.of(snap)));
            sendDiscovery(player, discovery);
        }
        return true;
    }

    /**
     * Reveal-mode filter — produces the snapshot list a specific player is
     * allowed to see, given the current SavedData and reveal state. Used by
     * {@link #broadcastSyncFiltered}, {@link uk.niknik.coedeposits.event.PlayerJoinSyncListener}
     * and the discovery/prospect listeners.
     *
     * <ul>
     *   <li>ALWAYS / ON_PROXIMITY — always included; the renderer applies the
     *       proximity filter at draw time so the client must hold the data.</li>
     *   <li>ON_DISCOVERY / ON_PROSPECT — included only if {@code store.isRevealed(player, deposit)}.</li>
     * </ul>
     */
    public static List<DepositSnapshot> buildVisible(
            ServerLevel lvl, DepositSavedData store, ServerPlayer player, Collection<Deposit> deposits) {
        // Pre-sized so the loop doesn't reallocate. Worst case all deposits
        // pass the filter — under-sizing then growing would cost more than
        // a slightly oversized backing array.
        List<DepositSnapshot> out = new ArrayList<>(deposits.size());
        var pid = player.getUUID();
        for (Deposit d : deposits) {
            // Look up the type's effective reveal mode. type may be null for
            // legacy SavedData entries whose typeId no longer exists in the
            // registry — fall back to the global default to keep them visible
            // rather than silently hiding them forever.
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(d.typeId());
            Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
            // Only the per-player modes (ON_DISCOVERY / ON_PROSPECT) gate
            // visibility on the reveal record. ALWAYS and ON_PROXIMITY always
            // include the snapshot — proximity filtering happens client-side
            // at render time, not in this server-side filter. A GLOBAL-scope
            // reveal (store.isGloballyRevealed) makes it visible to everyone.
            if (mode.isPerPlayer()
                    && !store.isGloballyRevealed(d.id())
                    && !store.isRevealed(pid, d.id())) continue;
            out.add(DepositSnapshot.fromDeposit(lvl, d));
        }
        return out;
    }
}
