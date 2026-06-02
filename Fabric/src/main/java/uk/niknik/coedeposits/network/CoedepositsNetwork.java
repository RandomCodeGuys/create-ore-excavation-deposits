package uk.niknik.coedeposits.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Fabric 1.20.1 network plumbing. Drop-in replacement for the platform-shared
 * (Forge {@code SimpleChannel}) {@code CoedepositsNetwork}: identical package,
 * class name, and public method signatures (the callers in
 * {@code CoedepositsPicker} / the event listeners / the command compile against
 * exactly these), but the wire send uses Fabric's
 * {@code ServerPlayNetworking.send(player, channel, buf)} instead of
 * {@code SimpleChannel.send(PacketDistributor...)}.
 *
 * <p>All three play-to-client payloads ({@link DepositSyncPayload} /
 * {@link DepositDiscoveryPayload} / {@link DepositRemovePayload}) carry a stable
 * channel id; the matching client receivers are registered in
 * {@code uk.niknik.coedeposits.client.CoedepositsFabricClient}.
 *
 * <p>{@link #buildVisible} is the canonical reveal-mode filter used by every
 * server-to-client sync (verbatim from the Forge version — pure
 * loader-agnostic logic).
 */
public final class CoedepositsNetwork {
    private CoedepositsNetwork() {}

    /**
     * No-op on Fabric — payload channels need no central registration on the
     * SEND side (the client registers receivers in
     * {@code CoedepositsFabricClient}). Kept so the mod-init wiring can call
     * {@code CoedepositsNetwork.register()} symmetrically with the Forge line.
     */
    public static void register() {
        // Fabric server→client channels require no server-side registration.
    }

    // ── Fabric send helpers ──────────────────────────────────────────────────

    private static void send(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
        ServerPlayNetworking.send(player, channel, buf);
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static void sendSyncPacket(ServerPlayer player, DepositSyncPayload payload) {
        FriendlyByteBuf buf = buffer();
        payload.encode(buf);
        send(player, DepositSyncPayload.CHANNEL, buf);
    }

    private static void sendDiscoveryPacket(ServerPlayer player, DepositDiscoveryPayload payload) {
        FriendlyByteBuf buf = buffer();
        payload.encode(buf);
        send(player, DepositDiscoveryPayload.CHANNEL, buf);
    }

    private static void sendRemovePacket(ServerPlayer player, DepositRemovePayload payload) {
        FriendlyByteBuf buf = buffer();
        payload.encode(buf);
        send(player, DepositRemovePayload.CHANNEL, buf);
    }

    // ── Public broadcast / send API (signature-compatible with the Forge version) ──

    /** Broadcast a chat-notification packet to every online player. */
    public static void broadcastDiscovery(MinecraftServer server, DepositDiscoveryPayload payload) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendDiscoveryPacket(p, payload);
        }
    }

    /** Send a chat-notification packet to a single player. Used by per-player reveal triggers. */
    public static void sendDiscovery(ServerPlayer player, DepositDiscoveryPayload payload) {
        sendDiscoveryPacket(player, payload);
    }

    /** Send the bulk cache state to a single player. Used on player login. */
    public static void sendSync(ServerPlayer player, DepositSyncPayload payload) {
        sendSyncPacket(player, payload);
    }

    // Fabric custom-payload packets are subject to the same vanilla 1 MiB cap
    // ("Payload may not be larger than 1048576 bytes"). A world with hundreds of
    // deposits overflows one DepositSyncPayload, so keep batches well under the
    // cap (the estimate over-counts; threshold leaves ~300 KiB headroom).
    private static final int MAX_SYNC_PAYLOAD_BYTES = 700 * 1024;

    /** Conservative encoded size of one snapshot — dominated by its 4 per-chunk lists. */
    private static int estimateSnapshotBytes(DepositSnapshot s) {
        return 384 + s.packedChunks().size() * 32;
    }

    /**
     * Bulk sync to a single player, split into sub-1-MiB {@link DepositSyncPayload}
     * packets so large worlds don't trip the payload cap. The client merges
     * batches per-UUID ({@link uk.niknik.coedeposits.client.DepositClientCache#applyUpdate}),
     * so the split is lossless. Use this for any multi-deposit sync (login / re-sync).
     */
    public static void sendSyncBatched(ServerPlayer player, List<DepositSnapshot> snapshots) {
        if (snapshots.isEmpty()) return;
        List<DepositSnapshot> batch = new ArrayList<>();
        int batchBytes = 0;
        for (DepositSnapshot s : snapshots) {
            int sz = estimateSnapshotBytes(s);
            if (!batch.isEmpty() && batchBytes + sz > MAX_SYNC_PAYLOAD_BYTES) {
                sendSync(player, new DepositSyncPayload(batch));
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(s);
            batchBytes += sz;
        }
        if (!batch.isEmpty()) sendSync(player, new DepositSyncPayload(batch));
    }

    /**
     * Tell every online player on the given level that a batch of deposits is
     * gone. Removal is dimension-scoped — only players currently IN the
     * deposit's dimension hold these uuids in their cache.
     */
    public static void broadcastRemoval(ServerLevel lvl, List<UUID> ids) {
        // Empty-id short-circuit — regenerate calls this with removeAll()'s
        // result, which is empty when there was nothing to wipe.
        if (ids.isEmpty()) return;
        DepositRemovePayload payload = new DepositRemovePayload(ids);
        for (ServerPlayer p : lvl.getServer().getPlayerList().getPlayers()) {
            if (!p.serverLevel().dimension().equals(lvl.dimension())) continue;
            sendRemovePacket(p, payload);
        }
    }

    /**
     * Push the bulk cache state to every online player without filtering.
     * Used only by {@link uk.niknik.coedeposits.gen.CoedepositsPicker#broadcastDiscovery}
     * for non-per-player reveal modes.
     */
    public static void broadcastSync(MinecraftServer server, DepositSyncPayload payload) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendSyncPacket(p, payload);
        }
    }

    /**
     * Per-player reveal-aware bulk sync. For every online player on
     * {@code lvl}'s dimension, builds the list of deposits they're allowed to
     * see ({@link #buildVisible}) and dispatches it as one or more
     * {@link DepositSyncPayload}. Skips players whose visible list is empty.
     */
    public static void broadcastSyncFiltered(MinecraftServer server, ServerLevel lvl, Collection<Deposit> deposits) {
        DepositSavedData store = DepositSavedData.get(lvl);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.serverLevel().dimension().equals(lvl.dimension())) continue;
            List<DepositSnapshot> visible = buildVisible(lvl, store, p, deposits);
            if (visible.isEmpty()) continue;
            sendSyncBatched(p, visible);
        }
    }

    /**
     * Mark a deposit revealed for this player, then push the one-shot sync +
     * chat notification. No-ops when {@link DepositSavedData#reveal} returns
     * false (deposit already revealed), so listeners can call this every tick
     * without spam.
     *
     * @return  true when a fresh reveal happened (caller may want to log)
     */
    public static boolean revealAndNotify(ServerLevel lvl, ServerPlayer player, Deposit dep) {
        // ── Step 1: claim the reveal (idempotent) ───────────────────────────
        DepositSavedData store = DepositSavedData.get(lvl);
        if (!store.reveal(player.getUUID(), dep.id())) return false;

        // ── Step 2: push the deposit snapshot to the player's cache ─────────
        sendSync(player, new DepositSyncPayload(List.of(DepositSnapshot.fromDeposit(lvl, dep))));

        // ── Step 3: chat-style discovery packet (gold message + waypoint) ──
        // Coord Y uses spawn Y so the resulting /tp suggestion is sensible.
        BlockPos pos = new BlockPos(
                dep.core().getMiddleBlockX(),
                lvl.getSharedSpawnPos().getY(),
                dep.core().getMiddleBlockZ());
        sendDiscovery(player, new DepositDiscoveryPayload(dep.name(), pos, dep.typeId()));
        return true;
    }

    /**
     * Reveal-mode filter — produces the snapshot list a specific player is
     * allowed to see, given the current SavedData and reveal state.
     *
     * <ul>
     *   <li>ALWAYS / ON_PROXIMITY — always included; the renderer applies the
     *       proximity filter at draw time so the client must hold the data.</li>
     *   <li>ON_DISCOVERY / ON_PROSPECT — included only if {@code store.isRevealed}.</li>
     * </ul>
     */
    public static List<DepositSnapshot> buildVisible(
            ServerLevel lvl, DepositSavedData store, ServerPlayer player, Collection<Deposit> deposits) {
        List<DepositSnapshot> out = new ArrayList<>(deposits.size());
        UUID pid = player.getUUID();
        for (Deposit d : deposits) {
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(d.typeId());
            Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
            if (mode.isPerPlayer() && !store.isRevealed(pid, d.id())) continue;
            out.add(DepositSnapshot.fromDeposit(lvl, d));
        }
        return out;
    }
}
