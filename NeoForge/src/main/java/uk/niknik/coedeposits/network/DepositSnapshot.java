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

import com.tom.createores.OreData;
import com.tom.createores.OreDataAttachment;
import com.tom.createores.recipe.VeinRecipe;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;

/**
 * Wire-friendly snapshot of a {@link Deposit}. {@code chunkRemaining} is
 * parallel to {@code packedChunks}: same length, same order. Sentinel values:
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
        int proximityBlocks) {

    /**
     * Build a snapshot from server-side state. Queries each chunk for its
     * current {@code OreData} (skipped if chunk unloaded → sentinel values).
     * The {@code dimension} field comes from {@code lvl.dimension()} — the
     * client renderer uses it to skip deposits that don't belong to the
     * player's current dimension.
     */
    public static DepositSnapshot fromDeposit(ServerLevel lvl, Deposit d) {
        List<Long> packed = new ArrayList<>(d.chunks().size());
        List<Long> remaining = new ArrayList<>(d.chunks().size());
        List<Long> initial = new ArrayList<>(d.chunks().size());
        var rm = lvl.getRecipeManager();
        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
        int base = com.tom.createores.Config.finiteAmountBase;
        for (ChunkPos cp : d.chunks()) {
            packed.add(cp.toLong());
            long rem = computeRemaining(lvl, rm, cp);
            remaining.add(rem);
            initial.add(computeInitial(lvl, rm, d, cp, edgeMul, base));
        }
        DepositType type = Coedeposits.DEPOSIT_TYPES.get(d.typeId());
        int color = (type != null && type.mapColor().isPresent()) ? type.mapColor().get() : -1;
        Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
        int proximity = Config.PROXIMITY_REVEAL_BLOCKS.get();
        return new DepositSnapshot(d.id(), d.name(), d.typeId(),
                lvl.dimension().location(),
                packed, remaining, initial,
                d.amountMul(), color, mode.ordinal(), proximity);
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
     * Hand-rolled stream codec — the parallel-list structure (chunks/remaining/initial)
     * doesn't compose cleanly with {@link StreamCodec#composite}, so we encode
     * the count once then three packed long runs.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, DepositSnapshot> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public DepositSnapshot decode(RegistryFriendlyByteBuf buf) {
                    UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
                    String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                    ResourceLocation typeId = ResourceLocation.STREAM_CODEC.decode(buf);
                    ResourceLocation dimension = ResourceLocation.STREAM_CODEC.decode(buf);
                    int n = buf.readVarInt();
                    List<Long> packed = new ArrayList<>(n);
                    List<Long> remaining = new ArrayList<>(n);
                    List<Long> initial = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) packed.add(buf.readVarLong());
                    for (int i = 0; i < n; i++) remaining.add(buf.readVarLong());
                    for (int i = 0; i < n; i++) initial.add(buf.readVarLong());
                    float amountMul = buf.readFloat();
                    int color = buf.readInt();
                    int revealOrdinal = buf.readVarInt();
                    int proximity = buf.readVarInt();
                    return new DepositSnapshot(id, name, typeId, dimension, packed, remaining, initial,
                            amountMul, color, revealOrdinal, proximity);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, DepositSnapshot v) {
                    UUIDUtil.STREAM_CODEC.encode(buf, v.id);
                    ByteBufCodecs.STRING_UTF8.encode(buf, v.name);
                    ResourceLocation.STREAM_CODEC.encode(buf, v.typeId);
                    ResourceLocation.STREAM_CODEC.encode(buf, v.dimension);
                    buf.writeVarInt(v.packedChunks.size());
                    for (long p : v.packedChunks) buf.writeVarLong(p);
                    for (long r : v.chunkRemaining) buf.writeVarLong(r);
                    for (long i : v.chunkInitial) buf.writeVarLong(i);
                    buf.writeFloat(v.amountMul);
                    buf.writeInt(v.mapColor);
                    buf.writeVarInt(v.revealModeOrdinal);
                    buf.writeVarInt(v.proximityBlocks);
                }
            };
}
