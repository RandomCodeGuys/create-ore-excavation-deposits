package uk.niknik.coedeposits.gen;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import com.mojang.datafixers.util.Pair;

import com.tom.createores.OreDataCapability;
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
 * vein placement with deposit-aware logic. Installed via {@link #install} which
 * reflects into the private {@code OreVeinGenerator.picker} AtomicReference.
 *
 * <p><b>1.20.1 line:</b> {@code pick(LevelChunk)} returns a bare {@link VeinRecipe}
 * (not a {@code RecipeHolder}); OreData is written through
 * {@link OreDataCapability#getData} (Forge Capabilities — no NeoForge attachment,
 * no reflection) guarded by a thread-local re-entry flag so the lazy
 * {@code getData → populate → pick} call doesn't recurse.
 */
public class CoedepositsPicker extends RandomSpreadGenerator {

    /**
     * Re-entry guard for {@link #applyToOreData}. On 1.20.1
     * {@link OreDataCapability#getData} lazily populates by calling
     * {@code OreVeinGenerator.pick} (= this picker). When we call getData from
     * inside our own pick (to write OreData), that re-entrant pick must short-
     * circuit or it recurses forever. The flag is set across the getData call.
     */
    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Override
    public VeinRecipe pick(LevelChunk chunk) {
        // Re-entry from applyToOreData's getData→populate — don't recurse.
        if (APPLYING.get()) return null;

        ServerLevel lvl = (ServerLevel) chunk.getLevel();
        ChunkPos cp = chunk.getPos();
        ResourceLocation dim = lvl.dimension().location();

        if (!Config.isDimensionEnabled(dim)) {
            return null;
        }

        DepositSavedData store = DepositSavedData.get(lvl);

        // ── Phase 1: re-apply OreData for chunks of known deposits ──────────
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
                    recipeId = type.veinRecipes().get(0).recipe();
                } else {
                    recipeId = DepositPlacer.rollChunkRecipe(
                            type, store.effectiveSeed(lvl), cp).orElse(null);
                }
                if (recipeId != null) {
                    float perChunk = existing.amountMulFor(
                            cp, Config.EDGE_AMOUNT_MUL.get().floatValue());
                    applyToOreData(chunk, recipeId, perChunk);
                }
            }
            return null;
        }

        // ── Phase 2: managed blob placer ────────────────────────────────────
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

            double targetUnits = type.perChunkUnits().computeTarget(
                    placed.tierFraction(), Config.UNBOUNDED_GROWTH.get());
            int base = com.tom.createores.Config.finiteAmountBase;
            float amountMul = DepositPlacer.amountMulForTarget(
                    targetUnits, vr.getMinAmount(), vr.getMaxAmount(), base);

            Deposit candidate = new Deposit(
                    UUID.randomUUID(), placed.typeId(), nameFor(placed.typeId(), cp),
                    cp, placed.chunks(), amountMul, placed.tierFraction(),
                    DepositType.Placement.MANAGED, 0.0);
            DepositSavedData.OverlapResult res =
                    store.addResolvingOverlap(candidate, CoedepositsPicker::weightOf);
            Deposit dep = res.placed();
            if (dep == null) return null;

            java.util.Optional<ResourceLocation> coreRecipe =
                    DepositPlacer.rollChunkRecipe(type, store.effectiveSeed(lvl), cp);
            if (coreRecipe.isPresent()) {
                float perChunkCore = dep.amountMulFor(cp, Config.EDGE_AMOUNT_MUL.get().floatValue());
                applyToOreData(chunk, coreRecipe.get(), perChunkCore);
            }

            if (Config.LOG_PLACEMENT.get()) {
                Coedeposits.LOGGER.info(
                        "[coedeposits] placed {} at chunk {},{} | {} chunks | tier {} | target {} units/chunk peak (ref recipe {})",
                        placed.typeId(), cp.x, cp.z, dep.chunks().size(),
                        String.format("%.2f", placed.tierFraction()),
                        String.format("%,.0f", targetUnits), referenceRecipeId);
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

        VeinRecipe superResult = super.pick(chunk);
        if (superResult == null) return null;

        ResourceLocation chosenVein = superResult.getId();

        if (Coedeposits.DEPOSIT_TYPES.managedVeinRecipes().contains(chosenVein)) {
            return null;
        }

        // Step 3b.5: disabled vein — explicitly via editor/disabled_veins, or a
        // base-COE vein under coe_veins_disabled_by_default. A DECLARED type
        // referencing the vein overrides this (promoting a vein in the editor is
        // explicit intent to have it generate). Return null so COE doesn't place
        // it at all.
        ResourceLocation declaredCoeTypeId = Coedeposits.DEPOSIT_TYPES.coeTypeIdForVeinRecipe(chosenVein);
        if (declaredCoeTypeId == null && Config.isVeinDisabled(chosenVein)) {
            return null;
        }

        // Step 3c: adoption path. Resolve the type that owns COE's chosen vein —
        // a declared placement=COE type, or (when auto-adopt is on) an implicit
        // type keyed by the vein id. Either way we take ownership of the OreData
        // and persist a single-chunk deposit so it shows on the map. Honour the
        // per-type dimensions allow-list — a COE entry restricted to specific
        // dims won't be tracked outside them (implicit types are dim-agnostic).
        ResourceLocation coeTypeId = declaredCoeTypeId;
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
            float amountMul = rollDeterministicMul(store.effectiveSeed(lvl), cp);
            applyToOreData(chunk, chosenVein, amountMul);

            Deposit dep = new Deposit(
                    UUID.randomUUID(), coeTypeId, nameFor(coeTypeId, cp),
                    cp, Set.of(cp), amountMul, 0f, DepositType.Placement.COE, 0.0);
            store.add(dep);
            if (Config.LOG_PLACEMENT.get()) {
                Coedeposits.LOGGER.info(
                        "[coedeposits] tracked COE vein {} at chunk {},{} | dim {} | amountMul {}",
                        chosenVein, cp.x, cp.z, dim, String.format("%.2f", amountMul));
            }
            broadcastDiscovery(lvl, dep);
            return null;
        }

        return superResult;  // third-party vein — pass through to COE
    }

    /** True when at least one {@code placement=coe} type exists — gates the super.pick() path. */
    private static boolean hasAnyCoePlacementType() {
        for (DepositType t : Coedeposits.DEPOSIT_TYPES.all().values()) {
            if (t.placement() == DepositType.Placement.COE) return true;
        }
        return false;
    }

    /**
     * Write the COE OreData for a chunk via {@link OreDataCapability#getData} —
     * guarded by {@link #APPLYING} so the lazy populate-recursion is broken.
     * Sets recipe + randomMul and resets extractedAmount to 0 (re-apply refills).
     */
    public static void applyToOreData(LevelChunk chunk, ResourceLocation recipe, float amountMul) {
        APPLYING.set(Boolean.TRUE);
        try {
            OreDataCapability.OreData od = OreDataCapability.getData(chunk);
            od.setRecipe(recipe);
            od.setRandomMul(amountMul);
            od.setExtractedAmount(0);
            od.setLoaded(true);
        } catch (Exception e) {
            Coedeposits.LOGGER.error("[coedeposits] failed to apply OreData for chunk {}", chunk.getPos(), e);
        } finally {
            APPLYING.set(Boolean.FALSE);
        }
    }

    /**
     * Ensure a known deposit chunk's OreData carries the right vein recipe —
     * WITHOUT going through COE's lazy {@code getData → pick} populate (which
     * depends on our picker being the installed one; COE nulls it on every
     * {@link net.minecraftforge.event.TagsUpdatedEvent} and then lazily recreates
     * a DEFAULT picker that ignores our deposits, leaving chunks with a null recipe
     * → read back as "depleted"). We write the capability directly instead, so the
     * result is deterministic regardless of picker state, and mark {@code loaded}
     * so COE's getData won't later overwrite it.
     *
     * <p>Only touches OreData when the stored recipe differs from {@code recipe}
     * (null/stale/wrong): a matching recipe is left untouched so per-chunk
     * depletion ({@code extractedAmount}) survives chunk unload/reload. When the
     * recipe DOES change we reset {@code extractedAmount} — any prior value was
     * tracked against a different (or absent) vein and is meaningless.
     */
    public static void ensureOreData(LevelChunk chunk, ResourceLocation recipe, float amountMul) {
        OreDataCapability.OreData od =
                uk.niknik.coedeposits.platform.CoedepositsPlatform.get().oreDataNoPopulate(chunk);
        if (od == null) return;
        if (!recipe.equals(od.getRecipeId())) {
            od.setRecipe(recipe);
            od.setRandomMul(amountMul);
            od.setExtractedAmount(0);
            od.setLoaded(true);
            chunk.setUnsaved(true);
        }
    }

    /** Cached handle to {@code OreDataCapability.OreData.extractedAmount} (no public getter). */
    private static java.lang.reflect.Field extractedAmountField;

    /**
     * Reflectively read {@code OreData.extractedAmount} (COE exposes no getter on
     * the 1.20.1 nested {@link OreDataCapability.OreData}). Used by the depletion
     * sweep's self-heal to tell a never-applied deposit chunk (extracted == 0)
     * from a genuinely mined-out one (extracted &gt; 0). On reflection failure
     * returns {@link Long#MAX_VALUE} so the caller treats the chunk as
     * "extracted" and does NOT refill it (fail safe, never re-fills).
     */
    public static long getExtractedAmount(OreDataCapability.OreData od) {
        try {
            java.lang.reflect.Field f = extractedAmountField;
            if (f == null) {
                f = OreDataCapability.OreData.class.getDeclaredField("extractedAmount");
                f.setAccessible(true);
                extractedAmountField = f;
            }
            return f.getLong(od);
        } catch (ReflectiveOperationException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Recipe lookup — 1.20.1 RecipeManager.byKey returns the Recipe directly (no holder). */
    public static VeinRecipe resolveRecipeValue(ServerLevel lvl, ResourceLocation id) {
        return lvl.getRecipeManager().byKey(id)
                .filter(r -> r instanceof VeinRecipe)
                .map(r -> (VeinRecipe) r)
                .orElse(null);
    }

    /** Build a human-readable deposit label like {@code "iron@128,256"}. */
    private static String nameFor(ResourceLocation typeId, ChunkPos cp) {
        return typeId.getPath() + "@" + (cp.x * 16) + "," + (cp.z * 16);
    }

    /** Weight of a type — MAX_VALUE for an unknown type (so it always yields). */
    static int weightOf(ResourceLocation typeId) {
        DepositType t = Coedeposits.DEPOSIT_TYPES.get(typeId);
        return t != null ? t.weight() : Integer.MAX_VALUE;
    }

    /** Push an overlap result to clients (removals / re-sync / discovery). */
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
            broadcastDiscovery(lvl, placed);
        }
    }

    /** Deterministic {@code [0,1)} float for (seed, chunk) — reproducible randomMul for COE veins. */
    static float rollDeterministicMul(long worldSeed, ChunkPos cp) {
        WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
        rng.setLargeFeatureSeed(worldSeed, cp.x, cp.z);
        return rng.nextFloat();
    }

    /**
     * Override COE's vein-finder locate(): scans saved deposits then dry-runs the
     * placer on rings outward. 1.20.1 signature uses bare {@link VeinRecipe}.
     */
    @Override
    public Pair<BlockPos, VeinRecipe> locate(
            BlockPos pPos, ServerLevel level, int radius, Predicate<VeinRecipe> filter) {

        ResourceLocation dim = level.dimension().location();
        if (!Config.isDimensionEnabled(dim)) {
            Pair<BlockPos, VeinRecipe> fromCoe = super.locate(pPos, level, radius, filter);
            if (fromCoe == null) return null;
            if (Coedeposits.DEPOSIT_TYPES.managedVeinRecipes().contains(fromCoe.getSecond().getId())) {
                return null;  // phantom from one of our managed recipes — discard
            }
            return fromCoe;
        }

        DepositSavedData store = DepositSavedData.get(level);

        int searchRadius = Math.max(radius, 64);
        int searchBlockRadius = searchRadius * 16;

        BlockPos best = null;
        VeinRecipe bestRecipe = null;
        float bestDist = Float.MAX_VALUE;

        // ── Phase 1: scan saved deposits ────────────────────────────────────
        for (Deposit dep : store.all().values()) {
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
            if (type == null) continue;
            if (!type.matchesDimension(dim)) continue;
            VeinRecipe rh = firstMatchingRecipe(level, type, filter);
            if (rh == null) continue;
            BlockPos core = new BlockPos(
                    dep.core().getMiddleBlockX(), pPos.getY(), dep.core().getMiddleBlockZ());
            float d = RandomSpreadGenerator.distance2d(core, pPos);
            if (d > searchBlockRadius) continue;
            if (d < bestDist) { bestDist = d; best = core; bestRecipe = rh; }
        }

        // ── Phase 2: dry-run picker on rings outward ────────────────────────
        long worldSeed = DepositSavedData.get(level).effectiveSeed(level);
        BlockPos spawn = level.getSharedSpawnPos();
        float baseR = Config.BASE_RADIUS.get().floatValue();
        float maxR = Config.MAX_RADIUS.get().floatValue();
        float prob = Config.CORE_SPAWN_PROBABILITY.get().floatValue();
        ChunkPos centerCp = new ChunkPos(pPos);

        for (int r = 0; r <= searchRadius; r++) {
            if (r * 16 >= bestDist) break;
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
                            baseR, maxR, prob, biome, level.dimension().location());
                    if (result == null) continue;
                    VeinRecipe rh = firstMatchingRecipe(level, result.type(), filter);
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
            Pair<BlockPos, VeinRecipe> coe = super.locate(pPos, level, searchRadius, rh -> {
                if (!filter.test(rh)) return false;
                ResourceLocation rid = rh.getId();
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

    /** First recipe of a type that resolves AND passes the finder filter (multi-recipe support). */
    private static VeinRecipe firstMatchingRecipe(
            ServerLevel lvl, DepositType type, Predicate<VeinRecipe> filter) {
        for (DepositType.WeightedRecipe wr : type.veinRecipes()) {
            VeinRecipe rh = resolveRecipeValue(lvl, wr.recipe());
            if (rh != null && filter.test(rh)) return rh;
        }
        return null;
    }

    /** Notify players of a new deposit (reveal-mode aware). */
    public static void broadcastDiscovery(ServerLevel lvl, Deposit dep) {
        DepositType type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
        Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();

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
                new DepositDiscoveryPayload(
                        DepositType.displayNameOf(Coedeposits.DEPOSIT_TYPES.get(dep.typeId()), dep.typeId()),
                        pos, dep.typeId(), ""));
    }

    /** Replace COE's static {@code OreVeinGenerator.picker} with a fresh instance via reflection. */
    @SuppressWarnings("unchecked")
    public static void install(ServerLevel level) {
        try {
            CoedepositsPicker p = new CoedepositsPicker();
            p.loadAll(level);

            java.lang.reflect.Field f = OreVeinGenerator.class.getDeclaredField("picker");
            f.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicReference<RandomSpreadGenerator>) f.get(null)).set(p);
            if (Config.LOG_LIFECYCLE.get()) {
                Coedeposits.LOGGER.info("[coedeposits] installed CoedepositsPicker on OreVeinGenerator");
            }
        } catch (ReflectiveOperationException e) {
            Coedeposits.LOGGER.error("[coedeposits] failed to install picker", e);
        }
    }
}
