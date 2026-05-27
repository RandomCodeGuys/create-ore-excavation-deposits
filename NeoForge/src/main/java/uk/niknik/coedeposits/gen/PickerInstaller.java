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
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

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
     * enqueue a one-shot {@link ProspectScanQueue} sweep on every enabled
     * dimension so the map overlay populates as players log in.
     *
     * <p>The scan runs asynchronously on the queue's worker thread — server
     * start is no longer blocked by the {@code (radius/16)²} picker work.
     * Players logging in within the first few seconds may see the overlay
     * fill in gradually as the materialize tick budget drains the queue;
     * the deposits themselves are still placed correctly via the chunk-load
     * picker even before the overlay catches up.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();

        // ── Step 1: tell COE to drop its lazy picker ────────────────────────
        // Defensive — if COE already populated its picker via the default
        // RandomSpreadGenerator constructor we want it gone before we install
        // ours, otherwise super.pick() in Phase 3 would chain into the wrong
        // implementation.
        OreVeinGenerator.invalidate();

        // ── Step 2: install our picker globally ─────────────────────────────
        // Picker is one static AtomicReference shared across all levels —
        // installing once on any ServerLevel suffices. Overworld is chosen
        // arbitrarily because every server has one; the picker itself looks
        // up the active level from each chunk it processes.
        CoedepositsPicker.install(server.overworld());

        // ── Step 3: kick off the async prospect scan per enabled dim ────────
        // Was synchronous in 0.1.1, freezing server start for ~1s per dim at
        // default radius=2000. Now ProspectScanQueue picks the work up on its
        // worker thread; the overlay fills in over the first ~12s of uptime
        // (rate driven by prospect_chunks_per_tick).
        int radius = Config.PROSPECT_RADIUS.get();
        for (ServerLevel lvl : enabledLevels(server)) {
            ProspectScanQueue.INSTANCE.enqueue(lvl, lvl.getSharedSpawnPos(), radius);
        }
    }

    /**
     * Server-stop path: flush any in-flight {@link ProspectScanQueue}
     * placements synchronously so deposits the worker had already picked
     * but hadn't yet handed off to the materialize tick still make it into
     * the SavedData file before the world saves and shuts down. Also tears
     * down the worker executor.
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ProspectScanQueue.INSTANCE.shutdown(enabledLevels(event.getServer()));
    }

    /**
     * Datapack-sync path: also re-installs the picker because COE invalidates
     * its picker on {@code TagsUpdatedEvent} (which fires alongside resync),
     * and the rebuilt default picker would otherwise replace ours.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        // /reload (and the implicit datapack sync on player join) fires
        // TagsUpdatedEvent inside COE which invalidates COE's picker back to
        // the default RandomSpreadGenerator. We have to re-install ours
        // immediately or every chunk-load between now and the next server
        // restart will skip our blob algorithm.
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
        // Walk every loaded level (including modded dims like the_aether or
        // the Twilight Forest) and filter to those listed in the
        // ENABLED_DIMENSIONS config. Returns a real ArrayList (not a stream
        // view) so callers can iterate it multiple times without re-walking.
        java.util.List<ServerLevel> out = new java.util.ArrayList<>();
        for (ServerLevel lvl : server.getAllLevels()) {
            ResourceKey<Level> key = lvl.dimension();
            ResourceLocation id = key.location();
            if (Config.isDimensionEnabled(id)) out.add(lvl);
        }
        if (out.isEmpty()) {
            // Common misconfiguration: admin renamed a dim id (e.g. "the_aether"
            // → "aether:aether") and forgot to update the allow-list. Log so
            // they notice without the picker silently being a no-op.
            Coedeposits.LOGGER.warn("[coedeposits] enabled_dimensions resolved to no loaded dimensions");
        }
        return out;
    }
}
