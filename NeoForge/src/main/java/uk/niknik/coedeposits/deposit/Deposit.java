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
 * @param id            unique identifier; used as the SavedData primary key
 * @param typeId        resource location of the {@link DepositType} blueprint
 * @param name          server-generated label (e.g. {@code "iron@128,256"})
 *                       used for chat messages and tooltips
 * @param core          chunk where the picker first decided to spawn the deposit;
 *                       acts as the centre for the per-chunk gradient (managed
 *                       placement) or simply the single tracked chunk (COE
 *                       placement)
 * @param chunks        all chunks belonging to this deposit (Perlin-blob output
 *                       for managed; singleton for COE)
 * @param amountMul     peak {@code randomMul} value — what the core chunk uses.
 *                       Edge chunks fade toward {@code amountMul × edge_amount_mul}
 *                       via {@link #amountMulFor}
 * @param tierFraction  distance-gradient tier at placement time, in {@code [0,1]};
 *                       irrelevant for COE-placement deposits (stays at 0)
 * @param placement     who chose the chunk(s) — MANAGED (our blob) or COE
 *                       (vanilla COE generator, we just track for the map).
 *                       Codec-default: MANAGED for old SavedData files
 * @param replenishRateOverride  per-deposit replenishment rate in units/hour
 *                                that overrides the type's default. {@code 0}
 *                                (or negative) means "use type default" — see
 *                                {@link #effectiveReplenishRate}. Set by the
 *                                {@code /coedeposits replenish} command.
 *                                Codec-default: 0.0 for old SavedData files.
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
     * Resolve the effective replenishment rate (units/hour). Per-deposit
     * override wins when positive; otherwise falls back to the type's default
     * from {@code deposits.json}. Returns 0 when both are off — replenishment
     * disabled.
     *
     * @param type  the live {@link DepositType} for this deposit's typeId
     *              (callers should already have it; null is treated as
     *              "type missing", returns 0)
     */
    public double effectiveReplenishRate(DepositType type) {
        if (replenishRateOverride > 0) return replenishRateOverride;
        if (type == null) return 0.0;
        return type.replenishRatePerHour();
    }

    /**
     * Per-chunk amountMul derived from the peak ({@link #amountMul}) and the
     * chunk's Chebyshev distance from the core. Single-chunk deposits return
     * the peak as-is. Used by the picker / refill command when writing
     * {@code OreData.randomMul} for each chunk of the deposit.
     *
     * @param cp       chunk we're computing amount for (must belong to {@link #chunks})
     * @param edgeMul  edge ratio from {@link uk.niknik.coedeposits.Config#EDGE_AMOUNT_MUL}
     * @return         amountMul to write into the chunk's OreData
     */
    public float amountMulFor(ChunkPos cp, float edgeMul) {
        // ── Step 1: single-chunk fast path ──────────────────────────────────
        // Single-chunk deposits (e.g. COE-placement entries) skip the gradient
        // — there's no edge to fade toward. Return the peak as-is.
        if (chunks.size() <= 1) return amountMul;

        // ── Step 2: find the furthest blob chunk from core (Chebyshev) ──────
        // Chebyshev distance (max of |dx|, |dz|) matches how the renderer
        // perceives "edge" — the visual outline is rectangular, not circular.
        // Pre-compute once per call so the lerp denominator is stable.
        int maxDist = 0;
        for (ChunkPos c : chunks) {
            int d = Math.max(Math.abs(c.x - core.x), Math.abs(c.z - core.z));
            if (d > maxDist) maxDist = d;
        }
        // Defensive: maxDist=0 means every chunk == core (geometrically
        // impossible with size>1 but cheap to check). Avoids /0 in the lerp.
        if (maxDist == 0) return amountMul;

        // ── Step 3: lerp from peak (this=core) down to edgeMul × peak ──────
        // t = 0 at core, t = 1 at the furthest blob chunk. Output range:
        //   t=0 → amountMul × 1.0 = peak
        //   t=1 → amountMul × edgeMul
        // Smooth linear interpolation; sharper falloff curves felt punitive
        // in playtesting (depleting the inner ring effectively bricked the
        // whole blob).
        int thisDist = Math.max(Math.abs(cp.x - core.x), Math.abs(cp.z - core.z));
        float t = (float) thisDist / maxDist;
        return amountMul * (1.0f - t * (1.0f - edgeMul));
    }

    /** {@link ChunkPos} ↔ {@code long} bridge so we can encode chunk positions compactly. */
    private static final Codec<ChunkPos> CHUNK_POS_CODEC =
            Codec.LONG.xmap(ChunkPos::new, ChunkPos::toLong);

    /**
     * Codec for NBT/JSON persistence — used by
     * {@link uk.niknik.coedeposits.store.DepositSavedData}. The {@code placement}
     * field defaults to {@link DepositType.Placement#MANAGED} so SavedData
     * written before this field existed continues to load as managed deposits.
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
            // replenish_rate_override added in 0.2 — optional with default 0
            // so SavedData files predating the field still load without
            // migration. 0 means "use type default" (see effectiveReplenishRate).
            Codec.DOUBLE.optionalFieldOf("replenish_rate_override", 0.0)
                    .forGetter(Deposit::replenishRateOverride)
    ).apply(b, Deposit::new));
}
