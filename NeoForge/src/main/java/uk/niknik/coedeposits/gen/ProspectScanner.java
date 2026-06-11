package uk.niknik.coedeposits.gen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import com.tom.createores.CreateOreExcavation;
import com.tom.createores.recipe.VeinRecipe;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Stateless scan helpers — split into <b>dry-run</b> (off-thread safe) and
 * <b>materialize</b> (main-thread only) phases so {@link ProspectScanQueue}
 * can move the picker work off the server tick.
 *
 * <p>Two entry points coexist:
 * <ul>
 *   <li>{@link #dryRunChunk(ScanSnapshot, ChunkPos)} + {@link #materialize(ServerLevel, PendingPlacement)}
 *       — used by {@link ProspectScanQueue}. The dry run is pure (RNG + biome
 *       lookup via {@link BiomeSource} only, no chunk loading) and can run on
 *       a worker thread. The materialize step writes SavedData and patches
 *       OreData on any currently-loaded blob chunks; must run on the server
 *       thread.</li>
 *   <li>{@link #scan(ServerLevel, int)} / {@link #scanAround(ServerLevel, BlockPos, int)}
 *       — legacy synchronous sweep. Kept for {@code /coedeposits regenerate}
 *       and {@code /coedeposits scan} commands that explicitly want a blocking
 *       sweep. Internally delegates to the same dry-run + materialize methods,
 *       just in a tight loop.</li>
 * </ul>
 *
 * <p>Both paths are idempotent — chunks already in SavedData are skipped, so
 * re-runs only fill in newly-eligible chunks (e.g., after a config bump).
 */
public final class ProspectScanner {
    private ProspectScanner() {}

    /**
     * Immutable snapshot of all level/config state the dry-run phase needs.
     * Captured on the server thread (typically inside {@link ProspectScanQueue#enqueue})
     * and read freely from a worker thread thereafter. None of the referenced
     * objects are mutated by the scanner itself; they only mutate via reload
     * events which the queue tolerates by re-checking on materialize.
     *
     * @param spawn         world spawn — anchor for the distance gradient
     * @param worldSeed     effective deposit seed (override or world seed)
     * @param dimension     dimension id, used by per-type {@code dimensions} filter
     * @param baseR         {@link Config#BASE_RADIUS} snapshot
     * @param maxR          {@link Config#MAX_RADIUS} snapshot
     * @param prob          {@link Config#CORE_SPAWN_PROBABILITY} snapshot
     * @param biomeSource   chunk-generator biome source — sampled directly to
     *                       avoid blocking chunk loads
     * @param sampler       {@link Climate.Sampler} from the level's
     *                       {@code RandomState}, required by {@link BiomeSource#getNoiseBiome}
     */
    public record ScanSnapshot(
            BlockPos spawn,
            long worldSeed,
            long coeWorldSeed,
            net.minecraft.resources.ResourceLocation dimension,
            float baseR,
            float maxR,
            float prob,
            int coeMinQuartY,
            int coeMaxQuartY,
            BiomeSource biomeSource,
            Climate.Sampler sampler,
            List<CoeVein> coeVeins) {

        /**
         * Build a snapshot from live level state. Must be called on the server
         * thread because {@link DepositSavedData#get} and the recipe manager
         * insist on it; the resulting record is safe to hand to any thread.
         *
         * <p>{@code worldSeed} is the effective deposit seed (override or world)
         * for the MANAGED blob roll; {@code coeWorldSeed} is the raw world seed
         * COE's own spread uses (never the override), so adopted COE veins
         * predict where COE actually places them.
         */
        public static ScanSnapshot capture(ServerLevel lvl) {
            DepositSavedData store = DepositSavedData.get(lvl);
            int minQ = QuartPos.fromBlock(lvl.getMinBuildHeight());
            // COE's pick() uses maxY = minY + fromBlock(height) - 1 as the nextInt
            // bound for its jittered biome Y; replicate the exact value.
            int maxQ = minQ + QuartPos.fromBlock(lvl.getHeight()) - 1;
            return new ScanSnapshot(
                    lvl.getSharedSpawnPos(),
                    store.effectiveSeed(lvl),
                    lvl.getSeed(),
                    lvl.dimension().location(),
                    Config.BASE_RADIUS.get().floatValue(),
                    Config.MAX_RADIUS.get().floatValue(),
                    Config.CORE_SPAWN_PROBABILITY.get().floatValue(),
                    minQ, maxQ,
                    lvl.getChunkSource().getGenerator().getBiomeSource(),
                    lvl.getChunkSource().randomState().sampler(),
                    captureCoeVeins(lvl));
        }
    }

    /**
     * One captured COE-generation vein recipe — everything the off-thread dry run
     * needs to replicate COE's {@link com.tom.createores.util.RandomSpreadGenerator#pick}
     * placement decision (spread structure-chunk match + jittered biome
     * whitelist/blacklist) without touching the recipe manager. The list is held
     * in COE's iteration order (priority desc, then id) so the FIRST spatial+biome
     * match is the recipe COE would actually place.
     *
     * @param typeId    deposit-type id recorded on the adopted deposit: a declared
     *                  placement=COE type id, or the vein id itself (implicit adopt)
     * @param placement COE spread placement from the vein recipe
     * @param whitelist any-of biome-whitelist tag, or {@code null}
     * @param blacklist biome-blacklist tag, or {@code null}
     * @param managed   true if a MANAGED type owns this recipe — it still
     *                  participates in COE's iteration (can win/suppress a chunk)
     *                  but is never adopted (managed spawns only via the blob path)
     * @param adoptable emit a COE deposit on match (declared COE type or auto-adopt)
     */
    public record CoeVein(ResourceLocation typeId, RandomSpreadStructurePlacement placement,
            TagKey<Biome> whitelist, TagKey<Biome> blacklist, boolean managed, boolean adoptable) {}

    /**
     * Snapshot every COE vein recipe in COE's own iteration order (priority desc,
     * then id), tagging each managed (suppress) / adoptable (declared-COE or
     * auto-adopt). Empty when auto-adopt is off AND no declared COE type exists —
     * the dry run then skips the COE branch entirely.
     */
    private static List<CoeVein> captureCoeVeins(ServerLevel lvl) {
        boolean autoAdopt = Config.AUTO_ADOPT_COE_VEINS.get();
        if (!autoAdopt && !Coedeposits.DEPOSIT_TYPES.hasCoePlacementType()) return List.of();
        Set<ResourceLocation> managed = Coedeposits.DEPOSIT_TYPES.managedVeinRecipes();
        List<RecipeHolder<VeinRecipe>> all = new ArrayList<>(
                lvl.getRecipeManager().getAllRecipesFor(CreateOreExcavation.VEIN_RECIPES.getRecipeType()));
        // COE order: negGenerationPriority asc (= priority desc), then id.
        all.sort(Comparator.<RecipeHolder<VeinRecipe>>comparingInt(h -> h.value().getNegGenerationPriority())
                .thenComparing(RecipeHolder::id));
        List<CoeVein> out = new ArrayList<>(all.size());
        for (RecipeHolder<VeinRecipe> h : all) {
            ResourceLocation veinId = h.id();
            boolean isManaged = managed.contains(veinId);
            // Disabled veins (explicit list, or base-COE under the default-off
            // rule) are suppressed like managed ones (adoptable=false →
            // dryRunCoeVein returns null on a match, so nothing generates there).
            // A DECLARED type referencing the vein overrides the disable —
            // promoting a vein in the editor is explicit intent.
            ResourceLocation declaredType = Coedeposits.DEPOSIT_TYPES.coeTypeIdForVeinRecipe(veinId);
            boolean disabled = declaredType == null && Config.isVeinDisabled(veinId);
            boolean adoptable = !isManaged && !disabled && (declaredType != null || autoAdopt);
            ResourceLocation typeId = declaredType != null ? declaredType : veinId;
            // Foreign adoptable vein (no declared type): register its implicit
            // type NOW (server thread) so materialize()'s DEPOSIT_TYPES.get(typeId)
            // resolves it — otherwise the prospect path would silently drop every
            // adopted deposit (the chunk-load path calls adoptImplicit itself).
            if (adoptable && declaredType == null) {
                Coedeposits.DEPOSIT_TYPES.adoptImplicit(veinId);
            }
            VeinRecipe r = h.value();
            out.add(new CoeVein(typeId, r.getPlacement(),
                    r.biomeWhitelist().orElse(null), r.biomeBlacklist().orElse(null),
                    isManaged, adoptable));
        }
        return out;
    }

    /**
     * Pure outcome of a successful dry run — what the materialize phase needs
     * to turn the candidate into a persisted {@link Deposit}. Carries enough
     * to reconstruct the deposit on the main thread without needing the
     * original snapshot.
     *
     * @param coreChunk     chunk that rolled the core
     * @param typeId        chosen deposit type id (re-resolved on materialize
     *                       against the live registry; type may have been removed
     *                       by an intervening /reload — materialize drops silently
     *                       if so)
     * @param chunks        Perlin-blob chunk set covering the deposit
     * @param tierFraction  distance gradient tier in [0,1]
     */
    public record PendingPlacement(
            ChunkPos coreChunk,
            net.minecraft.resources.ResourceLocation typeId,
            Set<ChunkPos> chunks,
            float tierFraction,
            DepositType.Placement placement) {}

    /**
     * Off-thread-safe per-chunk dry run. Samples the biome directly from the
     * snapshot's {@link BiomeSource} (no chunk load), runs the placer's
     * deterministic eligibility/weight roll, and returns a pending placement
     * if a core was decided.
     *
     * <p>Returns {@code null} when the chunk didn't roll a core, no type
     * matched its biome/distance/dimension filters, or the placer otherwise
     * declined. Safe to call from any thread — no level state is mutated.
     */
    public static PendingPlacement dryRunChunk(ScanSnapshot snap, ChunkPos cp) {
        BlockPos chunkCenter = new BlockPos(
                cp.getMiddleBlockX(), snap.spawn().getY(), cp.getMiddleBlockZ());
        Holder<Biome> biome = snap.biomeSource().getNoiseBiome(
                QuartPos.fromBlock(chunkCenter.getX()),
                QuartPos.fromBlock(64),
                QuartPos.fromBlock(chunkCenter.getZ()),
                snap.sampler());

        // Managed blob first — mirrors pick()'s Phase 2 precedence over the COE
        // delegation in Phase 3.
        DepositPlacer.Result result = DepositPlacer.tryPick(
                cp, snap.spawn(), snap.worldSeed(), Coedeposits.DEPOSIT_TYPES,
                snap.baseR(), snap.maxR(), snap.prob(), biome,
                snap.dimension());
        if (result != null) {
            return new PendingPlacement(cp, result.typeId(), result.chunks(),
                    result.tierFraction(), DepositType.Placement.MANAGED);
        }

        // COE-spread path — replicate COE's own pick() so the prospect map and
        // the regenerate sweep pre-populate declared / auto-adopted COE veins
        // ahead of the player, exactly where COE will place them on chunk-load.
        return dryRunCoeVein(snap, cp);
    }

    /**
     * Off-thread replica of COE {@code RandomSpreadGenerator.pick} for the
     * COE-generation veins captured in {@link ScanSnapshot#coeVeins}. Iterates
     * them in COE's priority order; the FIRST whose spread structure-chunk equals
     * {@code cp} AND whose jittered biome passes its whitelist/blacklist is COE's
     * choice. If that choice is a managed recipe (it competes in COE's iteration)
     * or a non-adoptable foreign vein, COE places nothing we track here — stop.
     */
    private static PendingPlacement dryRunCoeVein(ScanSnapshot snap, ChunkPos cp) {
        if (snap.coeVeins().isEmpty()) return null;
        for (CoeVein cv : snap.coeVeins()) {
            ChunkPos pot = cv.placement().getPotentialStructureChunk(snap.coeWorldSeed(), cp.x, cp.z);
            if (pot.x != cp.x || pot.z != cp.z) continue;
            // Jittered biome — identical RNG draw order to COE's pick():
            // nextInt(4) [x], minY + nextInt(maxY) [y], nextInt(4) [z].
            WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
            rng.setLargeFeatureSeed(snap.coeWorldSeed(), cp.x, cp.z);
            int qx = QuartPos.fromSection(cp.x) + rng.nextInt(4);
            int qy = snap.coeMinQuartY() + rng.nextInt(Math.max(1, snap.coeMaxQuartY()));
            int qz = QuartPos.fromSection(cp.z) + rng.nextInt(4);
            Holder<Biome> biome = snap.biomeSource().getNoiseBiome(qx, qy, qz, snap.sampler());
            if (!coeCanGenerate(cv, biome)) continue;
            // First spatial + biome match is COE's choice. Don't fall through to a
            // lower-priority COE recipe COE itself would never reach.
            if (cv.managed() || !cv.adoptable()) return null;
            return new PendingPlacement(cp, cv.typeId(), Set.of(cp), 0f, DepositType.Placement.COE);
        }
        return null;
    }

    /** Replica of {@code VeinRecipe.canGenerate} using a captured biome holder (off-thread safe). */
    private static boolean coeCanGenerate(CoeVein cv, Holder<Biome> biome) {
        if (cv.blacklist() != null && biome.is(cv.blacklist())) return false;
        if (cv.whitelist() != null) return biome.is(cv.whitelist());
        return true;
    }

    /**
     * Main-thread materialize step — turns a pending placement into a saved
     * deposit and writes OreData for any of the blob's chunks that are
     * currently loaded. Safe to no-op when:
     * <ul>
     *   <li>the core chunk has been claimed in the meantime (picker / another
     *       materialize call landed there first);</li>
     *   <li>the type was removed by {@code /reload} between dry-run and
     *       materialize;</li>
     *   <li>the vein recipe is missing from the recipe manager.</li>
     * </ul>
     *
     * @return {@code true} when a new deposit was actually persisted; {@code false}
     *         when the call was a no-op for one of the reasons above
     */
    public static boolean materialize(ServerLevel lvl, PendingPlacement p) {
        DepositSavedData store = DepositSavedData.get(lvl);
        if (store.isOccupied(p.coreChunk())) return false;

        DepositType type = Coedeposits.DEPOSIT_TYPES.get(p.typeId());
        if (type == null) return false;
        if (type.veinRecipes().isEmpty()) return false;  // misconfig, no pool

        // COE-spread placement: single-chunk adopted deposit. Same ownership as a
        // chunk-load adoption (Phase 3c) — we write OreData + persist; on reload
        // Phase 1 re-applies it. amountMul uses the deterministic per-chunk roll
        // so a prospect-materialized COE vein matches its eventual on-load value.
        if (p.placement() == DepositType.Placement.COE) {
            return materializeCoe(lvl, store, p, type);
        }

        // Reference recipe for amountMul math — same convention as Phase 2 of
        // CoedepositsPicker.pick. Multi-recipe types use this single value
        // for every ore chunk's randomMul; per-chunk recipe selection happens
        // below at apply time.
        ResourceLocation referenceRecipeId = type.veinRecipes().get(0).recipe();
        VeinRecipe vr = CoedepositsPicker.resolveRecipeValue(lvl, referenceRecipeId);
        if (vr == null) return false;

        double targetUnits = type.perChunkUnits().computeTarget(
                p.tierFraction(), Config.UNBOUNDED_GROWTH.get());
        int finiteBase = com.tom.createores.Config.finiteAmountBase;
        float amountMul = DepositPlacer.amountMulForTarget(
                targetUnits, vr.getMinAmount(), vr.getMaxAmount(), finiteBase);

        Deposit candidate = new Deposit(
                UUID.randomUUID(),
                p.typeId(),
                p.typeId().getPath() + "@" + (p.coreChunk().x * 16) + "," + (p.coreChunk().z * 16),
                p.coreChunk(),
                p.chunks(),
                amountMul,
                p.tierFraction(),
                DepositType.Placement.MANAGED,
                0.0);  // replenishRateOverride: defer to type's default
        // Resolve overlaps with already-placed deposits: same-type blobs merge,
        // different types yield each contested chunk to the rarer (lower weight).
        DepositSavedData.OverlapResult res =
                store.addResolvingOverlap(candidate, CoedepositsPicker::weightOf);
        Deposit dep = res.placed();
        if (dep == null) return false;  // every chunk lost to a rarer type
        CoedepositsPicker.syncOverlap(lvl, store, res, candidate.id());

        // Materialize OreData on any of the blob's chunks that are currently
        // loaded. Per-chunk roll picks each chunk's recipe (or returns empty
        // for filler chunks — those get NO OreData, drill yields nothing).
        // Without this loop the deposit only "appears" at ground level once
        // each chunk independently reloads — disruptive after
        // /coedeposits regenerate where the player's current chunks were
        // just wiped.
        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
        long depositSeed = store.effectiveSeed(lvl);
        for (ChunkPos cc : dep.chunks()) {
            var loadedChunk = lvl.getChunkSource().getChunkNow(cc.x, cc.z);
            if (loadedChunk == null) continue;
            java.util.Optional<ResourceLocation> recipeId =
                    DepositPlacer.rollChunkRecipe(type, depositSeed, cc);
            if (recipeId.isEmpty()) continue;  // filler chunk
            float perChunkMul = dep.amountMulFor(cc, edgeMul);
            CoedepositsPicker.applyToOreData(loadedChunk, recipeId.get(), perChunkMul);
            loadedChunk.setUnsaved(true);
        }
        return true;
    }

    /**
     * Materialize a single-chunk COE-spread deposit (declared or auto-adopted).
     * Persists the {@link Deposit}, writes OreData if the chunk is loaded (else
     * Phase 1 of {@link CoedepositsPicker#pick} applies it on load), and
     * broadcasts discovery. No overlap resolution — a single chunk that's already
     * occupied was filtered by {@code store.isOccupied} in the caller.
     */
    private static boolean materializeCoe(ServerLevel lvl, DepositSavedData store,
                                          PendingPlacement p, DepositType type) {
        ResourceLocation veinId = type.veinRecipes().get(0).recipe();
        // A reload between dry-run and materialize may have dropped the recipe.
        if (CoedepositsPicker.resolveRecipeValue(lvl, veinId) == null) return false;

        ChunkPos cp = p.coreChunk();
        float amountMul = CoedepositsPicker.rollDeterministicMul(store.effectiveSeed(lvl), cp);
        Deposit dep = new Deposit(
                UUID.randomUUID(),
                p.typeId(),
                p.typeId().getPath() + "@" + (cp.x * 16) + "," + (cp.z * 16),
                cp,
                Set.of(cp),
                amountMul,
                0f,                                   // tier irrelevant for COE-placed
                DepositType.Placement.COE,
                0.0);                                 // replenishRateOverride: type default
        store.add(dep);

        var loaded = lvl.getChunkSource().getChunkNow(cp.x, cp.z);
        if (loaded != null) {
            CoedepositsPicker.applyToOreData(loaded, veinId, amountMul);
            loaded.setUnsaved(true);
        }
        CoedepositsPicker.broadcastDiscovery(lvl, dep);
        return true;
    }

    /**
     * Synchronous one-shot scan around the level's shared spawn — convenience
     * wrapper for callers (typically commands) that want a blocking sweep.
     *
     * <p>Prefer {@link ProspectScanQueue#enqueue} for the routine
     * server-start / player-roam paths so the tick isn't blocked.
     *
     * @param lvl          level to scan
     * @param blockRadius  scan radius in blocks. {@code <= 0} disables.
     */
    public static void scan(ServerLevel lvl, int blockRadius) {
        scanAround(lvl, lvl.getSharedSpawnPos(), blockRadius);
    }

    /**
     * Synchronous one-shot scan centred on {@code center}. Same idempotency
     * guarantees as the queued path. Internally uses the same
     * {@link #dryRunChunk} + {@link #materialize} primitives so behaviour is
     * identical between sync and async paths.
     *
     * @param lvl          level to scan
     * @param center       centre of the scan box in world blocks
     * @param blockRadius  scan radius in blocks; {@code <= 0} disables
     */
    public static void scanAround(ServerLevel lvl, BlockPos center, int blockRadius) {
        if (blockRadius <= 0) {
            if (Config.LOG_SCAN_SUMMARY.get()) {
                Coedeposits.LOGGER.info("[coedeposits] prospect scan disabled (prospect_radius=0)");
            }
            return;
        }

        ScanSnapshot snap = ScanSnapshot.capture(lvl);
        DepositSavedData store = DepositSavedData.get(lvl);
        ChunkPos centerCp = new ChunkPos(center);
        int chunkRadius = blockRadius / 16;

        long startMs = System.currentTimeMillis();
        int scanned = 0, skipped = 0, placed = 0;

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                scanned++;
                ChunkPos cp = new ChunkPos(centerCp.x + dx, centerCp.z + dz);
                if (store.isOccupied(cp)) { skipped++; continue; }

                PendingPlacement p = dryRunChunk(snap, cp);
                if (p == null) continue;

                if (materialize(lvl, p)) placed++;
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        if ((placed > 0 || elapsed > 500) && Config.LOG_SCAN_SUMMARY.get()) {
            // Only log when something happened or it was slow — roving scans
            // would otherwise spam the log with zero-placed lines.
            Coedeposits.LOGGER.info(
                    "[coedeposits] prospect scan at ({},{}): radius={} blocks, scanned={} chunks, " +
                            "skipped={} (already placed), placed={} new deposits in {} ms",
                    center.getX(), center.getZ(),
                    blockRadius, scanned, skipped, placed, elapsed);
        }
    }
}
