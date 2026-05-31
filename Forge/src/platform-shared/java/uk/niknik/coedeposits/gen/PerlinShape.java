package uk.niknik.coedeposits.gen;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Produces an irregular blob of chunks around a core chunk by modulating a disc
 * with simplex noise. Deterministic for a given seed.
 *
 * <p>Loader-agnostic (platform-shared); ports verbatim — {@code RandomSource},
 * {@code LegacyRandomSource}, {@code SimplexNoise} are identical in 1.20.1.
 */
public final class PerlinShape {
    private PerlinShape() {}

    /** Noise coordinate scale — higher values give smaller features. */
    private static final double NOISE_SCALE = 0.35;

    /** Multiplier range for the radius along each direction (1.0 ± RADIUS_JITTER). */
    private static final double RADIUS_JITTER = 0.45;

    /**
     * Build the chunk-set for a deposit centred on {@code core}.
     *
     * @param core              chunk that will be the deposit's core (always included)
     * @param targetSizeChunks  approximate number of chunks the deposit should contain
     * @param seed              random seed; same seed → identical blob shape
     * @return                  unordered set of chunk positions; always contains {@code core}
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
                if (dx == 0 && dz == 0) continue;  // core already added
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
