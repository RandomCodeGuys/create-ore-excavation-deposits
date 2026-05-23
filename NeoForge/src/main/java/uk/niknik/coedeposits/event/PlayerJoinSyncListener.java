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
        int total = 0;
        int visible = 0;
        for (ServerLevel lvl : PickerInstaller.enabledLevels(sp.getServer())) {
            DepositSavedData store = DepositSavedData.get(lvl);
            if (store.all().isEmpty()) continue;
            total += store.all().size();
            List<DepositSnapshot> snapshots = CoedepositsNetwork.buildVisible(
                    lvl, store, sp, store.all().values());
            if (snapshots.isEmpty()) continue;
            visible += snapshots.size();
            CoedepositsNetwork.sendSync(sp, new DepositSyncPayload(snapshots));
        }
        if (total > 0) {
            Coedeposits.LOGGER.info("[coedeposits] synced {}/{} deposits to {} on login across {} dim(s)",
                    visible, total, sp.getName().getString(),
                    Config.enabledDimensions().size());
        }
    }
}
