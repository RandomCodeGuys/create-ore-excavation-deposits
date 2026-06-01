package uk.niknik.coedeposits.gen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import com.tom.createores.OreVeinGenerator;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.store.DepositSavedData;

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

    /**
     * COE's {@code CreateOreExcavation.reloadEvent} nulls its vein picker on EVERY
     * {@link TagsUpdatedEvent} — which fires on server data-load AND, in single player,
     * when the CLIENT receives tags from the integrated server. Our {@link #onServerStarted}
     * / {@link #onDatapackSync} hooks don't cover that client-side fire, so the picker can
     * be left null; COE's {@code getPicker} then lazily recreates a DEFAULT picker that
     * ignores our managed deposits, and lazy {@code getData → pick} stops writing our vein
     * recipe → chunks read back as "depleted". Re-install at {@link EventPriority#LOWEST} so
     * we always run AFTER COE's invalidate within this same dispatch. The event may fire on
     * the client thread (SP), so marshal the install onto the server thread.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;  // pure client connected to a remote server — it owns its picker
        if (server.isSameThread()) {
            CoedepositsPicker.install(server.overworld());
        } else {
            server.execute(() -> CoedepositsPicker.install(server.overworld()));
        }
    }

    /**
     * Deterministic OreData guarantee: whenever a chunk of a known MANAGED deposit loads,
     * write its vein recipe straight into the COE capability via
     * {@link CoedepositsPicker#ensureOreData}. This does NOT depend on the picker being the
     * installed one (see {@link #onTagsUpdated}), so it fixes both the map "depleted" read
     * and actual drilling even if the picker was transiently replaced by COE's default.
     * COE-placement deposits are skipped — those are real COE veins COE populates itself.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel lvl)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;  // skip proto-chunks
        if (!Config.isDimensionEnabled(lvl.dimension().location())) return;
        DepositSavedData store = DepositSavedData.get(lvl);
        ChunkPos cp = chunk.getPos();
        Deposit dep = store.lookup(cp);
        if (dep == null || dep.placement() != DepositType.Placement.MANAGED) return;
        DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
        if (type == null || type.veinRecipes().isEmpty()) return;
        ResourceLocation recipeId =
                DepositPlacer.rollChunkRecipe(type, store.effectiveSeed(lvl), cp).orElse(null);
        if (recipeId == null) return;  // this chunk legitimately rolled filler — no ore
        float perChunk = dep.amountMulFor(cp, Config.EDGE_AMOUNT_MUL.get().floatValue());
        CoedepositsPicker.ensureOreData(chunk, recipeId, perChunk);
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
