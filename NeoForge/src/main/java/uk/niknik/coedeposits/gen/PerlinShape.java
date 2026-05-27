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
        // Step 1: derive a base radius (in chunks) from the requested area.
        // For target=N chunks we want a disc of area N, hence r = sqrt(N/π).
        // Round to nearest int but never less than 1 — a single-chunk deposit
        // (target=1, radius=1) still needs the loop to run at least once.
        int radius = Math.max(1, (int) Math.round(Math.sqrt(targetSizeChunks / Math.PI)));

        // Step 2: seed a stable per-deposit noise field. LegacyRandomSource is
        // the vanilla MC primitive (deterministic across JVMs); SimplexNoise
        // gives smoother edges than Perlin for blob shapes.
        RandomSource rng = new LegacyRandomSource(seed);
        SimplexNoise noise = new SimplexNoise(rng);

        // Core chunk is always part of the blob — it's the deposit's anchor,
        // and the per-chunk-amount gradient is centred on it.
        Set<ChunkPos> result = new HashSet<>();
        result.add(core);

        // Step 3: scan every cell within (radius + jitter slack). The +2
        // padding lets the noise push the boundary slightly outward without
        // truncating the blob — jitter can reach radius * (1 + RADIUS_JITTER)
        // which for default 0.45 means we need at least +0.45*radius headroom.
        // Two extra cells (+2) covers up to radius=4 safely; bigger blobs miss
        // at most a couple of edge chunks which doesn't affect the deposit's
        // visual footprint enough to matter.
        int reach = radius + 2;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx == 0 && dz == 0) continue;  // core already added
                double dist = Math.sqrt(dx * dx + dz * dz);

                // Sample noise in [-1, 1] then map to [0, 1] — n=0 collapses
                // modRadius to (1 - RADIUS_JITTER) × radius, n=1 expands it to
                // (1 + RADIUS_JITTER) × radius. The 2.0 factor unfolds the
                // 0.5 normalisation back into a full ±RADIUS_JITTER swing.
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
