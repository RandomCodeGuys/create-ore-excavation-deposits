package uk.niknik.coedeposits.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import com.tom.createores.OreDataCapability;
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
 *   -4 — vein recipe not loaded (recipe id set but unresolvable — misconfig)
 *   -3 — chunk is a filler (no OreData by design; renders as tailings)
 *   -2 — chunk unloaded server-side, remaining unknown
 *   -1 — vein depleted in this chunk
 *    0 — vein is infinite (recipe.finite == NEVER or DEFAULT+config)
 *   >0 — units of ore remaining in this chunk
 *
 * <p><b>1.20.1 line:</b> no {@code StreamCodec}/{@code ByteBufCodecs}/
 * {@code RegistryFriendlyByteBuf} (those are 1.20.5+) — the codec is a
 * hand-rolled instance {@link #encode}/static {@link #decode} pair over plain
 * {@link FriendlyByteBuf}. OreData read through {@link OreDataCapability};
 * {@code OreData.getRecipe} returns a bare {@link VeinRecipe} (no
 * {@code RecipeHolder}); {@code RecipeManager.getRecipes()} yields bare
 * {@link Recipe} (no {@code RecipeHolder} wrapper).
 *
 * <p>{@code dimension} carries the source level's id so the client renderer
 * can skip snapshots that don't belong to the player's current dimension.
 *
 * <p>{@code revealModeOrdinal} mirrors the server-side effective reveal mode
 * so the client renderer can apply ON_PROXIMITY filtering. The matching
 * {@code proximityBlocks} carries the radius for that mode (0 when N/A).
 *
 * <p>{@code recipeIds} + {@code recipeWeights} are the type's full
 * weighted-recipe pool (deposit-level). {@code chunkRecipeIndex} is parallel
 * to {@code packedChunks}: each entry indexes into {@code recipeIds} for that
 * chunk (or {@code -1} for a filler).
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
     * One output entry from a drilling recipe. {@code chance} is the
     * probability (0..1) that the entry drops, {@code count} is how many drop
     * on success. Uses a {@link ResourceLocation} for the item id so the
     * client doesn't need a shared ItemStack codec for the snapshot payload.
     */
    public record DrillOutputEntry(ResourceLocation itemId, int count, float chance) {}

    /**
     * Build a snapshot from server-side state. Queries each chunk for its
     * current {@code OreData} (skipped if chunk unloaded → sentinel values).
     */
    public static DepositSnapshot fromDeposit(ServerLevel lvl, Deposit d) {
        // ── Phase 1: pre-size parallel lists, snapshot per-call constants ───
        List<Long> packed = new ArrayList<>(d.chunks().size());
        List<Long> remaining = new ArrayList<>(d.chunks().size());
        List<Long> initial = new ArrayList<>(d.chunks().size());
        List<Integer> chunkRecipeIdx = new ArrayList<>(d.chunks().size());
        var rm = lvl.getRecipeManager();
        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
        int base = com.tom.createores.Config.finiteAmountBase;
        DepositType liveType = Coedeposits.DEPOSIT_TYPES.get(d.typeId());
        long depositSeed = DepositSavedData.get(lvl).effectiveSeed(lvl);
        boolean isManaged = d.placement() == DepositType.Placement.MANAGED;

        // ── Phase 2: build deposit-level recipe pool (id + weight lists) ────
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
        java.util.Map<ResourceLocation, List<DrillOutputEntry>> drillByVein = resolveDrillOutputsMap(rm);
        List<List<DrillOutputEntry>> drillOutputsByRecipe = new ArrayList<>(recipeIds.size());
        for (ResourceLocation rid : recipeIds) {
            drillOutputsByRecipe.add(drillByVein.getOrDefault(rid, List.of()));
        }

        // ── Phase 3: query each chunk — filler check, remaining, initial,
        //              and per-chunk recipe index resolution ─────────────────
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
                    idx = recipeIds.indexOf(rolled.get());
                    if (idx < 0) idx = 0;  // shouldn't happen — defensive
                }
            } else {
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
        DepositType type = liveType;
        int color = (type != null && type.mapColor().isPresent()) ? type.mapColor().get() : -1;
        Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
        int proximity = Config.PROXIMITY_REVEAL_BLOCKS.get();
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
     * the manager and picking out {@link DrillingRecipe} instances.
     *
     * <p><b>1.20.1:</b> {@code RecipeManager.getRecipes()} returns bare
     * {@link Recipe} (not {@code RecipeHolder}); iterate + instanceof directly.
     */
    private static java.util.Map<ResourceLocation, List<DrillOutputEntry>>
            resolveDrillOutputsMap(net.minecraft.world.item.crafting.RecipeManager rm) {
        java.util.Map<ResourceLocation, List<DrillOutputEntry>> out = new java.util.HashMap<>();
        // ProcessingOutput is Create's class — reach into each output entry
        // reflectively. Cache the two Method handles per call.
        java.lang.reflect.Method getStackMethod = null;
        java.lang.reflect.Method getChanceMethod = null;
        for (Recipe<?> r : rm.getRecipes()) {
            if (!(r instanceof DrillingRecipe dr)) continue;
            ResourceLocation veinId = dr.veinId;
            if (veinId == null) continue;
            // Multiple drilling recipes can target the same vein (different
            // drill tiers) — keep the first; outputs are usually identical.
            if (out.containsKey(veinId)) continue;
            @SuppressWarnings({"rawtypes", "unchecked"})
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
     * the enum. Falls back to {@link Config.RevealMode#ALWAYS} if out of range.
     */
    public Config.RevealMode revealMode() {
        Config.RevealMode[] values = Config.RevealMode.values();
        return revealModeOrdinal >= 0 && revealModeOrdinal < values.length
                ? values[revealModeOrdinal]
                : Config.RevealMode.ALWAYS;
    }

    /** Reads remaining units for a chunk; returns sentinel if unloaded/depleted. */
    private static long computeRemaining(ServerLevel lvl,
                                          net.minecraft.world.item.crafting.RecipeManager rm,
                                          ChunkPos cp) {
        LevelChunk chunk = lvl.getChunkSource().getChunkNow(cp.x, cp.z);
        if (chunk == null) return -2L;
        OreDataCapability.OreData od = OreDataCapability.getData(chunk);
        VeinRecipe vr = od.getRecipe(rm);
        if (vr == null) {
            // -4: a recipe id is set but doesn't resolve — the vein recipe isn't
            // loaded (datapack/config removed it, or a referenced id is wrong).
            // Distinct from -1 (no recipe id at all = genuinely cleared/depleted),
            // so a misconfig reads "recipe not loaded" instead of "depleted".
            return od.getRecipeId() != null ? -4L : -1L;
        }
        return od.getResourcesRemaining(vr);
    }

    /**
     * Theoretical initial units for chunk {@code cp} of deposit {@code d}.
     * Sentinel: 0 if recipe missing or infinite-mode, -2 if chunk unloaded.
     */
    private static long computeInitial(ServerLevel lvl,
                                        net.minecraft.world.item.crafting.RecipeManager rm,
                                        Deposit d, ChunkPos cp, float edgeMul, int base) {
        LevelChunk chunk = lvl.getChunkSource().getChunkNow(cp.x, cp.z);
        if (chunk == null) return -2L;
        OreDataCapability.OreData od = OreDataCapability.getData(chunk);
        VeinRecipe vr = od.getRecipe(rm);
        if (vr == null) return 0L;
        float mul = d.amountMulFor(cp, edgeMul);
        double perChunkMul = (vr.getMaxAmount() - vr.getMinAmount()) * mul + vr.getMinAmount();
        return Math.round(perChunkMul * base);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Hand-rolled FriendlyByteBuf codec (1.20.1 has no StreamCodec).
    //  encode/decode are symmetric — any reorder here MUST mirror the other.
    // ─────────────────────────────────────────────────────────────────────

    /** Write this snapshot to the buffer (symmetric to {@link #decode}). */
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name);
        buf.writeResourceLocation(typeId);
        buf.writeResourceLocation(dimension);
        buf.writeVarInt(packedChunks.size());
        for (long p : packedChunks) buf.writeVarLong(p);
        for (long r : chunkRemaining) buf.writeVarLong(r);
        for (long i : chunkInitial) buf.writeVarLong(i);
        for (int idx : chunkRecipeIndex) buf.writeVarInt(idx);
        buf.writeFloat(amountMul);
        buf.writeInt(mapColor);
        buf.writeVarInt(revealModeOrdinal);
        buf.writeVarInt(proximityBlocks);
        buf.writeDouble(replenishRatePerHour);
        buf.writeVarInt(recipeIds.size());
        for (ResourceLocation r : recipeIds) buf.writeResourceLocation(r);
        for (int w : recipeWeights) buf.writeVarInt(w);
        // Per-vein drilling outputs — parallel to recipeIds.
        for (List<DrillOutputEntry> outs : drillOutputsByRecipe) {
            buf.writeVarInt(outs.size());
            for (DrillOutputEntry e : outs) {
                buf.writeResourceLocation(e.itemId());
                buf.writeVarInt(e.count());
                buf.writeFloat(e.chance());
            }
        }
    }

    /** Read a snapshot from the buffer (symmetric to {@link #encode}). */
    public static DepositSnapshot decode(FriendlyByteBuf buf) {
        // ── Phase 1: scalar header fields ───────────────────────────────────
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        ResourceLocation typeId = buf.readResourceLocation();
        ResourceLocation dimension = buf.readResourceLocation();

        // ── Phase 2: four parallel chunk-lists (count-prefixed) ─────────────
        int n = buf.readVarInt();
        List<Long> packed = new ArrayList<>(n);
        List<Long> remaining = new ArrayList<>(n);
        List<Long> initial = new ArrayList<>(n);
        List<Integer> chunkRecipeIdx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) packed.add(buf.readVarLong());
        for (int i = 0; i < n; i++) remaining.add(buf.readVarLong());
        for (int i = 0; i < n; i++) initial.add(buf.readVarLong());
        for (int i = 0; i < n; i++) chunkRecipeIdx.add(buf.readVarInt());

        // ── Phase 3: trailing scalar metadata ───────────────────────────────
        float amountMul = buf.readFloat();
        int color = buf.readInt();
        int revealOrdinal = buf.readVarInt();
        int proximity = buf.readVarInt();
        double replenishRate = buf.readDouble();

        // ── Phase 4: deposit-level recipe pool (id + weights) ──────────────
        int rn = buf.readVarInt();
        List<ResourceLocation> recipeIds = new ArrayList<>(rn);
        List<Integer> recipeWeights = new ArrayList<>(rn);
        for (int i = 0; i < rn; i++) recipeIds.add(buf.readResourceLocation());
        for (int i = 0; i < rn; i++) recipeWeights.add(buf.readVarInt());

        // ── Phase 5: per-vein drilling outputs ─────────────────────────────
        List<List<DrillOutputEntry>> drillOutputs = new ArrayList<>(rn);
        for (int i = 0; i < rn; i++) {
            int outCount = buf.readVarInt();
            List<DrillOutputEntry> outs = new ArrayList<>(outCount);
            for (int j = 0; j < outCount; j++) {
                ResourceLocation itemId = buf.readResourceLocation();
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
}
