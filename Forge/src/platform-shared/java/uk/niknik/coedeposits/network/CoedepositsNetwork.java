package uk.niknik.coedeposits.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Forge 1.20.1 network plumbing. Registers the three play-to-client messages
 * on a {@link SimpleChannel} and exposes server-side broadcast helpers.
 *
 * <p><b>1.20.1 line:</b> Forge {@code SimpleChannel}/SimpleImpl instead of
 * NeoForge's {@code PayloadRegistrar}/{@code PacketDistributor.sendToPlayer}.
 * Each message carries its own {@code encode}/{@code decode}/{@code handle};
 * registration order ({@link #register}) defines the wire index, so it must
 * stay stable across versions. Client receive handling lives in
 * {@link uk.niknik.coedeposits.client.ClientPayloadHandler}.
 *
 * <p>{@link #buildVisible} is the canonical reveal-mode filter used by every
 * server-to-client sync — per-player-reveal deposits (ON_DISCOVERY /
 * ON_PROSPECT) are excluded for players who haven't unlocked them yet.
 *
 * <p><b>Fabric TODO:</b> the {@code SimpleChannel} bits are Forge-specific;
 * when the Fabric 1.20.1 line lands, the send/register methods move behind a
 * {@code *Platform} indirection (the reveal logic + {@link DepositSnapshot}
 * serialization stay loader-agnostic).
 */
public final class CoedepositsNetwork {
    private CoedepositsNetwork() {}

    private static final String PROTOCOL = "1";

    /** Mod network channel. Created at class-load (during mod construction). */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Coedeposits.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    /**
     * Register all three play-to-client messages. Called once from the mod
     * constructor. The integer index is the wire id — keep the order stable.
     */
    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, DepositSyncPayload.class,
                DepositSyncPayload::encode, DepositSyncPayload::decode, DepositSyncPayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, DepositDiscoveryPayload.class,
                DepositDiscoveryPayload::encode, DepositDiscoveryPayload::decode, DepositDiscoveryPayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, DepositRemovePayload.class,
                DepositRemovePayload::encode, DepositRemovePayload::decode, DepositRemovePayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Broadcast a chat-notification packet to every online player. */
    public static void broadcastDiscovery(MinecraftServer server, DepositDiscoveryPayload payload) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), payload);
        }
    }

    /** Send a chat-notification packet to a single player. Used by per-player reveal triggers. */
    public static void sendDiscovery(ServerPlayer player, DepositDiscoveryPayload payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    /** Send the bulk cache state to a single player. Used on player login. */
    public static void sendSync(ServerPlayer player, DepositSyncPayload payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
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
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), payload);
        }
    }

    /**
     * Push the bulk cache state to every online player without filtering.
     * Used only by {@link uk.niknik.coedeposits.gen.CoedepositsPicker#broadcastDiscovery}
     * for non-per-player reveal modes.
     */
    public static void broadcastSync(MinecraftServer server, DepositSyncPayload payload) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), payload);
        }
    }

    /**
     * Per-player reveal-aware bulk sync. For every online player on
     * {@code lvl}'s dimension, builds the list of deposits they're allowed to
     * see ({@link #buildVisible}) and dispatches it as a single
     * {@link DepositSyncPayload}. Skips players whose visible list is empty.
     */
    public static void broadcastSyncFiltered(MinecraftServer server, ServerLevel lvl, Collection<Deposit> deposits) {
        DepositSavedData store = DepositSavedData.get(lvl);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.serverLevel().dimension().equals(lvl.dimension())) continue;
            List<DepositSnapshot> visible = buildVisible(lvl, store, p, deposits);
            if (visible.isEmpty()) continue;
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new DepositSyncPayload(visible));
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
