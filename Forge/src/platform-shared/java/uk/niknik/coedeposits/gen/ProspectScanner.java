package uk.niknik.coedeposits.gen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
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
 * Stateless scan helpers — dry-run (off-thread safe) + materialize (main-thread
 * only) so {@link ProspectScanQueue} can move picker work off the server tick.
 *
 * <p>Loader-agnostic (platform-shared); ports verbatim — the biome-source
 * sampling API ({@code getBiomeSource}, {@code randomState().sampler()},
 * {@code getNoiseBiome(x,y,z,sampler)}) is identical in 1.20.1.
 */
public final class ProspectScanner {
    private ProspectScanner() {}

    /** Immutable snapshot of level/config state the dry-run phase needs (server-thread captured, any-thread read). */
    public record ScanSnapshot(
            BlockPos spawn,
            long worldSeed,
            long coeWorldSeed,
            ResourceLocation dimension,
            float baseR,
            float maxR,
            float prob,
            int coeMinQuartY,
            int coeMaxQuartY,
            BiomeSource biomeSource,
            Climate.Sampler sampler,
            List<CoeVein> coeVeins) {

        /**
         * Build a snapshot from live level state. Server-thread only (DepositSavedData
         * + the recipe manager); the result is safe to hand to any thread.
         *
         * <p>{@code worldSeed} is the effective deposit seed (override or world) for
         * the MANAGED blob roll; {@code coeWorldSeed} is the raw world seed COE's own
         * spread uses (never the override), so adopted COE veins predict where COE
         * actually places them.
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
     *
     * <p><b>1.20.1 delta:</b> {@code getAllRecipesFor} returns bare
     * {@link VeinRecipe}s (no {@code RecipeHolder}); the id comes from
     * {@code VeinRecipe.getId()}.
     */
    private static List<CoeVein> captureCoeVeins(ServerLevel lvl) {
        boolean autoAdopt = Config.AUTO_ADOPT_COE_VEINS.get();
        if (!autoAdopt && !Coedeposits.DEPOSIT_TYPES.hasCoePlacementType()) return List.of();
        Set<ResourceLocation> managed = Coedeposits.DEPOSIT_TYPES.managedVeinRecipes();
        List<VeinRecipe> all = new ArrayList<>(
                lvl.getRecipeManager().getAllRecipesFor(CreateOreExcavation.VEIN_RECIPES.getRecipeType()));
        // COE order: negGenerationPriority asc (= priority desc), then id.
        all.sort(Comparator.<VeinRecipe>comparingInt(VeinRecipe::getNegGenerationPriority)
                .thenComparing(VeinRecipe::getId));
        List<CoeVein> out = new ArrayList<>(all.size());
        for (VeinRecipe r : all) {
            ResourceLocation veinId = r.getId();
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
            // 1.20.1 COE VeinRecipe exposes biome white/blacklist as public
            // (nullable) TagKey fields, not the 1.21.1 Optional accessors.
            out.add(new CoeVein(typeId, r.getPlacement(),
                    r.biomeWhitelist, r.biomeBlacklist,
                    isManaged, adoptable));
        }
        return out;
    }

    /** Pure outcome of a dry run — what materialize needs to persist the deposit. */
    public record PendingPlacement(
            ChunkPos coreChunk,
            ResourceLocation typeId,
            Set<ChunkPos> chunks,
            float tierFraction,
            DepositType.Placement placement) {}

    /** Off-thread-safe per-chunk dry run. Returns null when no core rolled / no type matched. */
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
                snap.baseR(), snap.maxR(), snap.prob(), biome, snap.dimension());
        if (result != null) {
            return new PendingPlacement(cp, result.typeId(), result.chunks(),
                    result.tierFraction(), DepositType.Placement.MANAGED);
        }

        // COE-spread path — replicate COE's own pick() so the prospect map and the
        // regenerate sweep pre-populate declared / auto-adopted COE veins ahead of
        // the player, exactly where COE will place them on chunk-load.
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

    /** Main-thread materialize — persists the placement + writes OreData on loaded blob chunks. */
    public static boolean materialize(ServerLevel lvl, PendingPlacement p) {
        DepositSavedData store = DepositSavedData.get(lvl);
        if (store.isOccupied(p.coreChunk())) return false;

        DepositType type = Coedeposits.DEPOSIT_TYPES.get(p.typeId());
        if (type == null) return false;
        if (type.veinRecipes().isEmpty()) return false;

        // COE-spread placement: single-chunk adopted deposit. Same ownership as a
        // chunk-load adoption (Phase 3c) — we write OreData + persist; on reload
        // Phase 1 re-applies it. amountMul uses the deterministic per-chunk roll
        // so a prospect-materialized COE vein matches its eventual on-load value.
        if (p.placement() == DepositType.Placement.COE) {
            return materializeCoe(lvl, store, p, type);
        }

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
                0.0);
        DepositSavedData.OverlapResult res =
                store.addResolvingOverlap(candidate, CoedepositsPicker::weightOf);
        Deposit dep = res.placed();
        if (dep == null) return false;
        CoedepositsPicker.syncOverlap(lvl, store, res, candidate.id());

        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
        long depositSeed = store.effectiveSeed(lvl);
        for (ChunkPos cc : dep.chunks()) {
            var loadedChunk = lvl.getChunkSource().getChunkNow(cc.x, cc.z);
            if (loadedChunk == null) continue;
            java.util.Optional<ResourceLocation> recipeId =
                    DepositPlacer.rollChunkRecipe(type, depositSeed, cc);
            if (recipeId.isEmpty()) continue;
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

    /** Synchronous one-shot scan around the level's shared spawn (used by commands). */
    public static void scan(ServerLevel lvl, int blockRadius) {
        scanAround(lvl, lvl.getSharedSpawnPos(), blockRadius);
    }

    /** Synchronous one-shot scan centred on {@code center}. */
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
            Coedeposits.LOGGER.info(
                    "[coedeposits] prospect scan at ({},{}): radius={} blocks, scanned={} chunks, " +
                            "skipped={} (already placed), placed={} new deposits in {} ms",
                    center.getX(), center.getZ(),
                    blockRadius, scanned, skipped, placed, elapsed);
        }
    }
}
