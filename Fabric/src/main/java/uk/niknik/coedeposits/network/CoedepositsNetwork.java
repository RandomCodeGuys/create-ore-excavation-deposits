package uk.niknik.coedeposits.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        // Server→client channels need no registration on the send side.
        // Client→server: the world-map share keybind sends DepositSharePayload here.
        ServerPlayNetworking.registerGlobalReceiver(DepositSharePayload.CHANNEL,
                (server, player, handler, buf, responseSender) -> {
                    UUID id = buf.readUUID();
                    server.execute(() -> handleShareRequest(player, id));
                });
    }

    /**
     * Server-side: a client pressed the share keybind on a hovered deposit.
     * Validate the deposit exists and the sender can see it (anti-probe), then
     * broadcast the clickable chat offer.
     */
    public static void handleShareRequest(ServerPlayer sp, UUID depositId) {
        ServerLevel lvl = sp.serverLevel();
        DepositSavedData store = DepositSavedData.get(lvl);
        Deposit dep = store.all().get(depositId);
        if (dep == null || !canSee(store, sp, dep)) return;
        broadcastShareOffer(lvl, sp, dep);
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
            if (!visible.isEmpty()) {
                sendSyncBatched(p, visible);
            }
            // Client MERGES syncs and never drops on its own, so hiding needs an
            // explicit removal each sweep — covers GLOBAL→PER_PLAYER un-share,
            // leaving a TEAM, scope flips. No-op for ids a client never cached.
            Set<UUID> visIds = new HashSet<>();
            for (DepositSnapshot s : visible) visIds.add(s.id());
            List<UUID> hide = new ArrayList<>();
            for (Deposit d : deposits) {
                if (visIds.contains(d.id())) continue;
                DepositType type = Coedeposits.DEPOSIT_TYPES.get(d.typeId());
                Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
                if (mode.isPerPlayer()) hide.add(d.id());
            }
            if (!hide.isEmpty()) {
                sendRemovePacket(p, new DepositRemovePayload(hide));
            }
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
        // ── Step 1: claim the reveal (idempotent), honouring reveal_scope ───
        // Always record the discoverer's PERSONAL reveal (so a later switch to
        // PER_PLAYER keeps self-found deposits); bail when there's nothing new.
        DepositSavedData store = DepositSavedData.get(lvl);
        Config.RevealScope scope = Config.REVEAL_SCOPE.get();
        boolean global = scope == Config.RevealScope.GLOBAL;
        Set<UUID> mates = scope == Config.RevealScope.TEAM
                ? uk.niknik.coedeposits.compat.TeamBridge.teammatesOf(player)
                : Set.of();
        boolean wasVisible = store.isRevealed(player.getUUID(), dep.id())
                || (scope == Config.RevealScope.TEAM && anyRevealed(store, mates, dep.id()));
        store.reveal(player.getUUID(), dep.id());
        if (global) {
            if (!store.revealGlobal(dep.id())) return false;  // announce on first global reveal only
        } else if (wasVisible) {
            return false;
        }

        // ── Step 2: build the snapshot + discovery packet ───────────────────
        String name = DepositType.displayNameOf(Coedeposits.DEPOSIT_TYPES.get(dep.typeId()), dep.typeId());
        BlockPos pos = new BlockPos(
                dep.core().getMiddleBlockX(),
                lvl.getSharedSpawnPos().getY(),
                dep.core().getMiddleBlockZ());
        DepositDiscoveryPayload discovery =
                new DepositDiscoveryPayload(name, pos, dep.typeId(), player.getName().getString());
        DepositSnapshot snap = DepositSnapshot.fromDeposit(lvl, dep);

        // ── Step 3: deliver — everyone (GLOBAL), the team (TEAM), or just this player ──
        if (global) {
            for (ServerPlayer p : lvl.getServer().getPlayerList().getPlayers()) {
                if (!p.serverLevel().dimension().equals(lvl.dimension())) continue;
                sendSync(p, new DepositSyncPayload(List.of(snap)));
                sendDiscovery(p, discovery);
            }
        } else if (scope == Config.RevealScope.TEAM) {
            sendSync(player, new DepositSyncPayload(List.of(snap)));
            sendDiscovery(player, discovery);
            for (UUID mate : mates) {
                ServerPlayer mp = lvl.getServer().getPlayerList().getPlayer(mate);
                if (mp != null && mp.serverLevel().dimension().equals(lvl.dimension())) {
                    sendSync(mp, new DepositSyncPayload(List.of(snap)));
                    sendDiscovery(mp, discovery);
                }
            }
        } else {
            sendSync(player, new DepositSyncPayload(List.of(snap)));
            sendDiscovery(player, discovery);
        }
        return true;
    }

    /**
     * Can this player currently see {@code dep}? Mirrors {@link #buildVisible}'s
     * per-deposit predicate. Gates the share actions — you can only share what
     * you can see.
     */
    public static boolean canSee(DepositSavedData store, ServerPlayer player, Deposit dep) {
        DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
        Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
        if (!mode.isPerPlayer()) return true;
        if (store.isRevealed(player.getUUID(), dep.id())) return true;
        Config.RevealScope scope = Config.REVEAL_SCOPE.get();
        if (scope == Config.RevealScope.GLOBAL) return store.isGloballyRevealed(dep.id());
        if (scope == Config.RevealScope.TEAM) {
            return anyRevealed(store,
                    uk.niknik.coedeposits.compat.TeamBridge.teammatesOf(player), dep.id());
        }
        return false;
    }

    /**
     * Reveal {@code dep} for {@code to} because {@code from} shared it. Idempotent:
     * false when the target already had it. On a fresh share, pushes the marker
     * sync + the discovery chat line (with {@code %player%} = the sharer).
     */
    public static boolean shareWith(ServerLevel lvl, String sharerName, ServerPlayer to, Deposit dep) {
        DepositSavedData store = DepositSavedData.get(lvl);
        if (!store.reveal(to.getUUID(), dep.id())) return false;
        sendSync(to, new DepositSyncPayload(List.of(DepositSnapshot.fromDeposit(lvl, dep))));
        BlockPos pos = new BlockPos(
                dep.core().getMiddleBlockX(),
                lvl.getSharedSpawnPos().getY(),
                dep.core().getMiddleBlockZ());
        sendDiscovery(to, new DepositDiscoveryPayload(
                DepositType.displayNameOf(Coedeposits.DEPOSIT_TYPES.get(dep.typeId()), dep.typeId()),
                pos, dep.typeId(), sharerName));
        return true;
    }

    /**
     * Broadcast a clickable [+ Add to map] offer to every player in the dimension.
     * Clicking runs {@code /coedeposits accept <id>} ({@link #shareWith}).
     */
    public static void broadcastShareOffer(ServerLevel lvl, ServerPlayer sharer, Deposit dep) {
        String name = DepositType.displayNameOf(Coedeposits.DEPOSIT_TYPES.get(dep.typeId()), dep.typeId());
        int x = dep.core().getMiddleBlockX();
        int z = dep.core().getMiddleBlockZ();
        net.minecraft.network.chat.MutableComponent line = net.minecraft.network.chat.Component
                .literal(sharer.getName().getString())
                .withStyle(net.minecraft.ChatFormatting.GREEN)
                .append(net.minecraft.network.chat.Component.literal(" shares ")
                        .withStyle(net.minecraft.ChatFormatting.GRAY))
                .append(net.minecraft.network.chat.Component.literal(name)
                        .withStyle(net.minecraft.ChatFormatting.GOLD))
                .append(net.minecraft.network.chat.Component.literal(" at ")
                        .withStyle(net.minecraft.ChatFormatting.GRAY))
                .append(net.minecraft.network.chat.Component.literal(String.format("[%d, ~, %d]", x, z))
                        .withStyle(net.minecraft.ChatFormatting.YELLOW))
                .append(net.minecraft.network.chat.Component.literal("  [✚ Add to map]")
                        .withStyle(s -> s.withColor(net.minecraft.ChatFormatting.AQUA)
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                        net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                        "/coedeposits accept " + dep.id()))
                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                        net.minecraft.network.chat.Component.literal(
                                                "Add this deposit to your map")))));
        for (ServerPlayer p : lvl.getServer().getPlayerList().getPlayers()) {
            if (!p.serverLevel().dimension().equals(lvl.dimension())) continue;
            p.sendSystemMessage(line);
        }
    }

    /**
     * Chat-only "you found it" notice for ON_PROXIMITY — sent to one player the
     * first time they come within the proximity radius. No visibility change.
     */
    public static void sendProximityNotice(ServerLevel lvl, ServerPlayer player, Deposit dep) {
        BlockPos pos = new BlockPos(
                dep.core().getMiddleBlockX(),
                lvl.getSharedSpawnPos().getY(),
                dep.core().getMiddleBlockZ());
        sendDiscovery(player, new DepositDiscoveryPayload(
                DepositType.displayNameOf(Coedeposits.DEPOSIT_TYPES.get(dep.typeId()), dep.typeId()),
                pos, dep.typeId(), player.getName().getString()));
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
        Config.RevealScope scope = Config.REVEAL_SCOPE.get();
        boolean globalScope = scope == Config.RevealScope.GLOBAL;
        // TEAM visibility is LIVE: visible when the player or any CURRENT teammate
        // revealed it (joining shares past finds, leaving un-shares them).
        Set<UUID> mates = scope == Config.RevealScope.TEAM
                ? uk.niknik.coedeposits.compat.TeamBridge.teammatesOf(player)
                : Set.of();
        for (Deposit d : deposits) {
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(d.typeId());
            Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
            boolean seen = store.isRevealed(pid, d.id())
                    || (globalScope && store.isGloballyRevealed(d.id()))
                    || (!mates.isEmpty() && anyRevealed(store, mates, d.id()));
            if (mode.isPerPlayer() && !seen) continue;
            out.add(DepositSnapshot.fromDeposit(lvl, d));
        }
        return out;
    }

    /** True when ANY of {@code players} has a personal reveal record for {@code depositId}. */
    private static boolean anyRevealed(DepositSavedData store, Set<UUID> players, UUID depositId) {
        for (UUID p : players) {
            if (store.isRevealed(p, depositId)) return true;
        }
        return false;
    }
}
