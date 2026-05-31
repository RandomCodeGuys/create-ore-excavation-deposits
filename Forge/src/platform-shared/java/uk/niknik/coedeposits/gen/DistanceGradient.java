package uk.niknik.coedeposits.gen;

import net.minecraft.core.BlockPos;

/**
 * Distance-from-spawn → tier conversion using a logarithmic curve. Tier=0 near
 * spawn, tier=1 at or beyond {@code max_radius}. Stateless helper.
 *
 * <p>Loader-agnostic (platform-shared); ports verbatim — pure math + {@code BlockPos}.
 */
public final class DistanceGradient {
    private DistanceGradient() {}

    /** 2D (XZ-plane) Euclidean distance between two BlockPos. Y is ignored. */
    public static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Log-curve mapping {@code distance → [0,1]}:
     * {@code tier(d) = log(1 + d/baseRadius) / log(1 + maxRadius/baseRadius)}.
     * Rises quickly near spawn, saturates toward {@code maxRadius}; clamped to 1 beyond.
     *
     * @param pos         position being evaluated (e.g. chunk centre)
     * @param spawn       world spawn used as the origin
     * @param baseRadius  scale knob — how quickly tier rises early on
     * @param maxRadius   distance at which tier saturates to ~1.0
     */
    public static float tierFraction(BlockPos pos, BlockPos spawn, float baseRadius, float maxRadius) {
        double d = distance(pos, spawn);
        double numer = Math.log(1.0 + d / baseRadius);
        double denom = Math.log(1.0 + maxRadius / baseRadius);
        double v = denom <= 0.0 ? 0.0 : numer / denom;
        return (float) Math.min(1.0, Math.max(0.0, v));
    }

    /** Standard linear interpolation; t expected in [0,1] but the formula is unclamped. */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
