package uk.niknik.coedeposits.gen;

import net.minecraft.core.BlockPos;

/**
 * Distance-from-spawn → tier conversion using a logarithmic curve. Tier is
 * the canonical input to size, amount and richness scaling: tier=0 near
 * spawn, tier=1 at or beyond {@code max_radius}. Stateless helper class.
 */
public final class DistanceGradient {
    private DistanceGradient() {}

    /** 2D (XZ-plane) Euclidean distance between two BlockPos. Y is ignored. */
    public static double distance(BlockPos a, BlockPos b) {
        // Y-ignored so a player digging into the ground doesn't see their
        // tier suddenly spike — tier is a horizontal exploration metric.
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Log-curve mapping {@code distance → [0,1]}.
     * <pre>
     *   tier(d) = log(1 + d / baseRadius) / log(1 + maxRadius / baseRadius)
     * </pre>
     * The curve rises quickly near spawn (each new block of exploration adds
     * more tier per block) and saturates as the player approaches
     * {@code maxRadius}. Beyond {@code maxRadius} the result is clamped to 1.
     *
     * @param pos         position being evaluated (e.g. chunk centre)
     * @param spawn       world spawn used as the origin
     * @param baseRadius  scale knob — controls how quickly tier rises early on
     * @param maxRadius   distance at which tier saturates to ~1.0
     */
    public static float tierFraction(BlockPos pos, BlockPos spawn, float baseRadius, float maxRadius) {
        double d = distance(pos, spawn);
        // Log curve: numerator grows fast at small d, slowly at large d.
        // Normalised by the same curve evaluated at maxRadius so tier(maxR)=1.
        double numer = Math.log(1.0 + d / baseRadius);
        double denom = Math.log(1.0 + maxRadius / baseRadius);
        // denom can be <=0 only when maxRadius <= 0 (misconfig); treat as
        // tier=0 to avoid NaN/-Inf rather than failing.
        double v = denom <= 0.0 ? 0.0 : numer / denom;
        // Clamp to [0, 1] — beyond maxRadius the formula keeps growing, but
        // callers expect a bounded tier for lerp arithmetic.
        return (float) Math.min(1.0, Math.max(0.0, v));
    }

    /** Standard linear interpolation; t expected in [0,1] but the formula is unclamped. */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
