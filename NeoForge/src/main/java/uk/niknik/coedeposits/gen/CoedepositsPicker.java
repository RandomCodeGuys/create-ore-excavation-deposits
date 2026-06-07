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

        // ── Gate: only act on managed dimensions ────────────────────────────
        // Dimensions outside enabled_dimensions get pure vanilla COE behaviour —
        // we don't suppress anything, but also don't track.
        if (!Config.isDimensionEnabled(dim)) {
            return null;
        }

        DepositSavedData store = DepositSavedData.get(lvl);

        // ── Phase 1: re-apply OreData for chunks of known deposits ──────────
        // The fast path: SavedData already says this chunk is part of a
        // deposit. For MANAGED deposits we re-roll the chunk's recipe
        // deterministically (rollChunkRecipe seeds on chunk pos so the same
        // chunk always picks the same recipe across reloads); filler chunks
        // get NO OreData (drill yields nothing, marker rendered as tailings).
        // COE-mode deposits skip the roll — COE chose the recipe at
        // placement time, we just re-apply the type's first recipe (COE-mode
        // types typically declare a single recipe; multi-recipe COE-mode
        // types degrade to "first recipe" semantics, acceptable for the rare
        // case). Return null either way so COE doesn't overwrite.
        Deposit existing = store.lookup(cp);
        if (existing != null) {
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(existing.typeId());
            // Re-register a lost implicit type for a previously-adopted foreign
            // COE vein. Implicit types are in-memory (cleared on reload, gone on
            // restart); without this a saved adopted deposit would skip its
            // OreData re-apply after a restart, leaving the vein empty.
            if (type == null && existing.placement() == DepositType.Placement.COE
                    && resolveRecipeValue(lvl, existing.typeId()) != null) {
                type = Coedeposits.DEPOSIT_TYPES.adoptImplicit(existing.typeId());
            }
            if (type != null && !type.veinRecipes().isEmpty()) {
                ResourceLocation recipeId;
                if (existing.placement() == DepositType.Placement.COE) {
                    // COE chose this chunk's recipe at placement; use the
                    // type's reference recipe to re-apply. No per-chunk roll.
                    recipeId = type.veinRecipes().get(0).recipe();
                } else {
                    // MANAGED: per-chunk weighted roll, may be filler.
                    recipeId = DepositPlacer.rollChunkRecipe(
                            type, store.effectiveSeed(lvl), cp).orElse(null);
                }
                if (recipeId != null) {
                    float perChunk = existing.amountMulFor(
                            cp, Config.EDGE_AMOUNT_MUL.get().floatValue());
                    applyToOreData(chunk, recipeId, perChunk);
                }
                // else: filler chunk, intentionally leave OreData empty.
            }
            return null;
        }

        // ── Phase 2: managed blob placer ────────────────────────────────────
        // Try to place a new MANAGED deposit. tryPick decides:
        //   - whether this chunk wins the core_spawn_probability roll
        //   - if so, which managed type (weighted by config)
        //   - the Perlin blob covering this deposit
        // COE-placement types are filtered out inside tryPick — they only
        // appear via Phase 3's delegation path.
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
            // Step 2a: pick a REFERENCE recipe for amountMul math. Multi-recipe
            // types still store ONE amountMul on the Deposit — chunks of
            // different recipes use the same randomMul value, with small
            // unit-yield variance proportional to how different the recipes'
            // min/max are. The first weighted recipe is a stable reference.
            // Bail if no recipes (empty pool) or the reference can't be resolved.
            DepositType type = placed.type();
            if (type.veinRecipes().isEmpty()) {
                Coedeposits.LOGGER.warn(
                        "[coedeposits] picker dropped placement at {},{} — type {} has no vein_recipes",
                        cp.x, cp.z, placed.typeId());
                return null;
            }
            ResourceLocation referenceRecipeId = type.veinRecipes().get(0).recipe();
            VeinRecipe vr = resolveRecipeValue(lvl, referenceRecipeId);
            if (vr == null) {
                Coedeposits.LOGGER.warn(
                        "[coedeposits] picker dropped placement at {},{} — reference vein_recipe {} not loaded",
                        cp.x, cp.z, referenceRecipeId);
                return null;
            }

            // Step 2b: convert per_chunk_units → COE randomMul via the
            // reference recipe's min/max bounds. unbounded_growth kicks in
            // when per_chunk_units.max is absent so far-tier deposits scale
            // open-endedly.
            double targetUnits = type.perChunkUnits().computeTarget(
                    placed.tierFraction(),
                    Config.UNBOUNDED_GROWTH.get());
            int base = com.tom.createores.Config.finiteAmountBase;
            float amountMul = DepositPlacer.amountMulForTarget(
                    targetUnits, vr.getMinAmount(), vr.getMaxAmount(), base);

            // Step 2c: persist, resolving overlaps with existing deposits —
            // same-type blobs merge, different types yield to the rarer (lower
            // weight) one per contested chunk.
            Deposit candidate = new Deposit(
                    UUID.randomUUID(),
                    placed.typeId(),
                    nameFor(placed.typeId(), cp),
                    cp,
                    placed.chunks(),
                    amountMul,
                    placed.tierFraction(),
                    DepositType.Placement.MANAGED,
                    0.0);  // replenishRateOverride: defer to type's default
            DepositSavedData.OverlapResult res =
                    store.addResolvingOverlap(candidate, CoedepositsPicker::weightOf);
            Deposit dep = res.placed();
            if (dep == null) return null;  // every chunk lost to a rarer type

            // Step 2d: roll + apply OreData for the CORE chunk (the only chunk
            // we know is loaded right now). The core was unowned — Phase 1 caught
            // owned chunks — so it's always part of the resulting deposit. Other
            // blob chunks (and any trimmed-from-another-deposit chunks) get rolled
            // & applied via Phase 1 when they load.
            java.util.Optional<ResourceLocation> coreRecipe =
                    DepositPlacer.rollChunkRecipe(type, store.effectiveSeed(lvl), cp);
            if (coreRecipe.isPresent()) {
                float perChunkCore = dep.amountMulFor(
                        cp, Config.EDGE_AMOUNT_MUL.get().floatValue());
                applyToOreData(chunk, coreRecipe.get(), perChunkCore);
            }

            // Step 2e: log + sync the overlap result to clients.
            if (Config.LOG_PLACEMENT.get()) {
                Coedeposits.LOGGER.info(
                        "[coedeposits] placed {} at chunk {},{} | {} chunks | tier {} | target {} units/chunk peak (ref recipe {})",
                        placed.typeId(), cp.x, cp.z, dep.chunks().size(),
                        String.format("%.2f", placed.tierFraction()),
                        String.format("%,.0f", targetUnits),
                        referenceRecipeId);
            }
            syncOverlap(lvl, store, res, candidate.id());
            return null;
        }

        // ── Phase 3: COE delegation + adoption ──────────────────────────────
        // Run COE's own placement when EITHER a declared placement=COE type
        // exists OR auto-adopt is on (so foreign add-on/datapack veins can be
        // taken onto our map). Skipped entirely otherwise — pure-managed worlds
        // keep behaviour identical to pre-delegation versions.
        if (!hasAnyCoePlacementType() && !Config.AUTO_ADOPT_COE_VEINS.get()) {
            return null;
        }

        // Step 3a: let COE's own RandomSpreadGenerator make its choice. Null
        // means COE also passes — this chunk simply has no vein.
        RecipeHolder<VeinRecipe> superResult = super.pick(chunk);
        if (superResult == null) return null;

        ResourceLocation chosenVein = superResult.id();

        // Step 3b: defend against COE picking one of OUR managed vein recipes
        // via its own random spread. Managed types should only spawn through
        // the blob algorithm — otherwise a chunk that didn't roll a core
        // would get a single-chunk managed vein from COE, breaking the
        // deposit model (no blob, no gradient, no map entry).
        if (Coedeposits.DEPOSIT_TYPES.managedVeinRecipes().contains(chosenVein)) {
            return null;
        }

        // Step 3b.5: suppressed vein — admin disabled it (editor "Disable" or the
        // disabled_veins config). Return null so COE doesn't place it at all.
        if (Config.isVeinDisabled(chosenVein)) {
            return null;
        }

        // Step 3c: adoption path. Resolve the type that owns COE's chosen vein —
        // a declared placement=COE type, or (when auto-adopt is on) an implicit
        // type keyed by the vein id. Either way we take ownership of the OreData
        // and persist a single-chunk deposit so it shows on the map. Honour the
        // per-type dimensions allow-list — a COE entry restricted to specific
        // dims won't be tracked outside them (implicit types are dim-agnostic).
        ResourceLocation coeTypeId = Coedeposits.DEPOSIT_TYPES.coeTypeIdForVeinRecipe(chosenVein);
        DepositType coeType;
        if (coeTypeId != null) {
            coeType = Coedeposits.DEPOSIT_TYPES.get(coeTypeId);
        } else if (Config.AUTO_ADOPT_COE_VEINS.get()) {
            // Foreign vein (add-on / datapack) with no declared deposit_type —
            // adopt it under an implicit COE type whose id IS the vein id.
            coeTypeId = chosenVein;
            coeType = Coedeposits.DEPOSIT_TYPES.adoptImplicit(chosenVein);
        } else {
            coeType = null;
        }
        if (coeType != null && coeType.matchesDimension(dim)) {
            // Roll a deterministic randomMul so the same chunk produces the
            // same amount across server restarts (otherwise rejoining would
            // sometimes shift COE's natural variation).
            float amountMul = rollDeterministicMul(store.effectiveSeed(lvl), cp);
            applyToOreData(chunk, chosenVein, amountMul);

            Deposit dep = new Deposit(
                    UUID.randomUUID(),
                    coeTypeId,
                    nameFor(coeTypeId, cp),
                    cp,
                    Set.of(cp),  // COE deposits are single-chunk by definition
                    amountMul,
                    0f,          // tier irrelevant for COE-placed
                    DepositType.Placement.COE,
                    0.0);        // replenishRateOverride: defer to type's default
            store.add(dep);
            if (Config.LOG_PLACEMENT.get()) {
                Coedeposits.LOGGER.info(
                        "[coedeposits] tracked COE vein {} at chunk {},{} | dim {} | amountMul {}",
                        chosenVein, cp.x, cp.z, dim, String.format("%.2f", amountMul));
            }
            broadcastDiscovery(lvl, dep);
            return null;
        }

        // Step 3d: recipe isn't in our registry — pass it through so COE
        // places it normally. No map tracking, no SavedData entry. Behaves
        // like vanilla COE for third-party veins.
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
            // ── Step 1: get the chunk's OreDataAttachment ───────────────────
            // The attachment is auto-created if missing — chunk.getData()
            // returns the actual instance bound to this LevelChunk.
            OreDataAttachment at = chunk.getData(CreateOreExcavation.ORE_DATA);

            // ── Step 2: reflectively access the private `data` field ────────
            // OreDataAttachment.data is private. We must NOT call the public
            // OreDataAttachment.getData(chunk) here because it lazily triggers
            // OreData.populate() — which is the picker method we're called from.
            // Calling it would recurse infinitely. Reflection bypasses that.
            java.lang.reflect.Field f = OreDataAttachment.class.getDeclaredField("data");
            f.setAccessible(true);
            OreData od = (OreData) f.get(at);

            // ── Step 3: write the three OreData fields atomically ───────────
            // setExtractedAmount(0) gives refill semantics: every apply resets
            // extraction, which is what Phase 1 of pick() relies on when
            // re-applying for a chunk that was previously partially mined.
            // setLoaded(true) tells COE the data is initialized (otherwise it
            // would re-run populate on the next access).
            od.setRecipe(recipe);
            od.setRandomMul(amountMul);
            od.setExtractedAmount(0);
            od.setLoaded(true);
        } catch (ReflectiveOperationException e) {
            // Field name or visibility changed in a COE update — log and bail.
            // Picker will keep returning null but no OreData will land, so
            // chunks visually show as empty until COE gets patched.
            Coedeposits.LOGGER.error("[coedeposits] failed to apply OreData for chunk {}",
                    chunk.getPos(), e);
        }
    }

    /** Cached handle to {@code OreData.extractedAmount} (no public getter). */
    private static java.lang.reflect.Field extractedAmountField;

    /**
     * Reflectively read {@code OreData.extractedAmount} (COE exposes no getter).
     * Used by the depletion sweep's self-heal to tell a never-applied deposit
     * chunk (extracted == 0) from a genuinely mined-out one (extracted &gt; 0).
     * On reflection failure returns {@link Long#MAX_VALUE} so the caller treats
     * the chunk as "extracted" and does NOT refill it (fail safe, never re-fills).
     */
    public static long getExtractedAmount(OreData od) {
        try {
            java.lang.reflect.Field f = extractedAmountField;
            if (f == null) {
                f = OreData.class.getDeclaredField("extractedAmount");
                f.setAccessible(true);
                extractedAmountField = f;
            }
            return f.getLong(od);
        } catch (ReflectiveOperationException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Recipe lookup helper used by ProspectScanner, the depletion sweep, and the picker. */
    @SuppressWarnings("unchecked")
    public static VeinRecipe resolveRecipeValue(ServerLevel lvl, ResourceLocation id) {
        return lvl.getRecipeManager().byKey(id)
                .filter(r -> r.value() instanceof VeinRecipe)
                .map(r -> ((RecipeHolder<VeinRecipe>) r).value())
                .orElse(null);
    }

    /** Build a human-readable deposit label like {@code "iron@128,256"}. */
    private static String nameFor(ResourceLocation typeId, ChunkPos cp) {
        return typeId.getPath() + "@" + (cp.x * 16) + "," + (cp.z * 16);
    }

    /** Weight of a deposit type — Integer.MAX_VALUE for an unknown type (treated as most common, so it always yields). */
    static int weightOf(ResourceLocation typeId) {
        DepositType t = Coedeposits.DEPOSIT_TYPES.get(typeId);
        return t != null ? t.weight() : Integer.MAX_VALUE;
    }

    /**
     * Push a {@link DepositSavedData.OverlapResult} to clients: removals for
     * absorbed deposits, a reveal-aware re-sync for trimmed/merged deposits, and
     * a full discovery (chat + sync) for a genuinely new deposit (one whose id
     * still equals the candidate's). Trimmed chunks re-apply their OreData lazily
     * when they next load through {@link #pick}'s Phase 1.
     */
    static void syncOverlap(ServerLevel lvl, DepositSavedData store,
                            DepositSavedData.OverlapResult res, UUID candidateId) {
        if (!res.removed().isEmpty()) {
            CoedepositsNetwork.broadcastRemoval(lvl, new java.util.ArrayList<>(res.removed()));
        }
        java.util.List<Deposit> changed = new java.util.ArrayList<>();
        for (UUID id : res.changed()) {
            Deposit d = store.all().get(id);
            if (d != null) changed.add(d);
        }
        if (!changed.isEmpty()) {
            CoedepositsNetwork.broadcastSyncFiltered(lvl.getServer(), lvl, changed);
        }
        Deposit placed = res.placed();
        if (placed != null && placed.id().equals(candidateId)) {
            broadcastDiscovery(lvl, placed);  // genuinely new deposit
        }
    }

    /**
     * Roll a deterministic {@code [0,1)} float for (seed, chunk). Used as the
     * {@code randomMul} for a COE-tracked vein so the value is reproducible
     * across server restarts. {@link WorldgenRandom#setLargeFeatureSeed} is
     * the same primitive vanilla MC uses for feature placement and gives a
     * proper avalanche across neighbouring chunks.
     */
    static float rollDeterministicMul(long worldSeed, ChunkPos cp) {
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

        // ── Gate: dimension allow-list ──────────────────────────────────────
        // For dimensions we don't manage, fall back to COE's default locate()
        // so vanilla / third-party COE veins can still be found. We *also*
        // filter out any result whose recipe is one of ours: our recipes are
        // placed by the deposit-blob algorithm, so a managed recipe returned
        // by super.locate() in a disabled dimension is a phantom — the spread
        // placement formula computes a chunk position but no actual deposit
        // was placed there.
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
        // our model (density 0.5–5% × biome/distance filters). Bump so the
        // Vein Finder reliably hits something within a few uses.
        int searchRadius = Math.max(radius, 64);          // chunks
        int searchBlockRadius = searchRadius * 16;        // blocks

        // Running best — updated by both phases below. Phase 2's ring scan
        // shortcuts whenever it can prove no closer match is possible.
        BlockPos best = null;
        RecipeHolder<VeinRecipe> bestRecipe = null;
        float bestDist = Float.MAX_VALUE;

        // ── Phase 1: scan saved deposits ────────────────────────────────────
        // Cheap exact lookup — anything already placed by exploration or by
        // the prospect scanner is here. Per-level store, no cross-dimension
        // leakage possible. Multi-recipe-aware: a deposit matches if ANY of
        // its constituent recipes passes the finder's filter — so an
        // iron+copper deposit shows up for both "find iron" and "find copper"
        // queries.
        for (Deposit dep : store.all().values()) {
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
            if (type == null) continue;
            // Per-type dimension allow-list, in case a saved deposit's type
            // has since been narrowed to other dimensions via config edit.
            if (!type.matchesDimension(dim)) continue;
            RecipeHolder<VeinRecipe> rh = firstMatchingRecipe(level, type, filter);
            if (rh == null) continue;
            BlockPos core = new BlockPos(
                    dep.core().getMiddleBlockX(), pPos.getY(), dep.core().getMiddleBlockZ());
            float d = RandomSpreadGenerator.distance2d(core, pPos);
            if (d > searchBlockRadius) continue;          // ignore far saved
            if (d < bestDist) { bestDist = d; best = core; bestRecipe = rh; }
        }

        // ── Phase 2: dry-run picker on rings outward ────────────────────────
        // Walks concentric rings around the player's chunk, running tryPick
        // dry on each candidate chunk. Stops early when the running best
        // (from Phase 1 or earlier rings) is closer than any chunk on the
        // current ring could be (ring's inner distance is r*16 blocks).
        long worldSeed = DepositSavedData.get(level).effectiveSeed(level);
        BlockPos spawn = level.getSharedSpawnPos();
        float baseR = uk.niknik.coedeposits.Config.BASE_RADIUS.get().floatValue();
        float maxR = uk.niknik.coedeposits.Config.MAX_RADIUS.get().floatValue();
        float prob = uk.niknik.coedeposits.Config.CORE_SPAWN_PROBABILITY.get().floatValue();
        ChunkPos centerCp = new ChunkPos(pPos);

        for (int r = 0; r <= searchRadius; r++) {
            // Early-out: a chunk on ring r is at least r*16 blocks away. If
            // bestDist is already smaller, no point checking further rings.
            if (r * 16 >= bestDist) break;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // Only iterate the ring boundary (Chebyshev distance == r).
                    // Inner cells were covered by earlier ring iterations.
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    ChunkPos cp = new ChunkPos(centerCp.x + dx, centerCp.z + dz);
                    BlockPos center = new BlockPos(
                            cp.getMiddleBlockX(), pPos.getY(), cp.getMiddleBlockZ());
                    // Use main-thread biome lookup here — locate() runs from
                    // Brigadier command thread which has level access. Off-thread
                    // path (BiomeSource direct) is only needed by ProspectScanQueue.
                    Holder<Biome> biome = level.getNoiseBiome(
                            QuartPos.fromBlock(center.getX()),
                            QuartPos.fromBlock(64),
                            QuartPos.fromBlock(center.getZ()));
                    DepositPlacer.Result result = DepositPlacer.tryPick(
                            cp, spawn, worldSeed, Coedeposits.DEPOSIT_TYPES,
                            baseR, maxR, prob, biome,
                            level.dimension().location());
                    if (result == null) continue;
                    // Multi-recipe-aware match — same any-recipe logic as Phase 1.
                    RecipeHolder<VeinRecipe> rh = firstMatchingRecipe(level, result.type(), filter);
                    if (rh == null) continue;
                    float d = RandomSpreadGenerator.distance2d(center, pPos);
                    if (d < bestDist) { bestDist = d; best = center; bestRecipe = rh; }
                }
            }
        }
        // ── Phase 3: COE-generation veins via COE's own locate ──────────────
        // Declared placement=COE types + auto-adopted foreign veins are placed by
        // COE's spread. Reuse COE's exact locate (it owns the spread math) to find
        // the nearest one the finder query wants, filtered to recipes we adopt
        // (never a managed recipe — those come from Phases 1-2). Already-placed COE
        // deposits were caught by Phase 1; this extends finder reach to ones not
        // yet generated / prospected.
        boolean autoAdopt = Config.AUTO_ADOPT_COE_VEINS.get();
        if (autoAdopt || Coedeposits.DEPOSIT_TYPES.hasCoePlacementType()) {
            Pair<BlockPos, RecipeHolder<VeinRecipe>> coe = super.locate(pPos, level, searchRadius, rh -> {
                if (!filter.test(rh)) return false;
                ResourceLocation rid = rh.id();
                if (Coedeposits.DEPOSIT_TYPES.managedVeinRecipes().contains(rid)) return false;
                return autoAdopt || Coedeposits.DEPOSIT_TYPES.coeTypeIdForVeinRecipe(rid) != null;
            });
            if (coe != null) {
                float d = RandomSpreadGenerator.distance2d(coe.getFirst(), pPos);
                if (d < bestDist) { bestDist = d; best = coe.getFirst(); bestRecipe = coe.getSecond(); }
            }
        }

        return best != null ? Pair.of(best, bestRecipe) : null;
    }

    /**
     * Walk a type's vein-recipes list and return the first one that resolves
     * AND passes the finder's filter. Used by {@link #locate} to support
     * multi-recipe types — the finder considers a deposit a match if any of
     * its recipes satisfies the query. Returns null when no recipe matches
     * (deposit isn't findable by this query).
     */
    private static RecipeHolder<VeinRecipe> firstMatchingRecipe(
            ServerLevel lvl, DepositType type, Predicate<RecipeHolder<VeinRecipe>> filter) {
        for (DepositType.WeightedRecipe wr : type.veinRecipes()) {
            RecipeHolder<VeinRecipe> rh = resolveRecipe(lvl, wr.recipe());
            if (rh != null && filter.test(rh)) return rh;
        }
        return null;
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
        // ── Step 1: resolve the effective reveal mode for this deposit ──────
        // Per-type `reveal` override wins; falls back to the global
        // REVEAL_MODE config when the type was deleted from deposits.json
        // (defensive — broadcastDiscovery may be called for legacy SavedData
        // entries whose type is no longer registered).
        DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
        Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();

        // ── Step 2: bulk-sync the snapshot for non-per-player modes ─────────
        // ALWAYS / ON_PROXIMITY are world-visible; everyone gets the data and
        // the client renderer applies the proximity filter at draw time.
        // ON_DISCOVERY / ON_PROSPECT skip this — the deposit stays hidden
        // until the player triggers their own per-player reveal listener.
        if (!mode.isPerPlayer()) {
            CoedepositsNetwork.broadcastSync(
                    lvl.getServer(),
                    new uk.niknik.coedeposits.network.DepositSyncPayload(
                            java.util.List.of(
                                    uk.niknik.coedeposits.network.DepositSnapshot.fromDeposit(lvl, dep))));
        }

        // ── Step 3: server-wide chat notification, only for ALWAYS mode ─────
        // The other modes are "discover-by-action" by definition — pushing a
        // chat blast on placement would spoil that. Build the chat coord on
        // spawn Y to give players a sensible vertical reference even when the
        // deposit's chunks span a wide Y range.
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
            // ── Step 1: build a fresh picker + warm its recipe cache ────────
            // loadAll iterates the level's recipe manager and pre-resolves
            // vein recipes COE will need — avoids first-pick latency.
            CoedepositsPicker p = new CoedepositsPicker();
            p.loadAll(level);

            // ── Step 2: swap COE's static picker AtomicReference ────────────
            // OreVeinGenerator.picker is a private static
            // AtomicReference<RandomSpreadGenerator>. The AT (accesstransformer)
            // opens it at runtime so the cast below works, but the
            // compile-classpath still sees it as private — reflection bridges
            // that gap. Atomic write means concurrent pick() calls observe
            // either the old or new picker, never a torn state.
            java.lang.reflect.Field f = OreVeinGenerator.class.getDeclaredField("picker");
            f.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicReference<RandomSpreadGenerator>) f.get(null)).set(p);
            if (Config.LOG_LIFECYCLE.get()) {
                Coedeposits.LOGGER.info("[coedeposits] installed CoedepositsPicker on OreVeinGenerator");
            }
        } catch (ReflectiveOperationException e) {
            // Field name or access changed in COE — log loudly because without
            // the picker installed our deposit model silently doesn't apply
            // (vanilla COE behaviour resumes, players see no managed blobs).
            Coedeposits.LOGGER.error("[coedeposits] failed to install picker", e);
        }
    }
}
