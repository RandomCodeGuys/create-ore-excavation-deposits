package uk.niknik.coedeposits.event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;

import com.tom.createores.OreDataCapability;
import com.tom.createores.OreVeinGenerator;
import com.tom.createores.item.OreVeinFinderItem;
import com.tom.createores.recipe.VeinRecipe;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.gen.CoedepositsPicker;
import uk.niknik.coedeposits.gen.DepositPlacer;
import uk.niknik.coedeposits.gen.ProspectScanQueue;
import uk.niknik.coedeposits.network.CoedepositsNetwork;
import uk.niknik.coedeposits.network.DepositSnapshot;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Fabric server-side event hub — the consolidated port of Forge's
 * {@code PickerInstaller} + {@code PlayerJoinSyncListener} +
 * {@code PlayerRoamProspectListener} + {@code DepositDepletionListener} +
 * {@code VeinFinderListener}. The LOGIC bodies are copied verbatim from those
 * Forge {@code src/main} classes (they only call the picker / store / network,
 * all of which exist identically on Fabric); only the event entry points change:
 *
 * <ul>
 *   <li>{@code ServerStartedEvent}              → {@link ServerLifecycleEvents#SERVER_STARTED}</li>
 *   <li>{@code ServerStoppingEvent}             → {@link ServerLifecycleEvents#SERVER_STOPPING}</li>
 *   <li>{@code OnDatapackSyncEvent}             → {@link ServerLifecycleEvents#END_DATA_PACK_RELOAD}</li>
 *   <li>{@code TagsUpdatedEvent}                → {@link CommonLifecycleEvents#TAGS_LOADED}</li>
 *   <li>{@code ChunkEvent.Load}                 → {@link ServerChunkEvents#CHUNK_LOAD}</li>
 *   <li>{@code TickEvent.ServerTickEvent (END)} → {@link ServerTickEvents#END_SERVER_TICK}</li>
 *   <li>{@code PlayerLoggedInEvent}             → {@link ServerPlayConnectionEvents#JOIN}</li>
 *   <li>{@code PlayerInteractEvent.RightClick*} → {@link UseItemCallback} / {@link UseBlockCallback}</li>
 * </ul>
 */
public final class CoedepositsServerEvents {
    private CoedepositsServerEvents() {}

    // ── tick cadences (copied from the Forge listeners) ──────────────────────
    private static final int CHECK_INTERVAL_TICKS = 200;
    private static final int DEPLETION_INTERVAL_TICKS = 20;
    private static final int REPLENISH_INTERVAL_TICKS = 20;
    private static final int SYNC_INTERVAL_TICKS = 100;

    /** Per-player last position where we enqueued a scan; reset when player rejoins. */
    private static final Map<UUID, BlockPos> lastScanCenter = new HashMap<>();
    private static final Map<UUID, Map<Long, Double>> REPLENISH_ACCUMULATORS = new ConcurrentHashMap<>();

    /** Tracked server instance — set on start, cleared on stop (for the tags-loaded re-install). */
    private static volatile MinecraftServer RUNNING_SERVER;

    /** Wire every server-side Fabric event. Called once from {@code CoedepositsFabric}. */
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(CoedepositsServerEvents::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(CoedepositsServerEvents::onServerStopping);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, mgr, success) -> reinstallPicker(server));
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> onTagsLoaded());
        ServerChunkEvents.CHUNK_LOAD.register(CoedepositsServerEvents::onChunkLoad);
        ServerTickEvents.END_SERVER_TICK.register(CoedepositsServerEvents::onServerTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerJoin(handler.player));
        UseItemCallback.EVENT.register(CoedepositsServerEvents::onUseItem);
        UseBlockCallback.EVENT.register(CoedepositsServerEvents::onUseBlock);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PickerInstaller port
    // ════════════════════════════════════════════════════════════════════════

    /** Server start: install the picker + enqueue an async prospect scan per enabled dimension. */
    private static void onServerStarted(MinecraftServer server) {
        RUNNING_SERVER = server;
        OreVeinGenerator.invalidate();
        CoedepositsPicker.install(server.overworld());

        int radius = Config.PROSPECT_RADIUS.get();
        for (ServerLevel lvl : enabledLevels(server)) {
            ProspectScanQueue.INSTANCE.enqueue(lvl, lvl.getSharedSpawnPos(), radius);
        }
    }

    /** Server stop: flush in-flight prospect placements + tear down the worker. */
    private static void onServerStopping(MinecraftServer server) {
        ProspectScanQueue.INSTANCE.shutdown(enabledLevels(server));
        RUNNING_SERVER = null;
    }

    /** Datapack reload (/reload): re-install the picker COE invalidated on tags update. */
    private static void reinstallPicker(MinecraftServer server) {
        OreVeinGenerator.invalidate();
        CoedepositsPicker.install(server.overworld());
    }

    /**
     * COE nulls its vein picker on EVERY tags update (server data-load, and in
     * single player when the CLIENT receives tags). Re-install on the server
     * thread so we always run after COE's invalidate; mirrors the Forge
     * {@code onTagsUpdated} at LOWEST priority.
     */
    private static void onTagsLoaded() {
        MinecraftServer server = RUNNING_SERVER;
        if (server == null) return;  // pure client connected to a remote server — it owns its picker
        if (server.isSameThread()) {
            CoedepositsPicker.install(server.overworld());
        } else {
            server.execute(() -> CoedepositsPicker.install(server.overworld()));
        }
    }

    /**
     * Deterministic OreData guarantee on chunk-load for known MANAGED deposits —
     * verbatim from Forge {@code PickerInstaller.onChunkLoad}.
     */
    private static void onChunkLoad(ServerLevel lvl, LevelChunk chunk) {
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

    // ════════════════════════════════════════════════════════════════════════
    //  Combined server tick — roam/discovery (PlayerRoamProspectListener)
    //  + depletion/replenish/sync (DepositDepletionListener)
    // ════════════════════════════════════════════════════════════════════════

    private static void onServerTick(MinecraftServer server) {
        long tick = server.getTickCount();

        // ── Per-tick: drain the prospect materialize queue ──────────────────
        int budget = Config.PROSPECT_CHUNKS_PER_TICK.get();
        for (ServerLevel lvl : enabledLevels(server)) {
            ProspectScanQueue.INSTANCE.tickMaterialize(lvl, budget);
        }

        // ── Every 200 ticks: roam-enqueue + ON_DISCOVERY reveal sweep ───────
        if (tick % CHECK_INTERVAL_TICKS == 0) {
            int prospectRadius = Config.PROSPECT_RADIUS.get();
            int discoveryRadius = Config.DISCOVERY_RADIUS_BLOCKS.get();
            long discoveryRadiusSq = (long) discoveryRadius * discoveryRadius;
            long prospectTriggerSq = (long) (prospectRadius / 2) * (prospectRadius / 2);

            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                ServerLevel lvl = p.serverLevel();
                if (!Config.isDimensionEnabled(lvl.dimension().location())) continue;
                BlockPos current = p.blockPosition();

                if (prospectRadius > 0) {
                    BlockPos last = lastScanCenter.get(p.getUUID());
                    if (last == null || distSq(last, current) > prospectTriggerSq) {
                        ProspectScanQueue.INSTANCE.enqueue(lvl, current, prospectRadius);
                        lastScanCenter.put(p.getUUID(), current);
                    }
                }
                tryRevealDiscoveryNear(lvl, p, current, discoveryRadiusSq);
            }
        }

        // ── Depletion sweep ─────────────────────────────────────────────────
        if (tick % DEPLETION_INTERVAL_TICKS == 0) {
            for (ServerLevel lvl : enabledLevels(server)) {
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

        // ── Replenishment sweep ─────────────────────────────────────────────
        if (tick % REPLENISH_INTERVAL_TICKS == 0) {
            double elapsedSec = REPLENISH_INTERVAL_TICKS / 20.0;
            for (ServerLevel lvl : enabledLevels(server)) {
                DepositSavedData store = DepositSavedData.get(lvl);
                if (store.all().isEmpty()) continue;
                long depositSeed = store.effectiveSeed(lvl);
                ServerChunkCache cache = lvl.getChunkSource();
                for (Deposit dep : store.all().values()) {
                    DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
                    if (type == null) continue;
                    double rate = dep.effectiveReplenishRate(type);
                    if (rate <= 0.0) continue;
                    replenishDeposit(lvl, cache, dep, type, depositSeed, rate, elapsedSec);
                }
            }
        }

        // ── Map re-sync broadcast ───────────────────────────────────────────
        if (tick % SYNC_INTERVAL_TICKS == 0 && !server.getPlayerList().getPlayers().isEmpty()) {
            for (ServerLevel lvl : enabledLevels(server)) {
                DepositSavedData store = DepositSavedData.get(lvl);
                if (store.all().isEmpty()) continue;
                CoedepositsNetwork.broadcastSyncFiltered(server, lvl, store.all().values());
            }
        }
    }

    private static void tryRevealDiscoveryNear(ServerLevel lvl, ServerPlayer player, BlockPos current, long discoveryRadiusSq) {
        DepositSavedData store = DepositSavedData.get(lvl);
        if (store.all().isEmpty()) return;
        UUID pid = player.getUUID();
        for (Deposit dep : store.all().values()) {
            if (store.isRevealed(pid, dep.id())) continue;
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
            Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
            if (mode != Config.RevealMode.ON_DISCOVERY) continue;
            if (!withinAnyChunk(current, dep, discoveryRadiusSq)) continue;
            if (CoedepositsNetwork.revealAndNotify(lvl, player, dep)) {
                if (Config.LOG_DISCOVERY.get()) {
                    Coedeposits.LOGGER.info("[coedeposits] {} discovered {} via walk",
                            player.getName().getString(), dep.name());
                }
            }
        }
    }

    private static boolean withinAnyChunk(BlockPos from, Deposit dep, long radiusSq) {
        int fx = from.getX();
        int fz = from.getZ();
        for (ChunkPos cp : dep.chunks()) {
            long dx = cp.getMiddleBlockX() - fx;
            long dz = cp.getMiddleBlockZ() - fz;
            if (dx * dx + dz * dz <= radiusSq) return true;
        }
        return false;
    }

    private static long distSq(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static void replenishDeposit(
            ServerLevel lvl, ServerChunkCache cache, Deposit dep, DepositType type,
            long depositSeed, double ratePerHour, double elapsedSec) {

        List<LevelChunk> loadedOreChunks = new ArrayList<>();
        for (ChunkPos cp : dep.chunks()) {
            LevelChunk chunk = cache.getChunkNow(cp.x, cp.z);
            if (chunk == null) continue;
            if (dep.placement() == DepositType.Placement.MANAGED) {
                java.util.Optional<ResourceLocation> rolled =
                        DepositPlacer.rollChunkRecipe(type, depositSeed, cp);
                if (rolled.isEmpty()) continue;
            }
            loadedOreChunks.add(chunk);
        }
        if (loadedOreChunks.isEmpty()) return;

        double totalUnitsThisSweep = (ratePerHour / 3600.0) * elapsedSec;
        double perChunkUnits = totalUnitsThisSweep / loadedOreChunks.size();

        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
        int finiteBase = com.tom.createores.Config.finiteAmountBase;
        Map<Long, Double> depAccum = REPLENISH_ACCUMULATORS.computeIfAbsent(dep.id(), k -> new HashMap<>());
        for (LevelChunk chunk : loadedOreChunks) {
            long packedPos = chunk.getPos().toLong();
            double accum = depAccum.getOrDefault(packedPos, 0.0) + perChunkUnits;
            long integerUnits = (long) accum;
            if (integerUnits > 0) {
                applyReplenish(lvl, chunk, dep, edgeMul, finiteBase, integerUnits);
                accum -= integerUnits;
            }
            depAccum.put(packedPos, accum);
        }
    }

    private static void applyReplenish(
            ServerLevel lvl, LevelChunk chunk, Deposit dep,
            float edgeMul, int finiteBase, long integerUnits) {
        OreDataCapability.OreData od = OreDataCapability.getData(chunk);
        if (od.getRecipeId() == null) return;
        VeinRecipe vr = od.getRecipe(lvl.getRecipeManager());
        if (vr == null) return;

        long remaining = od.getResourcesRemaining(vr);
        if (remaining <= 0) return;

        float perChunkRandomMul = dep.amountMulFor(chunk.getPos(), edgeMul);
        long total = Math.round(
                ((vr.getMaxAmount() - vr.getMinAmount()) * perChunkRandomMul + vr.getMinAmount())
                        * (double) finiteBase);
        long newRemaining = Math.min(total, remaining + integerUnits);
        if (newRemaining == remaining) return;

        long newExtracted = Math.max(0L, total - newRemaining);
        od.setExtractedAmount(newExtracted);
        chunk.setUnsaved(true);

        if (Config.LOG_REPLENISH_ACTIONS.get()) {
            long delta = newRemaining - remaining;
            Coedeposits.LOGGER.info(
                    "[coedeposits] replenished {} chunk {},{}: +{} units ({} → {} of {})",
                    dep.name(), chunk.getPos().x, chunk.getPos().z,
                    delta, remaining, newRemaining, total);
        }
    }

    /** If the chunk's vein is fully extracted ({@code getResourcesRemaining == -1}), null its recipe. */
    private static void clearIfDepleted(ServerLevel lvl, LevelChunk chunk) {
        OreDataCapability.OreData od = OreDataCapability.getData(chunk);
        if (od.getRecipeId() == null) return;
        VeinRecipe vr = od.getRecipe(lvl.getRecipeManager());
        if (vr == null) return;
        long remaining = od.getResourcesRemaining(vr);
        if (remaining != -1L) return;

        od.setRecipe(null);
        od.setRandomMul(0f);
        chunk.setUnsaved(true);
        if (Config.LOG_DEPLETION.get()) {
            Coedeposits.LOGGER.info("[coedeposits] depleted chunk {},{} — cleared OreData",
                    chunk.getPos().x, chunk.getPos().z);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Player login sync (PlayerJoinSyncListener port)
    // ════════════════════════════════════════════════════════════════════════

    private static void onPlayerJoin(ServerPlayer sp) {
        if (sp == null) return;
        int total = 0;
        int visible = 0;
        for (ServerLevel lvl : enabledLevels(sp.getServer())) {
            DepositSavedData store = DepositSavedData.get(lvl);
            if (store.all().isEmpty()) continue;
            total += store.all().size();
            List<DepositSnapshot> snapshots = CoedepositsNetwork.buildVisible(
                    lvl, store, sp, store.all().values());
            if (snapshots.isEmpty()) continue;
            visible += snapshots.size();
            CoedepositsNetwork.sendSyncBatched(sp, snapshots);
        }
        if (total > 0 && Config.LOG_LIFECYCLE.get()) {
            Coedeposits.LOGGER.info("[coedeposits] synced {}/{} deposits to {} on login across {} dim(s)",
                    visible, total, sp.getName().getString(),
                    Config.enabledDimensions().size());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Vein-finder chat + ON_PROSPECT reveal (VeinFinderListener port)
    // ════════════════════════════════════════════════════════════════════════

    private static InteractionResultHolder<ItemStack> onUseItem(Player player, Level level, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            handleVeinFinder(player, level, player.blockPosition(), player.getItemInHand(hand));
        }
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (hand == InteractionHand.MAIN_HAND) {
            handleVeinFinder(player, level, hit.getBlockPos(), player.getItemInHand(hand));
        }
        return InteractionResult.PASS;
    }

    /** Shared body — verbatim from Forge {@code VeinFinderListener.handle}. */
    private static void handleVeinFinder(Player player, Level level, BlockPos pos, ItemStack stack) {
        if (level.isClientSide) return;
        if (!(stack.getItem() instanceof OreVeinFinderItem)) return;

        ServerLevel slvl = (ServerLevel) level;
        ChunkPos cp = new ChunkPos(pos);
        Deposit d = DepositSavedData.get(slvl).lookup(cp);
        if (d == null) return;

        if (player instanceof ServerPlayer sp) {
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(d.typeId());
            Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
            if (mode == Config.RevealMode.ON_PROSPECT) {
                if (CoedepositsNetwork.revealAndNotify(slvl, sp, d)) {
                    if (Config.LOG_DISCOVERY.get()) {
                        Coedeposits.LOGGER.info("[coedeposits] {} prospected {}",
                                sp.getName().getString(), d.name());
                    }
                }
            }
        }

        LevelChunk chunk = slvl.getChunk(cp.x, cp.z);
        OreDataCapability.OreData od = OreDataCapability.getData(chunk);
        VeinRecipe vr = od.getRecipe(slvl.getRecipeManager());
        if (vr == null) return;

        long remaining = od.getResourcesRemaining(vr);
        String remStr;
        if (remaining == 0L) remStr = "infinite";
        else if (remaining == -1L) remStr = "DEPLETED";
        else remStr = String.format("%,d units", remaining);

        slvl.getServer().execute(() -> player.sendSystemMessage(Component.literal(String.format(
                "[coedeposits] deposit %s | this chunk remaining: %s | total chunks: %d | amountMul: %.2f",
                d.name(), remStr, d.chunks().size(), d.amountMul()))));
    }
}
