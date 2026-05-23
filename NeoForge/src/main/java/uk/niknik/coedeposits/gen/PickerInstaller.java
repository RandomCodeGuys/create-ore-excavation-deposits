package uk.niknik.coedeposits.gen;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import com.tom.createores.OreVeinGenerator;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;

/**
 * Lifecycle hooks that swap COE's {@code OreVeinGenerator.picker} with our
 * {@link CoedepositsPicker}. Subscribed to the game event bus via
 * {@code NeoForge.EVENT_BUS.register(PickerInstaller.class)} in
 * {@link uk.niknik.coedeposits.Coedeposits}.
 *
 * <p>The picker itself is a global singleton on COE's static field, so we
 * only install it once per server start. The prospect-scan sweep, however,
 * runs separately for each enabled dimension — each level has its own
 * {@link uk.niknik.coedeposits.store.DepositSavedData} state.
 *
 * <p>Re-installs on data-pack sync so {@code /reload} doesn't leave a stale
 * COE-default picker behind.
 */
public final class PickerInstaller {
    private PickerInstaller() {}

    /**
     * Server-start path: invalidate COE's lazy picker, install ours, then
     * run a one-shot {@link ProspectScanner} sweep on every enabled
     * dimension so the map overlay is populated immediately on first
     * player login.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        OreVeinGenerator.invalidate();
        // Picker is global — installing once on any ServerLevel is enough. We
        // pick the overworld arbitrarily; the picker dispatches per-chunk via
        // the chunk's own level reference.
        CoedepositsPicker.install(server.overworld());

        int radius = Config.PROSPECT_RADIUS.get();
        for (ServerLevel lvl : enabledLevels(server)) {
            ProspectScanner.scan(lvl, radius);
        }
    }

    /**
     * Datapack-sync path: also re-installs the picker because COE invalidates
     * its picker on {@code TagsUpdatedEvent} (which fires alongside resync),
     * and the rebuilt default picker would otherwise replace ours.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        MinecraftServer server = event.getPlayerList().getServer();
        OreVeinGenerator.invalidate();
        CoedepositsPicker.install(server.overworld());
    }

    /**
     * Iterable view of every loaded {@link ServerLevel} whose dimension is
     * present in {@link Config#ENABLED_DIMENSIONS}. Empty when the player has
     * configured zero dimensions (rare; usually the default overworld is on).
     */
    public static Iterable<ServerLevel> enabledLevels(MinecraftServer server) {
        java.util.List<ServerLevel> out = new java.util.ArrayList<>();
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
