package uk.niknik.coedeposits.deposit;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A placed deposit instance — persistent record stored in
 * {@link uk.niknik.coedeposits.store.DepositSavedData}.
 *
 * <p>Loader-agnostic (platform-shared); ports verbatim from the 1.21.1 source —
 * {@code UUIDUtil.CODEC}, {@code ChunkPos}, the codec stack are identical in 1.20.1.
 *
 * @param id            unique identifier; SavedData primary key
 * @param typeId        resource location of the {@link DepositType} blueprint
 * @param name          server-generated label (e.g. {@code "iron@128,256"})
 * @param core          chunk where the picker first decided to spawn the deposit
 * @param chunks        all chunks belonging to this deposit
 * @param amountMul     peak {@code randomMul} value — what the core chunk uses
 * @param tierFraction  distance-gradient tier at placement time, in {@code [0,1]}
 * @param placement     who chose the chunk(s) — MANAGED (our blob) or COE
 * @param replenishRateOverride  per-deposit replenishment rate (units/hour); 0 = use type default
 */
public record Deposit(
        UUID id,
        ResourceLocation typeId,
        String name,
        ChunkPos core,
        Set<ChunkPos> chunks,
        float amountMul,
        float tierFraction,
        DepositType.Placement placement,
        double replenishRateOverride) {

    /** Copy with a replaced chunk set; id / core / amountMul / tier / placement unchanged. */
    public Deposit withChunks(Set<ChunkPos> newChunks) {
        return new Deposit(id, typeId, name, core, newChunks, amountMul, tierFraction, placement, replenishRateOverride);
    }

    /**
     * Resolve the effective replenishment rate (units/hour). Per-deposit override
     * wins when positive; otherwise falls back to the type's default. Returns 0
     * when both are off.
     *
     * @param type  the live {@link DepositType} for this deposit's typeId (null → 0)
     */
    public double effectiveReplenishRate(DepositType type) {
        if (replenishRateOverride > 0) return replenishRateOverride;
        if (type == null) return 0.0;
        return type.replenishRatePerHour();
    }

    /**
     * Per-chunk amountMul derived from the peak ({@link #amountMul}) and the
     * chunk's Chebyshev distance from the core. Single-chunk deposits return the
     * peak as-is.
     *
     * @param cp       chunk we're computing amount for (must belong to {@link #chunks})
     * @param edgeMul  edge ratio from {@link uk.niknik.coedeposits.Config#EDGE_AMOUNT_MUL}
     * @return         amountMul to write into the chunk's OreData
     */
    public float amountMulFor(ChunkPos cp, float edgeMul) {
        if (chunks.size() <= 1) return amountMul;

        int maxDist = 0;
        for (ChunkPos c : chunks) {
            int d = Math.max(Math.abs(c.x - core.x), Math.abs(c.z - core.z));
            if (d > maxDist) maxDist = d;
        }
        if (maxDist == 0) return amountMul;

        int thisDist = Math.max(Math.abs(cp.x - core.x), Math.abs(cp.z - core.z));
        float t = (float) thisDist / maxDist;
        return amountMul * (1.0f - t * (1.0f - edgeMul));
    }

    /** {@link ChunkPos} ↔ {@code long} bridge for compact chunk-position encoding. */
    private static final Codec<ChunkPos> CHUNK_POS_CODEC =
            Codec.LONG.xmap(ChunkPos::new, ChunkPos::toLong);

    /**
     * Codec for NBT/JSON persistence. The {@code placement} field defaults to
     * {@link DepositType.Placement#MANAGED} so SavedData written before this field
     * existed continues to load as managed deposits.
     */
    public static final Codec<Deposit> CODEC = RecordCodecBuilder.create(b -> b.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Deposit::id),
            ResourceLocation.CODEC.fieldOf("type").forGetter(Deposit::typeId),
            Codec.STRING.fieldOf("name").forGetter(Deposit::name),
            CHUNK_POS_CODEC.fieldOf("core").forGetter(Deposit::core),
            Codec.list(CHUNK_POS_CODEC).xmap(
                    list -> (Set<ChunkPos>) new java.util.HashSet<>(list),
                    set -> set.stream().collect(Collectors.toList())
            ).fieldOf("chunks").forGetter(Deposit::chunks),
            Codec.FLOAT.fieldOf("amount_mul").forGetter(Deposit::amountMul),
            Codec.FLOAT.fieldOf("tier").forGetter(Deposit::tierFraction),
            DepositType.Placement.CODEC.optionalFieldOf("placement", DepositType.Placement.MANAGED)
                    .forGetter(Deposit::placement),
            Codec.DOUBLE.optionalFieldOf("replenish_rate_override", 0.0)
                    .forGetter(Deposit::replenishRateOverride)
    ).apply(b, Deposit::new));
}
