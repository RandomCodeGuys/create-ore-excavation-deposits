package uk.niknik.coedeposits.gen;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Produces an irregular blob of chunks around a core chunk by modulating a
 * disc with simplex noise. Output is the set of chunk positions that fall
 * inside {@code radius × (1 ± RADIUS_JITTER)} where the per-cell radius is
 * shifted by a Simplex sample.
 *
 * <p>Deterministic for a given seed — same seed always yields the same blob.
 * Used by {@link DepositPlacer} for natural placement and by
 * {@link DepositPlacer#forceWith} for {@code /coedeposits place ... chunks=N}.
 */
public final class PerlinShape {
    private PerlinShape() {}

    /** Noise coordinate scale — higher values give smaller features (more variation per chunk). */
    private static final double NOISE_SCALE = 0.35;

    /** Multiplier range for the radius along each direction (1.0 ± RADIUS_JITTER). */
    private static final double RADIUS_JITTER = 0.45;

    /**
     * Build the chunk-set for a deposit centred on {@code core}.
     *
     * @param core              chunk that will be the deposit's core (always included)
     * @param targetSizeChunks  approximate number of chunks the deposit should
     *                          contain. The radius is solved from area ≈
     *                          {@code sqrt(target/π)}; with jitter the actual
     *                          chunk count varies ±20% from this target
     * @param seed              random seed for the simplex noise; same seed →
     *                          identical blob shape across worlds and reloads
     * @return                  unordered set of chunk positions belonging to
     *                          this deposit; always contains {@code core}
     */
    public static Set<ChunkPos> generate(ChunkPos core, int targetSizeChunks, long seed) {
        int radius = Math.max(1, (int) Math.round(Math.sqrt(targetSizeChunks / Math.PI)));
        RandomSource rng = new LegacyRandomSource(seed);
        SimplexNoise noise = new SimplexNoise(rng);

        Set<ChunkPos> result = new HashSet<>();
        result.add(core);

        int reach = radius + 2;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx == 0 && dz == 0) continue;
                double dist = Math.sqrt(dx * dx + dz * dz);
                double n = (noise.getValue(dx * NOISE_SCALE, dz * NOISE_SCALE) + 1.0) * 0.5;
                double modRadius = radius * (1.0 - RADIUS_JITTER + n * 2.0 * RADIUS_JITTER);
                if (dist <= modRadius) {
                    result.add(new ChunkPos(core.x + dx, core.z + dz));
                }
            }
        }
        return result;
    }
}
