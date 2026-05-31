package uk.niknik.coedeposits.gen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.tom.createores.OreVeinGenerator;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;

/**
 * Forge lifecycle hooks that install {@link CoedepositsPicker} into COE's
 * {@code OreVeinGenerator}, seed the initial prospect-scan per dimension, and
 * flush the queue on shutdown. The per-tick materialize budget is drained by
 * {@link uk.niknik.coedeposits.event.PlayerRoamProspectListener}. Registered on
 * the game event bus from {@link uk.niknik.coedeposits.Coedeposits}.
 */
public final class PickerInstaller {
    private PickerInstaller() {}

    /** Server start: install the picker + enqueue an async prospect scan per enabled dimension. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        OreVeinGenerator.invalidate();
        CoedepositsPicker.install(server.overworld());

        int radius = Config.PROSPECT_RADIUS.get();
        for (ServerLevel lvl : enabledLevels(server)) {
            ProspectScanQueue.INSTANCE.enqueue(lvl, lvl.getSharedSpawnPos(), radius);
        }
    }

    /** Server stop: flush in-flight prospect placements + tear down the worker. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ProspectScanQueue.INSTANCE.shutdown(enabledLevels(event.getServer()));
    }

    /** Datapack sync (/reload, player join): re-install the picker COE invalidated on tags update. */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        MinecraftServer server = event.getPlayerList().getServer();
        OreVeinGenerator.invalidate();
        CoedepositsPicker.install(server.overworld());
    }

    /** Loaded {@link ServerLevel}s whose dimension is in {@link Config#ENABLED_DIMENSIONS}. */
    public static Iterable<ServerLevel> enabledLevels(MinecraftServer server) {
        List<ServerLevel> out = new ArrayList<>();
        for (ServerLevel lvl : server.getAllLevels()) {
            ResourceKey<Level> key = lvl.dimension();
            ResourceLocation id = key.location();
            if (Config.isDimensionEnabled(id)) out.add(lvl);
        }
        if (out.isEmpty()) {
            Coedeposits.LOGGER.warn("[coedeposits] enabled_dimensions resolved to no loaded dimensions");
        }
        return out;
    }
}
