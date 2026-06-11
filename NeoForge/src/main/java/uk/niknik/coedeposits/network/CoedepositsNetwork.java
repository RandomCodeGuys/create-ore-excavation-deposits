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
        reg.playToServer(
                DepositSharePayload.TYPE,
                DepositSharePayload.STREAM_CODEC,
                CoedepositsNetwork::handleShareRequest);
    }

    /**
     * Server-side: a client pressed the share keybind on a hovered deposit.
     * Validate the deposit exists and the sender can see it (anti-probe — a
     * hacked client must not enumerate hidden deposits by spamming UUIDs),
     * then broadcast the clickable chat offer.
     */
    private static void handleShareRequest(DepositSharePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            ServerLevel lvl = sp.serverLevel();
            DepositSavedData store = DepositSavedData.get(lvl);
            Deposit dep = store.all().get(payload.depositId());
            if (dep == null || !canSee(store, sp, dep)) return;
            broadcastShareOffer(lvl, sp, dep);
        });
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

        // ── Step 2: per-player tailored sync + reconcile ────────────────────
        // Each player gets a different snapshot list because reveal state is
        // per-player. Players in other dimensions are skipped (their cache holds
        // other dims' data). Because the client MERGES syncs and never drops on
        // its own, hiding needs an explicit removal: every per-player-mode
        // deposit NOT in the player's visible set gets a removal each sweep.
        // That covers all the live transitions — GLOBAL→PER_PLAYER un-sharing,
        // leaving a TEAM, scope flips. Removals for ids a client never cached
        // are a cheap no-op.
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.serverLevel().dimension().equals(lvl.dimension())) continue;
            var visible = buildVisible(lvl, store, p, deposits);
            if (!visible.isEmpty()) {
                PacketDistributor.sendToPlayer(p, new DepositSyncPayload(visible));
            }
            java.util.Set<java.util.UUID> visIds = new java.util.HashSet<>();
            for (DepositSnapshot s : visible) visIds.add(s.id());
            List<java.util.UUID> hide = new ArrayList<>();
            for (Deposit d : deposits) {
                if (visIds.contains(d.id())) continue;
                DepositType type = Coedeposits.DEPOSIT_TYPES.get(d.typeId());
                Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
                if (mode.isPerPlayer()) hide.add(d.id());
            }
            if (!hide.isEmpty()) {
                PacketDistributor.sendToPlayer(p, new DepositRemovePayload(hide));
            }
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
        // Always record the discoverer's PERSONAL reveal — so if reveal_scope is
        // later switched to PER_PLAYER, players keep the deposits they found
        // themselves (GLOBAL just layers "visible to everyone" on top). We bail
        // when there's nothing new to announce, so the ON_DISCOVERY / ON_PROSPECT
        // listeners can call this every tick without spamming.
        DepositSavedData store = DepositSavedData.get(lvl);
        Config.RevealScope scope = Config.REVEAL_SCOPE.get();
        boolean global = scope == Config.RevealScope.GLOBAL;
        java.util.Set<java.util.UUID> mates = scope == Config.RevealScope.TEAM
                ? uk.niknik.coedeposits.compat.TeamBridge.teammatesOf(player)
                : java.util.Set.of();
        // Was the deposit already visible to this player BEFORE this trigger?
        // (personally revealed, team-visible via a teammate's record, or globally
        // revealed). If so there's nothing to announce — but we still record the
        // personal find below, so the player keeps it after leaving the team.
        boolean wasVisible = store.isRevealed(player.getUUID(), dep.id())
                || (scope == Config.RevealScope.TEAM && anyRevealed(store, mates, dep.id()));
        store.reveal(player.getUUID(), dep.id());
        if (global) {
            // Announce on the FIRST global reveal only — once globally revealed,
            // every other trigger (this or another player) bails: all already see it.
            if (!store.revealGlobal(dep.id())) return false;
        } else if (wasVisible) {
            return false;
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

        // ── Step 3: deliver — everyone (GLOBAL), the team (TEAM), or just this
        // player (PER_PLAYER). Clients with discovery_chat off still get the
        // marker (the chat line is suppressed client-side).
        if (global) {
            for (ServerPlayer p : lvl.getServer().getPlayerList().getPlayers()) {
                if (!p.serverLevel().dimension().equals(lvl.dimension())) continue;
                sendSync(p, new DepositSyncPayload(List.of(snap)));
                sendDiscovery(p, discovery);
            }
        } else if (scope == Config.RevealScope.TEAM) {
            // Visibility under TEAM is LIVE (buildVisible unions the team's
            // records), so no records are copied — just push the one-shot
            // marker + chat to the discoverer and the online teammates in this
            // dimension. Offline teammates see it on join; new teammates inherit
            // it via the live union; leaving the team un-shares it.
            sendSync(player, new DepositSyncPayload(List.of(snap)));
            sendDiscovery(player, discovery);
            for (java.util.UUID mate : mates) {
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
     * Can this player currently see {@code dep} (i.e. is it on their map)?
     * Mirrors {@link #buildVisible}'s per-deposit predicate. Used to gate the
     * share actions — you can only share what you can see.
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
     * Reveal {@code dep} for {@code to} because {@code from} shared it (direct
     * {@code /coedeposits share <player>} or a clicked chat offer). Idempotent:
     * returns false when the target already had it. On a fresh share, pushes the
     * marker sync + the discovery chat line (with {@code %player%} = the sharer,
     * so templates read "Dev shared/discovered Iron …") + Xaero waypoint.
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
     * Broadcast a clickable share offer to every player in the dimension:
     * "<sharer> shares <Name> at [x, ~, z]  [+ Add to map]". Clicking the button
     * runs {@code /coedeposits accept <id>}, which reveals the deposit for the
     * clicking player ({@link #shareWith}). This is the chat-button flow behind
     * the world-map share keybind and {@code /coedeposits share} with no target.
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
     * first time they come within the proximity radius. No visibility change
     * (ON_PROXIMITY snapshots are always synced; the client filters by distance),
     * so this is just the discovery chat line + best-effort Xaero waypoint.
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
        Config.RevealScope scope = Config.REVEAL_SCOPE.get();
        boolean globalScope = scope == Config.RevealScope.GLOBAL;
        // TEAM visibility is LIVE: a deposit is visible when the player or any
        // CURRENT teammate revealed it — so joining a team shares past finds,
        // leaving un-shares them (mirrors the GLOBAL live-switch semantics).
        java.util.Set<java.util.UUID> mates = scope == Config.RevealScope.TEAM
                ? uk.niknik.coedeposits.compat.TeamBridge.teammatesOf(player)
                : java.util.Set.of();
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
            // reveal makes a deposit visible to everyone, but ONLY while the
            // scope is GLOBAL — under PER_PLAYER visibility is purely personal,
            // so flipping back hides shared discoveries again.
            boolean seen = store.isRevealed(pid, d.id())
                    || (globalScope && store.isGloballyRevealed(d.id()))
                    || (!mates.isEmpty() && anyRevealed(store, mates, d.id()));
            if (mode.isPerPlayer() && !seen) continue;
            out.add(DepositSnapshot.fromDeposit(lvl, d));
        }
        return out;
    }

    /** True when ANY of {@code players} has a personal reveal record for {@code depositId}. */
    private static boolean anyRevealed(DepositSavedData store,
                                       java.util.Set<java.util.UUID> players, java.util.UUID depositId) {
        for (java.util.UUID p : players) {
            if (store.isRevealed(p, depositId)) return true;
        }
        return false;
    }
}
