package uk.niknik.coedeposits.event;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.tom.createores.OreData;
import com.tom.createores.OreDataAttachment;
import com.tom.createores.recipe.VeinRecipe;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.gen.PickerInstaller;
import uk.niknik.coedeposits.network.CoedepositsNetwork;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Server-tick listener that does two periodic jobs:
 * <ol>
 *   <li><b>Depletion sweep</b> (every 20 ticks ≈ 1s) — iterates only chunks
 *       tracked by {@link DepositSavedData}, clears OreData when a drill has
 *       fully extracted the vein. After clearing, Vein Finder reports
 *       "Nothing" for that chunk and the drill stops.</li>
 *   <li><b>Map re-sync</b> (every 100 ticks ≈ 5s) — broadcasts current
 *       snapshots of all overworld deposits to every online player so the
 *       world-map tooltip shows fresh remaining counts as drills extract.</li>
 * </ol>
 */
@EventBusSubscriber(modid = Coedeposits.MODID)
public final class DepositDepletionListener {
    private DepositDepletionListener() {}

    // Depletion sweep every 20 ticks (~1s). Iterates only chunks tracked by
    // DepositSavedData, not the whole loaded-chunks set.
    private static final int DEPLETION_INTERVAL_TICKS = 20;
    // World-map overlay re-sync every 100 ticks (~5s) so tooltips show fresh
    // per-chunk remaining as drills extract. Cheap broadcast (one packet per
    // dimension to all online players).
    private static final int SYNC_INTERVAL_TICKS = 100;

    /** Game-bus tick handler — gates depletion and re-sync work on interval. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        long tick = server.getTickCount();

        if (tick % DEPLETION_INTERVAL_TICKS == 0) {
            for (ServerLevel lvl : PickerInstaller.enabledLevels(server)) {
                DepositSavedData store = DepositSavedData.get(lvl);
                if (store.all().isEmpty()) continue;
                ServerChunkCache cache = lvl.getChunkSource();

                for (Deposit dep : store.all().values()) {
                    for (ChunkPos cp : dep.chunks()) {
                        LevelChunk chunk = cache.getChunkNow(cp.x, cp.z);
                        if (chunk == null) continue;
                        clearIfDepleted(lvl, chunk);
                    }
                }
            }
        }

        if (tick % SYNC_INTERVAL_TICKS == 0 && !server.getPlayerList().getPlayers().isEmpty()) {
            for (ServerLevel lvl : PickerInstaller.enabledLevels(server)) {
                DepositSavedData store = DepositSavedData.get(lvl);
                if (store.all().isEmpty()) continue;
                // Reveal-aware per-player broadcast — ON_DISCOVERY/ON_PROSPECT
                // deposits stay hidden for players that haven't unlocked them.
                // broadcastSyncFiltered already filters by player's current dimension.
                CoedepositsNetwork.broadcastSyncFiltered(server, lvl, store.all().values());
            }
        }
    }

    /** If {@code OreData.getResourcesRemaining() == -1}, null out the recipe so the vein looks "empty". */
    private static void clearIfDepleted(ServerLevel lvl, LevelChunk chunk) {
        OreData od = OreDataAttachment.getData(chunk);
        if (od.getRecipeId() == null) return;
        RecipeHolder<VeinRecipe> rh = od.getRecipe(lvl.getRecipeManager());
        if (rh == null) return;
        long remaining = od.getResourcesRemaining(rh.value());
        if (remaining != -1L) return;

        od.setRecipe(null);
        od.setRandomMul(0f);
        chunk.setUnsaved(true);
        Coedeposits.LOGGER.info("[coedeposits] depleted chunk {},{} — cleared OreData",
                chunk.getPos().x, chunk.getPos().z);
    }
}
