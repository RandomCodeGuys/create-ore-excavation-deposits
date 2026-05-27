package uk.niknik.coedeposits.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import com.tom.createores.OreData;
import com.tom.createores.OreDataAttachment;
import com.tom.createores.recipe.DrillingRecipe;
import com.tom.createores.recipe.VeinRecipe;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.gen.DepositPlacer;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Wire-friendly snapshot of a {@link Deposit}. {@code chunkRemaining} is
 * parallel to {@code packedChunks}: same length, same order. Sentinel values:
 *   -3 — chunk is a filler (no OreData by design; renders as tailings)
 *   -2 — chunk unloaded server-side, remaining unknown
 *   -1 — vein depleted in this chunk
 *    0 — vein is infinite (recipe.finite == NEVER or DEFAULT+config)
 *   >0 — units of ore remaining in this chunk
 *
 * <p>{@code dimension} carries the source level's id so the client renderer
 * can skip snapshots that don't belong to the player's current dimension —
 * chunk coords are world-relative but identical between dimensions, so
 * without this filter an overworld deposit at (50, 50) would draw on the
 * nether map at (50, 50) too.
 *
 * <p>{@code revealModeOrdinal} mirrors the server-side effective reveal mode
 * so the client renderer can apply ON_PROXIMITY filtering. The matching
 * {@code proximityBlocks} carries the radius for that mode (0 when N/A).
 *
 * <p>{@code recipeIds} + {@code recipeWeights} are the type's full
 * weighted-recipe pool (deposit-level). Used by the client renderer to show
 * "ores: iron (65%), copper (35%)" in the hover tooltip for multi-recipe
 * deposits. Empty when the type is missing from the live registry.
 *
 * <p>{@code chunkRecipeIndex} is parallel to {@code packedChunks}: each entry
 * is an index into {@code recipeIds} for that specific chunk (so the tooltip
 * can show "this chunk: iron — 179 740 / 180 000"), or {@code -1} when the
 * chunk rolled a filler. Computed via the same
 * {@link DepositPlacer#rollChunkRecipe} primitive used for placement, so the
 * displayed ore matches what the picker actually wrote into OreData.
 */
public record DepositSnapshot(
        UUID id,
        String name,
        ResourceLocation typeId,
        ResourceLocation dimension,
        List<Long> packedChunks,
        List<Long> chunkRemaining,
        List<Long> chunkInitial,
        float amountMul,
        int mapColor,
        int revealModeOrdinal,
        int proximityBlocks,
        double replenishRatePerHour,
        List<ResourceLocation> recipeIds,
        List<Integer> recipeWeights,
        List<Integer> chunkRecipeIndex,
        List<List<DrillOutputEntry>> drillOutputsByRecipe) {

    /**
     * One output entry from a drilling recipe. The drill rolls each entry
     * independently per cycle: {@code chance} is the probability (0..1) that
     * the entry drops, {@code count} is how many drop on success. Mirrors
     * Create's {@code ProcessingOutput} but uses a {@link ResourceLocation}
     * for the item id so the client doesn't need to share an ItemStack codec
     * for the snapshot payload.
     *
     * <p>Used by the world-map tooltip's "drill yields" line so the player
     * sees the actual mix of items they'll get from drilling — including any
     * weighted "slag" outputs like cobblestone or gravel. Per-block weighted
     * mixes (COE-native multi-output drilling recipes) become visible at a
     * glance instead of requiring the player to drill a few cycles to figure
     * out what's in the deposit.
     */
    public record DrillOutputEntry(ResourceLocation itemId, int count, float chance) {}

    /**
     * Build a snapshot from server-side state. Queries each chunk for its
     * current {@code OreData} (skipped if chunk unloaded → sentinel values).
     * The {@code dimension} field comes from {@code lvl.dimension()} — the
     * client renderer uses it to skip deposits that don't belong to the
     * player's current dimension.
     */
    public static DepositSnapshot fromDeposit(ServerLevel lvl, Deposit d) {
        // ── Phase 1: pre-size parallel lists, snapshot per-call constants ───
        // recipeManager + edgeMul + finiteAmountBase don't change between
        // chunks, so hoist them outside the loop. The four chunk-parallel
        // lists are kept index-aligned: packedChunks[i], chunkRemaining[i],
        // chunkInitial[i], chunkRecipeIndex[i] all refer to the same chunk.
        List<Long> packed = new ArrayList<>(d.chunks().size());
        List<Long> remaining = new ArrayList<>(d.chunks().size());
        List<Long> initial = new ArrayList<>(d.chunks().size());
        List<Integer> chunkRecipeIdx = new ArrayList<>(d.chunks().size());
        var rm = lvl.getRecipeManager();
        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
        int base = com.tom.createores.Config.finiteAmountBase;
        // Multi-recipe lookups: type for the per-chunk filler check, plus the
        // deposit seed driving rollChunkRecipe. Both stay constant for the
        // whole deposit, so hoist out of the loop.
        DepositType liveType = Coedeposits.DEPOSIT_TYPES.get(d.typeId());
        long depositSeed = DepositSavedData.get(lvl).effectiveSeed(lvl);
        boolean isManaged = d.placement() == DepositType.Placement.MANAGED;

        // ── Phase 2: build deposit-level recipe pool (id + weight lists) ────
        // Empty when type missing — client falls back to showing only the
        // type id as the composition (no per-recipe display).
        List<ResourceLocation> recipeIds;
        List<Integer> recipeWeights;
        if (liveType != null && !liveType.veinRecipes().isEmpty()) {
            recipeIds = new ArrayList<>(liveType.veinRecipes().size());
            recipeWeights = new ArrayList<>(liveType.veinRecipes().size());
            for (DepositType.WeightedRecipe wr : liveType.veinRecipes()) {
                recipeIds.add(wr.recipe());
                recipeWeights.add(wr.weight());
            }
        } else {
            recipeIds = List.of();
            recipeWeights = List.of();
        }

        // ── Phase 2b: resolve drilling outputs for every vein recipe ────────
        // Walk the recipe manager once to find each DrillingRecipe by veinId,
        // capturing its output list as DrillOutputEntry records the client can
        // render in the tooltip without needing access to COE/Create classes.
        // Used by the "drill yields: iron 65%, copper 35%, +cobblestone 20%"
        // line so per-block weighted mixes (single vein recipe, multi-output
        // drilling recipe) become visible at a glance — without it, the
        // tooltip would look identical to a vanilla single-ore deposit.
        java.util.Map<ResourceLocation, List<DrillOutputEntry>> drillByVein = resolveDrillOutputsMap(rm);
        List<List<DrillOutputEntry>> drillOutputsByRecipe = new ArrayList<>(recipeIds.size());
        for (ResourceLocation rid : recipeIds) {
            drillOutputsByRecipe.add(drillByVein.getOrDefault(rid, List.of()));
        }

        // ── Phase 3: query each chunk — filler check, remaining, initial,
        //              and per-chunk recipe index resolution ─────────────────
        // For MANAGED deposits we re-roll the per-chunk recipe via the same
        // deterministic primitive the picker uses. The roll's result is
        // mapped back to its position in recipeIds for display ("which ore
        // does THIS chunk yield"). COE-mode and missing-type chunks always
        // map to index 0 if any recipe exists (single-recipe semantic).
        for (ChunkPos cp : d.chunks()) {
            packed.add(cp.toLong());
            int idx;
            boolean isFiller = false;
            if (isManaged && liveType != null) {
                java.util.Optional<ResourceLocation> rolled =
                        DepositPlacer.rollChunkRecipe(liveType, depositSeed, cp);
                if (rolled.isEmpty()) {
                    isFiller = true;
                    idx = -1;
                } else {
                    // Look up the rolled recipe's position in recipeIds.
                    // indexOf is O(n) but n is tiny (1-5 typically); fine.
                    idx = recipeIds.indexOf(rolled.get());
                    if (idx < 0) idx = 0;  // shouldn't happen — defensive
                }
            } else {
                // COE-mode (or missing type) — chunk has a single recipe
                // chosen by COE at placement. Display as the first entry.
                idx = recipeIds.isEmpty() ? -1 : 0;
            }
            chunkRecipeIdx.add(idx);
            if (isFiller) {
                remaining.add(-3L);
                initial.add(0L);
            } else {
                remaining.add(computeRemaining(lvl, rm, cp));
                initial.add(computeInitial(lvl, rm, d, cp, edgeMul, base));
            }
        }

        // ── Phase 4: derive type-level metadata for the client renderer ─────
        // color of -1 tells the client to fall back to typeId-hash colour.
        // revealModeOrdinal is encoded as int because the enum can grow
        // without breaking older clients — they'd just see ALWAYS fallback.
        DepositType type = liveType;
        int color = (type != null && type.mapColor().isPresent()) ? type.mapColor().get() : -1;
        Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
        int proximity = Config.PROXIMITY_REVEAL_BLOCKS.get();
        // Effective replenish rate combines deposit's per-instance override
        // (settable via /coedeposits replenish) with the type's default. 0
        // means replenishment is disabled — tooltip skips the line in that case.
        double replenishRate = d.effectiveReplenishRate(type);
        return new DepositSnapshot(d.id(), d.name(), d.typeId(),
                lvl.dimension().location(),
                packed, remaining, initial,
                d.amountMul(), color, mode.ordinal(), proximity,
                replenishRate,
                recipeIds, recipeWeights, chunkRecipeIdx, drillOutputsByRecipe);
    }

    /**
     * Build a {@code veinId → drill outputs} map by walking every recipe in
     * the manager and picking out {@link DrillingRecipe} instances. Done
     * once per snapshot call (cheap — a few hundred recipes typically, and
     * the snapshot itself is built per-deposit-per-broadcast, ~25 times per
     * sync sweep). Returns empty list for any vein without a matching
     * drilling recipe (rare; usually a misconfigured datapack).
     */
    private static java.util.Map<ResourceLocation, List<DrillOutputEntry>>
            resolveDrillOutputsMap(net.minecraft.world.item.crafting.RecipeManager rm) {
        java.util.Map<ResourceLocation, List<DrillOutputEntry>> out = new java.util.HashMap<>();
        // ProcessingOutput is Create's class — we don't have Create on our
        // compile classpath (only COE), so we reach into each output entry
        // reflectively. Cache the two Method handles per call to avoid the
        // lookup cost in the inner loop.
        java.lang.reflect.Method getStackMethod = null;
        java.lang.reflect.Method getChanceMethod = null;
        for (RecipeHolder<?> holder : rm.getRecipes()) {
            if (!(holder.value() instanceof DrillingRecipe dr)) continue;
            ResourceLocation veinId = dr.veinId;
            if (veinId == null) continue;
            // Already mapped this vein (multiple drilling recipes can target
            // the same vein with different drill tiers) — keep the first.
            // For tooltip purposes, the first one suffices since output
            // composition is usually identical across tiers.
            if (out.containsKey(veinId)) continue;
            // Cast dr.getOutput() to raw List so the iterator's element type
            // is Object, sidestepping the absent-at-compile-time
            // ProcessingOutput type. Reflection then pulls .getStack() and
            // .getChance() off each entry.
            @SuppressWarnings({"rawtypes","unchecked"})
            java.util.List<Object> rawOutputs = (java.util.List) dr.getOutput();
            List<DrillOutputEntry> outputs = new ArrayList<>(rawOutputs.size());
            for (Object po : rawOutputs) {
                try {
                    if (getStackMethod == null) {
                        getStackMethod = po.getClass().getMethod("getStack");
                        getChanceMethod = po.getClass().getMethod("getChance");
                    }
                    ItemStack stack = (ItemStack) getStackMethod.invoke(po);
                    float chance = (Float) getChanceMethod.invoke(po);
                    if (stack.isEmpty()) continue;
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    outputs.add(new DrillOutputEntry(itemId, stack.getCount(), chance));
                } catch (ReflectiveOperationException e) {
                    // ProcessingOutput API drift in a future Create version —
                    // log once and skip this entry. Tooltip degrades to no
                    // drill-yields line for this recipe but the mod stays up.
                    Coedeposits.LOGGER.error(
                            "[coedeposits] couldn't read drilling output for {} — Create API drift?",
                            veinId, e);
                    break;
                }
            }
            out.put(veinId, outputs);
        }
        return out;
    }

    /**
     * Convenience for the client renderer — decodes the wire ordinal back into
     * the enum. Falls back to {@link Config.RevealMode#ALWAYS} if the ordinal
     * is out of range (forward-compat with future modes).
     */
    public Config.RevealMode revealMode() {
        Config.RevealMode[] values = Config.RevealMode.values();
        return revealModeOrdinal >= 0 && revealModeOrdinal < values.length
                ? values[revealModeOrdinal]
                : Config.RevealMode.ALWAYS;
    }

    /** Reads {@link OreData#getResourcesRemaining} for a chunk; returns sentinel if unloaded. */
    private static long computeRemaining(ServerLevel lvl,
                                          net.minecraft.world.item.crafting.RecipeManager rm,
                                          ChunkPos cp) {
        LevelChunk chunk = lvl.getChunkSource().getChunkNow(cp.x, cp.z);
        if (chunk == null) return -2L;
        OreData od = OreDataAttachment.getData(chunk);
        RecipeHolder<VeinRecipe> rh = od.getRecipe(rm);
        if (rh == null) return -1L;
        return od.getResourcesRemaining(rh.value());
    }

    /**
     * Theoretical initial units for chunk {@code cp} of deposit {@code d}, based
     * on its per-chunk amountMul and the resolved vein recipe range. Returned
     * value matches the formula used by COE to compute "depleted at".
     * Sentinel: 0 if recipe missing or infinite-mode, -2 if chunk unloaded.
     */
    private static long computeInitial(ServerLevel lvl,
                                        net.minecraft.world.item.crafting.RecipeManager rm,
                                        Deposit d, ChunkPos cp, float edgeMul, int base) {
        LevelChunk chunk = lvl.getChunkSource().getChunkNow(cp.x, cp.z);
        if (chunk == null) return -2L;
        OreData od = OreDataAttachment.getData(chunk);
        RecipeHolder<VeinRecipe> rh = od.getRecipe(rm);
        if (rh == null) return 0L;
        VeinRecipe vr = rh.value();
        float mul = d.amountMulFor(cp, edgeMul);
        double perChunkMul = (vr.getMaxAmount() - vr.getMinAmount()) * mul + vr.getMinAmount();
        return Math.round(perChunkMul * base);
    }

    /**
     * Hand-rolled stream codec — the multiple parallel-list structures
     * (chunks/remaining/initial/recipeIndex on one axis; recipeIds/recipeWeights
     * on another) don't compose cleanly with {@link StreamCodec#composite}, so
     * we encode the counts once each and write the parallel arrays as runs.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, DepositSnapshot> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public DepositSnapshot decode(RegistryFriendlyByteBuf buf) {
                    // ── Phase 1: scalar header fields ───────────────────────
                    // Read in the exact order written below. Mismatched order
                    // would silently corrupt the deposit.
                    UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
                    String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                    ResourceLocation typeId = ResourceLocation.STREAM_CODEC.decode(buf);
                    ResourceLocation dimension = ResourceLocation.STREAM_CODEC.decode(buf);

                    // ── Phase 2: four parallel chunk-lists (count-prefixed) ─
                    // VarLong for chunk pos / remaining / initial — packed
                    // ChunkPos longs compress well, sentinel values for
                    // remaining (-3, -2, -1, 0) take 1-2 bytes each. VarInt
                    // for chunkRecipeIndex which is small (-1 for filler,
                    // 0..N for ore where N is the recipe count).
                    int n = buf.readVarInt();
                    List<Long> packed = new ArrayList<>(n);
                    List<Long> remaining = new ArrayList<>(n);
                    List<Long> initial = new ArrayList<>(n);
                    List<Integer> chunkRecipeIdx = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) packed.add(buf.readVarLong());
                    for (int i = 0; i < n; i++) remaining.add(buf.readVarLong());
                    for (int i = 0; i < n; i++) initial.add(buf.readVarLong());
                    for (int i = 0; i < n; i++) chunkRecipeIdx.add(buf.readVarInt());

                    // ── Phase 3: trailing scalar metadata ───────────────────
                    // color is a packed RGB int (-1 = use hash fallback);
                    // revealOrdinal lets the client decode the enum forward-
                    // compatibly (new modes fall back to ALWAYS).
                    float amountMul = buf.readFloat();
                    int color = buf.readInt();
                    int revealOrdinal = buf.readVarInt();
                    int proximity = buf.readVarInt();
                    // Replenish rate (units/hour). Server-resolved effective
                    // value combining per-deposit override with type default.
                    double replenishRate = buf.readDouble();

                    // ── Phase 4: deposit-level recipe pool (id + weights) ──
                    // Parallel lists of recipe ResourceLocations and their
                    // weights. Both have the same length (= recipe count).
                    // Empty when the server-side type registry has no entry
                    // for this deposit's typeId (rare; type was removed
                    // post-placement).
                    int rn = buf.readVarInt();
                    List<ResourceLocation> recipeIds = new ArrayList<>(rn);
                    List<Integer> recipeWeights = new ArrayList<>(rn);
                    for (int i = 0; i < rn; i++) recipeIds.add(ResourceLocation.STREAM_CODEC.decode(buf));
                    for (int i = 0; i < rn; i++) recipeWeights.add(buf.readVarInt());

                    // ── Phase 5: per-vein drilling outputs ─────────────────
                    // Outer list length == recipeIds.size() (parallel). Each
                    // inner list holds the drilling recipe's output entries
                    // for that vein. Empty inner list = no drilling recipe
                    // bound to this vein id (misconfigured datapack).
                    List<List<DrillOutputEntry>> drillOutputs = new ArrayList<>(rn);
                    for (int i = 0; i < rn; i++) {
                        int outCount = buf.readVarInt();
                        List<DrillOutputEntry> outs = new ArrayList<>(outCount);
                        for (int j = 0; j < outCount; j++) {
                            ResourceLocation itemId = ResourceLocation.STREAM_CODEC.decode(buf);
                            int count = buf.readVarInt();
                            float chance = buf.readFloat();
                            outs.add(new DrillOutputEntry(itemId, count, chance));
                        }
                        drillOutputs.add(outs);
                    }

                    return new DepositSnapshot(id, name, typeId, dimension, packed, remaining, initial,
                            amountMul, color, revealOrdinal, proximity, replenishRate,
                            recipeIds, recipeWeights, chunkRecipeIdx, drillOutputs);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, DepositSnapshot v) {
                    // Symmetric to decode — same four phases in the same order.
                    // Any reorder/skip here MUST be mirrored in decode above.
                    UUIDUtil.STREAM_CODEC.encode(buf, v.id);
                    ByteBufCodecs.STRING_UTF8.encode(buf, v.name);
                    ResourceLocation.STREAM_CODEC.encode(buf, v.typeId);
                    ResourceLocation.STREAM_CODEC.encode(buf, v.dimension);
                    buf.writeVarInt(v.packedChunks.size());
                    for (long p : v.packedChunks) buf.writeVarLong(p);
                    for (long r : v.chunkRemaining) buf.writeVarLong(r);
                    for (long i : v.chunkInitial) buf.writeVarLong(i);
                    for (int idx : v.chunkRecipeIndex) buf.writeVarInt(idx);
                    buf.writeFloat(v.amountMul);
                    buf.writeInt(v.mapColor);
                    buf.writeVarInt(v.revealModeOrdinal);
                    buf.writeVarInt(v.proximityBlocks);
                    buf.writeDouble(v.replenishRatePerHour);
                    buf.writeVarInt(v.recipeIds.size());
                    for (ResourceLocation r : v.recipeIds) ResourceLocation.STREAM_CODEC.encode(buf, r);
                    for (int w : v.recipeWeights) buf.writeVarInt(w);
                    // Per-vein drilling outputs — parallel to recipeIds; each
                    // inner list is the drilling recipe's output entries.
                    for (List<DrillOutputEntry> outs : v.drillOutputsByRecipe) {
                        buf.writeVarInt(outs.size());
                        for (DrillOutputEntry e : outs) {
                            ResourceLocation.STREAM_CODEC.encode(buf, e.itemId());
                            buf.writeVarInt(e.count());
                            buf.writeFloat(e.chance());
                        }
                    }
                }
            };
}
