package uk.niknik.coedeposits.event;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.gen.PickerInstaller;
import uk.niknik.coedeposits.network.CoedepositsNetwork;
import uk.niknik.coedeposits.network.DepositSnapshot;
import uk.niknik.coedeposits.network.DepositSyncPayload;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Pushes a reveal-mode-aware deposit snapshot to a player as soon as they log
 * in, so the world-map overlay is populated before they open the map.
 *
 * <p>One sync payload per enabled dimension — each level has its own
 * {@link DepositSavedData}. Only deposits the player is allowed to see are
 * included; see {@link CoedepositsNetwork#buildVisible}.
 */
@EventBusSubscriber(modid = Coedeposits.MODID)
public final class PlayerJoinSyncListener {
    private PlayerJoinSyncListener() {}

    /** PlayerLoggedInEvent handler — bulk sync per enabled dimension, no chat noise. */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        // Counters track total deposits known server-side vs ones the player
        // can see (reveal-filtered). Used purely for the diagnostic log below.
        int total = 0;
        int visible = 0;

        // ── Per-dimension sync: one packet per managed level ────────────────
        // Each enabled dimension has its own DepositSavedData. We filter
        // visibility per-dim because reveal state is per-deposit (which is
        // implicitly per-dim — deposits don't migrate across dims) and the
        // client cache lookups also key on dimension id.
        for (ServerLevel lvl : PickerInstaller.enabledLevels(sp.getServer())) {
            DepositSavedData store = DepositSavedData.get(lvl);
            if (store.all().isEmpty()) continue;
            total += store.all().size();
            // buildVisible applies the reveal-mode filter: ALWAYS/ON_PROXIMITY
            // pass through, ON_DISCOVERY/ON_PROSPECT need an explicit reveal
            // record for this player.
            List<DepositSnapshot> snapshots = CoedepositsNetwork.buildVisible(
                    lvl, store, sp, store.all().values());
            if (snapshots.isEmpty()) continue;
            visible += snapshots.size();
            CoedepositsNetwork.sendSync(sp, new DepositSyncPayload(snapshots));
        }

        // Single summary line — quieter than logging per dim, and tells the
        // admin both the visibility breakdown (X/Y) and how many dims got
        // synced. Skipped entirely when total=0 (no deposits placed anywhere).
        if (total > 0 && Config.LOG_LIFECYCLE.get()) {
            Coedeposits.LOGGER.info("[coedeposits] synced {}/{} deposits to {} on login across {} dim(s)",
                    visible, total, sp.getName().getString(),
                    Config.enabledDimensions().size());
        }
    }
}
