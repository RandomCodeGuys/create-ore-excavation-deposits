package uk.niknik.coedeposits.gen;

import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import java.util.function.Predicate;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;

import com.mojang.datafixers.util.Pair;

import com.tom.createores.CreateOreExcavation;
import com.tom.createores.OreData;
import com.tom.createores.OreDataAttachment;
import com.tom.createores.OreVeinGenerator;
import com.tom.createores.recipe.VeinRecipe;
import com.tom.createores.util.RandomSpreadGenerator;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.network.CoedepositsNetwork;
import uk.niknik.coedeposits.network.DepositDiscoveryPayload;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Subclass of COE's {@link RandomSpreadGenerator} that replaces COE's default
 * vein placement with deposit-aware logic. Installed once on server start
 * via {@link #install(ServerLevel)} which reflects into
 * {@code OreVeinGenerator.picker} (opened by access-transformer).
 *
 * <p>Three placement paths in {@link #pick(LevelChunk)}, tried in order:
 * <ol>
 *   <li><b>SavedData lookup</b> — chunk belongs to a previously placed deposit
 *       (managed or COE-mode). Re-applies OreData and returns null so COE
 *       doesn't overwrite our randomMul.</li>
 *   <li><b>Managed blob placer</b> — {@link DepositPlacer#tryPick} may roll a
 *       new managed deposit core here. If so we materialise the blob, save it,
 *       broadcast discovery and apply OreData ourselves.</li>
 *   <li><b>COE delegation</b> — only when at least one {@code placement=COE}
 *       type exists in the registry. Calls {@code super.pick()} and either
 *       passes its result through (untracked vanilla COE vein), captures and
 *       saves it (tracked COE-mode deposit), or discards (managed vein recipe
 *       — must spawn via the blob path, not COE's natural spread).</li>
 * </ol>
 *
 * <p>Override {@link #locate} powers the Ore Vein Finder by combining a saved-
 * deposits lookup with a deterministic dry-run scan.
 */
public class CoedepositsPicker extends RandomSpreadGenerator {

    /**
     * Called by COE's {@code OreData.populate(chunk)} for every fresh chunk.
     * Routes through one of the three placement paths described in the class
     * Javadoc and returns either {@code null} (we took ownership of OreData)
     * or COE's chosen recipe (pass-through for untracked vanilla veins).
     */
    @Override
    public RecipeHolder<VeinRecipe> pick(LevelChunk chunk) {
        ServerLevel lvl = (ServerLevel) chunk.getLevel();
        ChunkPos cp = chunk.getPos();
        ResourceLocation dim = lvl.dimension().location();

        // Dimension allow-list gate. Dimensions outside it get pure vanilla
        // COE behaviour — we don't suppress anything, but also don't track.
        if (!Config.isDimensionEnabled(dim)) {
            return null;
        }

        DepositSavedData store = DepositSavedData.get(lvl);

        // Phase 1: chunk already part of a known deposit (managed or COE-mode).
        Deposit existing = store.lookup(cp);
        if (existing != null) {
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(existing.typeId());
            if (type != null) {
                float perChunk = existing.amountMulFor(
                        cp, Config.EDGE_AMOUNT_MUL.get().floatValue());
                applyToOreData(chunk, type.veinRecipe(), perChunk);
            }
            return null;
        }

        // Phase 2: managed blob placer. Filters to placement=MANAGED types only;
        // COE-mode types are intentionally not eligible here. Per-type dimension
        // filter is enforced inside tryPick.
        BlockPos spawn = lvl.getSharedSpawnPos();
        BlockPos chunkCenterForBiome = new BlockPos(
                cp.getMiddleBlockX(), spawn.getY(), cp.getMiddleBlockZ());
        DepositPlacer.Result placed = DepositPlacer.tryPick(
                cp, spawn, store.effectiveSeed(lvl), Coedeposits.DEPOSIT_TYPES,
                Config.BASE_RADIUS.get().floatValue(),
                Config.MAX_RADIUS.get().floatValue(),
                Config.CORE_SPAWN_PROBABILITY.get().floatValue(),
                lvl.getBiome(chunkCenterForBiome),
                dim);

        if (placed != null) {
            VeinRecipe vr = resolveRecipeValue(lvl, placed.type().veinRecipe());
            if (vr == null) {
                Coedeposits.LOGGER.warn(
                        "[coedeposits] picker dropped placement at {},{} — vein_recipe {} not loaded",
                        cp.x, cp.z, placed.type().veinRecipe());
                return null;
            }
            double targetUnits = placed.type().perChunkUnits().computeTarget(
                    placed.tierFraction(),
                    Config.UNBOUNDED_GROWTH.get());
            int base = com.tom.createores.Config.finiteAmountBase;
            float amountMul = DepositPlacer.amountMulForTarget(
                    targetUnits, vr.getMinAmount(), vr.getMaxAmount(), base);

            Deposit dep = new Deposit(
                    UUID.randomUUID(),
                    placed.typeId(),
                    nameFor(placed.typeId(), cp),
                    cp,
                    placed.chunks(),
                    amountMul,
                    placed.tierFraction(),
                    DepositType.Placement.MANAGED);
            store.add(dep);
            float perChunkCore = dep.amountMulFor(
                    cp, Config.EDGE_AMOUNT_MUL.get().floatValue());
            applyToOreData(chunk, placed.type().veinRecipe(), perChunkCore);
            Coedeposits.LOGGER.info(
                    "[coedeposits] placed {} at chunk {},{} | {} chunks | tier {} | target {} units/chunk peak",
                    placed.typeId(), cp.x, cp.z, placed.chunks().size(),
                    String.format("%.2f", placed.tierFraction()),
                    String.format("%,.0f", targetUnits));
            broadcastDiscovery(lvl, dep);
            return null;
        }

        // Phase 3: COE delegation. Skipped entirely when no placement=COE entry
        // exists — keeps the default behaviour identical to pre-delegation
        // versions for users who never opt in.
        if (!hasAnyCoePlacementType()) {
            return null;
        }

        RecipeHolder<VeinRecipe> superResult = super.pick(chunk);
        if (superResult == null) return null;

        ResourceLocation chosenVein = superResult.id();

        // Defend against COE picking one of our managed vein recipes via its
        // own random spread. Managed types should only spawn through the blob
        // algorithm — otherwise a chunk that didn't roll a core would get a
        // single-chunk managed vein from COE, breaking the deposit model.
        if (Coedeposits.DEPOSIT_TYPES.managedVeinRecipes().contains(chosenVein)) {
            return null;
        }

        // COE-tracked: find the deposit-type whose vein_recipe matches what
        // COE picked, take ownership of OreData and persist for the map.
        // Honour the per-type dimensions allow-list — a COE entry restricted
        // to specific dimensions won't be tracked outside them.
        ResourceLocation coeTypeId = Coedeposits.DEPOSIT_TYPES.coeTypeIdForVeinRecipe(chosenVein);
        DepositType coeType = coeTypeId != null ? Coedeposits.DEPOSIT_TYPES.get(coeTypeId) : null;
        if (coeType != null && coeType.matchesDimension(dim)) {
            float amountMul = rollDeterministicMul(store.effectiveSeed(lvl), cp);
            applyToOreData(chunk, chosenVein, amountMul);

            Deposit dep = new Deposit(
                    UUID.randomUUID(),
                    coeTypeId,
                    nameFor(coeTypeId, cp),
                    cp,
                    Set.of(cp),
                    amountMul,
                    0f,
                    DepositType.Placement.COE);
            store.add(dep);
            Coedeposits.LOGGER.info(
                    "[coedeposits] tracked COE vein {} at chunk {},{} | dim {} | amountMul {}",
                    chosenVein, cp.x, cp.z, dim, String.format("%.2f", amountMul));
            broadcastDiscovery(lvl, dep);
            return null;
        }

        // Recipe isn't in our registry — pass it through so COE places it
        // normally. No map tracking, no SavedData entry.
        return superResult;
    }

    /** True when at least one {@code placement=coe} type exists — gates the super.pick() delegation path. */
    private static boolean hasAnyCoePlacementType() {
        for (DepositType t : Coedeposits.DEPOSIT_TYPES.all().values()) {
            if (t.placement() == DepositType.Placement.COE) return true;
        }
        return false;
    }

    /**
     * Write the COE {@code OreData} for a chunk directly via reflection. Bypasses
     * {@link OreDataAttachment#getData} (which would recursively trigger
     * {@code populate}). Sets recipe, randomMul, and resets extractedAmount to 0
     * (so re-applying always refills the chunk).
     *
     * @param chunk      target chunk; must be loaded
     * @param recipe     COE vein recipe id to bind to this chunk
     * @param amountMul  randomMul value driving COE's amount formula
     */
    public static void applyToOreData(LevelChunk chunk, ResourceLocation recipe, float amountMul) {
        try {
            OreDataAttachment at = chunk.getData(CreateOreExcavation.ORE_DATA);
            // OreDataAttachment.data is private. Direct field access via reflection
            // because we can't call OreDataAttachment.getData(chunk) — it would
            // recursively trigger OreData.populate() which is our caller.
            java.lang.reflect.Field f = OreDataAttachment.class.getDeclaredField("data");
            f.setAccessible(true);
            OreData od = (OreData) f.get(at);
            od.setRecipe(recipe);
            od.setRandomMul(amountMul);
            od.setExtractedAmount(0);   // refill semantics: every apply resets extraction
            od.setLoaded(true);
        } catch (ReflectiveOperationException e) {
            Coedeposits.LOGGER.error("[coedeposits] failed to apply OreData for chunk {}",
                    chunk.getPos(), e);
        }
    }

    /** Package-private recipe lookup helper used by ProspectScanner and picker. */
    @SuppressWarnings("unchecked")
    static VeinRecipe resolveRecipeValue(ServerLevel lvl, ResourceLocation id) {
        return lvl.getRecipeManager().byKey(id)
                .filter(r -> r.value() instanceof VeinRecipe)
                .map(r -> ((RecipeHolder<VeinRecipe>) r).value())
                .orElse(null);
    }

    /** Build a human-readable deposit label like {@code "iron@128,256"}. */
    private static String nameFor(ResourceLocation typeId, ChunkPos cp) {
        return typeId.getPath() + "@" + (cp.x * 16) + "," + (cp.z * 16);
    }

    /**
     * Roll a deterministic {@code [0,1)} float for (seed, chunk). Used as the
     * {@code randomMul} for a COE-tracked vein so the value is reproducible
     * across server restarts. {@link WorldgenRandom#setLargeFeatureSeed} is
     * the same primitive vanilla MC uses for feature placement and gives a
     * proper avalanche across neighbouring chunks.
     */
    private static float rollDeterministicMul(long worldSeed, ChunkPos cp) {
        WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
        rng.setLargeFeatureSeed(worldSeed, cp.x, cp.z);
        return rng.nextFloat();
    }

    /**
     * Override COE's vein-finder locate(): COE's default uses the recipe's
     * RandomSpreadStructurePlacement to predict positions, but we ignore
     * placement entirely (our picker uses core_spawn_probability + biome +
     * distance gradient). So COE's prediction always misses our actual
     * deposits.
     *
     * Our implementation has two phases:
     *   1. Scan saved deposits (DepositSavedData) — cheap, exact for anything
     *      already placed by player exploration.
     *   2. Dry-run our placer on each chunk in a spiral up to {@code radius},
     *      returning the nearest chunk where a deposit *would* be placed if
     *      the chunk were loaded. Deterministic (uses world seed) so the
     *      dry-run matches what would actually happen when player walks there.
     */
    @Override
    public Pair<BlockPos, RecipeHolder<VeinRecipe>> locate(
            BlockPos pPos, ServerLevel level, int radius,
            Predicate<RecipeHolder<VeinRecipe>> filter) {

        // Dimension gate — if the player's current dim isn't managed by us,
        // fall back to COE's default locate() so vanilla / third-party COE
        // veins can still be found. We *also* filter out any result whose
        // recipe is one of ours: our recipes are placed by the deposit-blob
        // algorithm, so a managed recipe returned by super.locate() in a
        // disabled dimension is a phantom — the spread placement formula
        // computes a chunk position but no actual deposit was placed there.
        ResourceLocation dim = level.dimension().location();
        if (!Config.isDimensionEnabled(dim)) {
            Pair<BlockPos, RecipeHolder<VeinRecipe>> fromCoe = super.locate(pPos, level, radius, filter);
            if (fromCoe == null) return null;
            if (Coedeposits.DEPOSIT_TYPES.managedVeinRecipes().contains(fromCoe.getSecond().id())) {
                return null;  // phantom from one of our managed recipes — discard
            }
            return fromCoe;
        }

        DepositSavedData store = DepositSavedData.get(level);

        // COE hard-codes radius=16 in OreVeinFinderItem.detect(); too tight for
        // our model (density 0.5–5% × biome/distance filters). Bump.
        int searchRadius = Math.max(radius, 64);          // chunks
        int searchBlockRadius = searchRadius * 16;        // blocks

        BlockPos best = null;
        RecipeHolder<VeinRecipe> bestRecipe = null;
        float bestDist = Float.MAX_VALUE;

        // Phase 1: known saved deposits within radius — cheap exact lookup.
        // store is per-level — no cross-dimension leakage possible here.
        for (Deposit dep : store.all().values()) {
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
            if (type == null) continue;
            // Per-type dimension allow-list, in case a saved deposit's type
            // has since been narrowed to other dimensions via config edit.
            if (!type.matchesDimension(dim)) continue;
            RecipeHolder<VeinRecipe> rh = resolveRecipe(level, type.veinRecipe());
            if (rh == null || !filter.test(rh)) continue;
            BlockPos core = new BlockPos(
                    dep.core().getMiddleBlockX(), pPos.getY(), dep.core().getMiddleBlockZ());
            float d = RandomSpreadGenerator.distance2d(core, pPos);
            if (d > searchBlockRadius) continue;          // ignore far saved
            if (d < bestDist) { bestDist = d; best = core; bestRecipe = rh; }
        }

        // Phase 2: dry-run picker on rings outward, stop early when current best
        // is closer than any chunk on the current ring can be (r*16 blocks min).
        long worldSeed = DepositSavedData.get(level).effectiveSeed(level);
        BlockPos spawn = level.getSharedSpawnPos();
        float baseR = uk.niknik.coedeposits.Config.BASE_RADIUS.get().floatValue();
        float maxR = uk.niknik.coedeposits.Config.MAX_RADIUS.get().floatValue();
        float prob = uk.niknik.coedeposits.Config.CORE_SPAWN_PROBABILITY.get().floatValue();
        ChunkPos centerCp = new ChunkPos(pPos);

        for (int r = 0; r <= searchRadius; r++) {
            if (r * 16 >= bestDist) break;                // Phase 1 already won
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    ChunkPos cp = new ChunkPos(centerCp.x + dx, centerCp.z + dz);
                    BlockPos center = new BlockPos(
                            cp.getMiddleBlockX(), pPos.getY(), cp.getMiddleBlockZ());
                    Holder<Biome> biome = level.getNoiseBiome(
                            QuartPos.fromBlock(center.getX()),
                            QuartPos.fromBlock(64),
                            QuartPos.fromBlock(center.getZ()));
                    DepositPlacer.Result result = DepositPlacer.tryPick(
                            cp, spawn, worldSeed, Coedeposits.DEPOSIT_TYPES,
                            baseR, maxR, prob, biome,
                            level.dimension().location());
                    if (result == null) continue;
                    RecipeHolder<VeinRecipe> rh = resolveRecipe(level, result.type().veinRecipe());
                    if (rh == null || !filter.test(rh)) continue;
                    float d = RandomSpreadGenerator.distance2d(center, pPos);
                    if (d < bestDist) { bestDist = d; best = center; bestRecipe = rh; }
                }
            }
        }
        return best != null ? Pair.of(best, bestRecipe) : null;
    }

    /** Same as {@link #resolveRecipeValue} but returns the holder for COE's API. */
    @SuppressWarnings("unchecked")
    private static RecipeHolder<VeinRecipe> resolveRecipe(ServerLevel lvl, ResourceLocation id) {
        return lvl.getRecipeManager().byKey(id)
                .filter(r -> r.value() instanceof VeinRecipe)
                .map(r -> (RecipeHolder<VeinRecipe>) r)
                .orElse(null);
    }

    /**
     * Notify all online players of a new deposit. The sync packet routing
     * is reveal-mode aware:
     * <ul>
     *   <li>ALWAYS / ON_PROXIMITY — sent to every online player; for proximity
     *       the client filters by distance at render time.</li>
     *   <li>ON_DISCOVERY / ON_PROSPECT — not sent yet; the player-specific
     *       reveal listeners ({@link uk.niknik.coedeposits.event.PlayerRoamProspectListener},
     *       {@link uk.niknik.coedeposits.event.VeinFinderListener}) will dispatch
     *       a one-shot sync to the discovering player when the threshold is met.</li>
     * </ul>
     * Chat-notification still gated by the type's effective reveal mode:
     * only ALWAYS triggers a server-wide chat blast on placement.
     */
    public static void broadcastDiscovery(ServerLevel lvl, Deposit dep) {
        DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
        Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();

        // Per-player reveal modes (ON_DISCOVERY/ON_PROSPECT) wait for player
        // action — don't push the snapshot to anyone yet. Other modes blast
        // the snapshot to every online player.
        if (!mode.isPerPlayer()) {
            CoedepositsNetwork.broadcastSync(
                    lvl.getServer(),
                    new uk.niknik.coedeposits.network.DepositSyncPayload(
                            java.util.List.of(
                                    uk.niknik.coedeposits.network.DepositSnapshot.fromDeposit(lvl, dep))));
        }

        if (mode != Config.RevealMode.ALWAYS) return;
        BlockPos pos = new BlockPos(
                dep.core().getMiddleBlockX(),
                lvl.getSharedSpawnPos().getY(),
                dep.core().getMiddleBlockZ());
        CoedepositsNetwork.broadcastDiscovery(
                lvl.getServer(),
                new DepositDiscoveryPayload(dep.name(), pos, dep.typeId()));
    }

    /**
     * Replace COE's static {@code OreVeinGenerator.picker} with a fresh
     * instance of this class via reflection. Called on server start and after
     * data-pack sync so we stay installed across reloads.
     */
    @SuppressWarnings("unchecked")
    public static void install(ServerLevel level) {
        try {
            CoedepositsPicker p = new CoedepositsPicker();
            p.loadAll(level);
            // AT opens OreVeinGenerator.picker to public at runtime; compile-classpath
            // still sees it private, so we reach through reflection.
            java.lang.reflect.Field f = OreVeinGenerator.class.getDeclaredField("picker");
            f.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicReference<RandomSpreadGenerator>) f.get(null)).set(p);
            Coedeposits.LOGGER.info("[coedeposits] installed CoedepositsPicker on OreVeinGenerator");
        } catch (ReflectiveOperationException e) {
            Coedeposits.LOGGER.error("[coedeposits] failed to install picker", e);
        }
    }
}
