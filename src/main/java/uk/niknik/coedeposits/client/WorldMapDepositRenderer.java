package uk.niknik.coedeposits.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.ChunkPos;

import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.network.DepositSnapshot;

/**
 * Renders coedeposits chunks as filled coloured 16×16 quads on Xaero's
 * world map. Color is deterministic per typeId hash. On hover, builds a
 * tooltip with localized name, location, richness and per-chunk remaining
 * units extracted from {@link DepositSnapshot#chunkRemaining}.
 *
 * Called from {@link uk.niknik.coedeposits.mixin.GuiMapMixin#coedeposits$renderOverlay}.
 */
public final class WorldMapDepositRenderer {
    private WorldMapDepositRenderer() {}

    private static final int BG_ALPHA = 0x40;       // background tint
    private static final int STRIPE_ALPHA = 0xC0;   // diagonal stripes on top
    private static final int OUTLINE_ALPHA = 0xE0;  // chunk border
    private static final int STRIPE_PERIOD = 6;     // pixels between stripes
    private static final int STRIPE_WIDTH = 2;      // pixels wide

    /**
     * Entry point called from {@link uk.niknik.coedeposits.mixin.GuiMapMixin}
     * at the tail of Xaero's {@code GuiMap.render}. Applies the same pose
     * transform Xaero uses for its world content, then draws every cached
     * deposit chunk that falls inside the visible map bounds.
     *
     * <p>Per-chunk visual: solid colour fill at low alpha + diagonal stripes
     * (distinguishable from other mods' overlays) + 1-pixel outline. Alpha
     * scales by remaining/initial so depleted chunks fade out.
     *
     * @param screen           Xaero map screen — provides width/height in pixels
     * @param graphics         active draw surface
     * @param mouseX/Y         screen-space mouse position (for tooltip placement)
     * @param mapScale         Xaero's zoom scale
     * @param cameraX/Z        world position the map is centred on
     * @param mouseBlockX/Z    world-space block under the mouse (for hover hit-test)
     */
    public static void render(
            Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
            double mapScale, double cameraX, double cameraZ,
            int mouseBlockX, int mouseBlockZ) {

        if (!DepositClientCache.isEnabled()) return;       // player toggled overlay off
        var cache = DepositClientCache.all();
        if (cache.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        double guiScale = (double) window.getScreenWidth() / window.getGuiScaledWidth();
        double scale = mapScale / guiScale;

        // Visible world-block bounds — anything outside is off-screen, skip.
        // 16-block padding ensures partially-visible chunks render fully.
        double halfBlocksX = (screen.width / 2.0) / scale;
        double halfBlocksZ = (screen.height / 2.0) / scale;
        int minBlockX = (int) Math.floor(cameraX - halfBlocksX) - 16;
        int maxBlockX = (int) Math.ceil(cameraX + halfBlocksX) + 16;
        int minBlockZ = (int) Math.floor(cameraZ - halfBlocksZ) - 16;
        int maxBlockZ = (int) Math.ceil(cameraZ + halfBlocksZ) + 16;

        // Cheap mode when zoomed out — single fill per chunk, no stripes/outline.
        // Threshold ~6 pixels per chunk; below that detail is invisible anyway.
        boolean detailedDraw = scale >= 0.4;

        PoseStack pose = graphics.pose();
        pose.pushPose();

        pose.translate(screen.width / 2.0f, screen.height / 2.0f, 0);
        pose.scale((float) scale, (float) scale, 1f);
        pose.translate(-cameraX, -cameraZ, 0);

        // Player world position — needed to apply ON_PROXIMITY filtering per
        // deposit. Falls back to camera centre when there's no local player
        // (Xaero allows opening the map without a world loaded).
        int playerX = mc.player != null ? (int) mc.player.getX() : (int) cameraX;
        int playerZ = mc.player != null ? (int) mc.player.getZ() : (int) cameraZ;

        // Current dimension — used to skip snapshots that don't belong here.
        // Chunk coords are world-relative but identical between dimensions, so
        // without this check an overworld deposit at (50, 50) would draw on
        // the nether map at (50, 50) too.
        net.minecraft.resources.ResourceLocation currentDim =
                mc.level != null ? mc.level.dimension().location() : null;

        DepositSnapshot hovered = null;
        long hoveredChunkLong = 0L;
        for (DepositSnapshot d : cache) {
            // Dimension filter — hide deposits from other dimensions.
            if (currentDim != null && !currentDim.equals(d.dimension())) {
                continue;
            }

            // ON_PROXIMITY filter — hide the deposit unless the player is
            // within proximityBlocks of any of its chunks (centre block).
            if (d.revealMode() == Config.RevealMode.ON_PROXIMITY
                    && !withinProximity(d, playerX, playerZ)) {
                continue;
            }

            int rgb = d.mapColor() >= 0 ? (d.mapColor() & 0xFFFFFF) : colorFor(d.typeId().toString());

            for (int i = 0; i < d.packedChunks().size(); i++) {
                long packed = d.packedChunks().get(i);
                ChunkPos cp = new ChunkPos(packed);
                int x0 = cp.x * 16;
                int z0 = cp.z * 16;
                int x1 = x0 + 16;
                int z1 = z0 + 16;

                // Skip chunks outside the visible map area.
                if (x0 > maxBlockX || x1 < minBlockX || z0 > maxBlockZ || z1 < minBlockZ) {
                    continue;
                }

                long rem = i < d.chunkRemaining().size() ? d.chunkRemaining().get(i) : -2L;
                long init = i < d.chunkInitial().size() ? d.chunkInitial().get(i) : 0L;

                float fade = fadeFor(rem, init);
                int bg = (Math.round(BG_ALPHA * fade) << 24) | rgb;

                if (detailedDraw) {
                    int stripe = (Math.round(STRIPE_ALPHA * fade) << 24) | rgb;
                    int outline = (Math.round(OUTLINE_ALPHA * fade) << 24) | rgb;
                    graphics.fill(x0, z0, x1, z1, bg);
                    drawDiagonalStripes(graphics, x0, z0, stripe);
                    graphics.hLine(x0, x1 - 1, z0, outline);
                    graphics.hLine(x0, x1 - 1, z1 - 1, outline);
                    graphics.vLine(x0, z0, z1 - 1, outline);
                    graphics.vLine(x1 - 1, z0, z1 - 1, outline);
                } else {
                    // Zoomed-out: just a single solid fill (use stripe alpha
                    // so chunks are visible at this scale).
                    int solid = (Math.round(STRIPE_ALPHA * fade) << 24) | rgb;
                    graphics.fill(x0, z0, x1, z1, solid);
                }

                if (mouseBlockX >= x0 && mouseBlockX < x1
                        && mouseBlockZ >= z0 && mouseBlockZ < z1) {
                    hovered = d;
                    hoveredChunkLong = packed;
                }
            }
        }

        pose.popPose();

        if (hovered != null) {
            List<FormattedCharSequence> lines = buildTooltip(hovered, hoveredChunkLong).stream()
                    .map(Component::getVisualOrderText)
                    .toList();
            graphics.renderTooltip(mc.font, lines, mouseX, mouseY);
        }
    }

    /** Build the multi-line hover tooltip for the chunk under the mouse. */
    private static List<Component> buildTooltip(DepositSnapshot d, long hoveredChunkLong) {
        List<Component> out = new ArrayList<>();

        // Title — translation key "deposit.<ns>.<path>" with fallback to typeId string.
        String key = "deposit." + d.typeId().getNamespace() + "." + d.typeId().getPath();
        out.add(Component.translatableWithFallback(key, d.typeId().toString())
                .copy().withStyle(ChatFormatting.GOLD));

        // Location — extract X,Z from the server-side deposit name "<path>@x,z".
        // If parsing fails just fall back to showing the raw name.
        String coordStr = extractCoords(d.name());
        if (coordStr != null) {
            String[] parts = coordStr.split(",", 2);
            out.add(Component.translatable("coedeposits.tooltip.location",
                    parts[0], parts[1]).withStyle(ChatFormatting.DARK_GRAY));
        }

        // Stats — chunk count only, no richness/amountMul (too technical for players).
        out.add(Component.translatable("coedeposits.tooltip.stats",
                d.packedChunks().size()).withStyle(ChatFormatting.GRAY));

        // Per-chunk remaining lookup
        int hoveredIdx = d.packedChunks().indexOf(hoveredChunkLong);
        long remaining = hoveredIdx >= 0 && hoveredIdx < d.chunkRemaining().size()
                ? d.chunkRemaining().get(hoveredIdx) : -2L;
        long initial = hoveredIdx >= 0 && hoveredIdx < d.chunkInitial().size()
                ? d.chunkInitial().get(hoveredIdx) : 0L;
        MutableComponent remainLine;
        if (remaining == -2L)      remainLine = Component.translatable("coedeposits.tooltip.chunk_unknown");
        else if (remaining == -1L) remainLine = Component.translatable("coedeposits.tooltip.chunk_depleted");
        else if (remaining == 0L)  remainLine = Component.translatable("coedeposits.tooltip.chunk_infinite");
        else                       remainLine = Component.translatable("coedeposits.tooltip.chunk_remaining",
                                          String.format("%,d", remaining),
                                          String.format("%,d", Math.max(initial, remaining)));
        if      (remaining == -1L) remainLine.withStyle(ChatFormatting.RED);
        else if (remaining == -2L) remainLine.withStyle(ChatFormatting.DARK_GRAY);
        else if (remaining == 0L)  remainLine.withStyle(ChatFormatting.AQUA);
        else                       remainLine.withStyle(ChatFormatting.GREEN);
        out.add(remainLine);

        return out;
    }

    /**
     * True when the player (block X/Z) is within the deposit's
     * {@code proximityBlocks} threshold of any of its chunks' centre blocks.
     * Server-side {@link DepositSnapshot#fromDeposit} bakes the threshold
     * into the snapshot so this filter doesn't need client-side config.
     */
    private static boolean withinProximity(DepositSnapshot d, int playerX, int playerZ) {
        long radius = d.proximityBlocks();
        if (radius <= 0) return true;
        long radiusSq = radius * radius;
        for (long packed : d.packedChunks()) {
            ChunkPos cp = new ChunkPos(packed);
            long dx = cp.getMiddleBlockX() - playerX;
            long dz = cp.getMiddleBlockZ() - playerZ;
            if (dx * dx + dz * dz <= radiusSq) return true;
        }
        return false;
    }

    /** Returns the remaining-units long for a given chunk packed-long, or -2 if unknown. */
    private static long lookupRemaining(DepositSnapshot d, long chunkLong) {
        int idx = d.packedChunks().indexOf(chunkLong);
        if (idx < 0 || idx >= d.chunkRemaining().size()) return -2L;
        return d.chunkRemaining().get(idx);
    }

    /**
     * Fade factor for a chunk based on how much of its initial pool remains.
     * Returns 1.0 for fresh / infinite / unknown chunks, scales to 0.15 for
     * mostly-depleted chunks, 0.05 for fully depleted (still visible as ghost).
     */
    private static float fadeFor(long remaining, long initial) {
        if (remaining == -1L) return 0.05f;                  // depleted
        if (remaining == -2L) return 1.0f;                   // unknown
        if (remaining == 0L || initial <= 0L) return 1.0f;   // infinite
        float ratio = Math.min(1.0f, (float) remaining / initial);
        return 0.15f + 0.85f * ratio;                        // never fully invisible
    }

    /**
     * Diagonal stripes inside a 16×16 chunk box, distinct from solid-fill overlays
     * other mods draw (so players don't confuse our chunks with Xaero waypoints,
     * region outlines from xaero-map-regions, etc.). One row per pixel y; pixels
     * where ((x + y) % STRIPE_PERIOD) is in [0, STRIPE_WIDTH) become stripe pixels.
     * We group consecutive stripe pixels per row into a single graphics.fill call.
     */
    private static void drawDiagonalStripes(GuiGraphics g, int x0, int z0, int stripeColor) {
        for (int dy = 0; dy < 16; dy++) {
            int runStart = -1;
            for (int dx = 0; dx < 16; dx++) {
                boolean on = ((dx + dy) % STRIPE_PERIOD) < STRIPE_WIDTH;
                if (on && runStart < 0) runStart = dx;
                else if (!on && runStart >= 0) {
                    g.fill(x0 + runStart, z0 + dy, x0 + dx, z0 + dy + 1, stripeColor);
                    runStart = -1;
                }
            }
            if (runStart >= 0) g.fill(x0 + runStart, z0 + dy, x0 + 16, z0 + dy + 1, stripeColor);
        }
    }

    /** Parse "<path>@x,z" → "x,z" or return null if unrecognised. */
    private static String extractCoords(String name) {
        int at = name.lastIndexOf('@');
        if (at < 0 || at == name.length() - 1) return null;
        String tail = name.substring(at + 1);
        return tail.contains(",") ? tail : null;
    }

    /**
     * Deterministic colour fallback when a deposit_type doesn't declare
     * {@code map_color}. Hashes the typeId string and shifts into a mid-range
     * RGB so colours are always at least somewhat visible against the map.
     */
    private static int colorFor(String typeId) {
        int h = typeId.hashCode();
        int r = 80 + ((h >>> 16) & 0x7F);
        int g = 80 + ((h >>> 8) & 0x7F);
        int b = 80 + (h & 0x7F);
        return (r << 16) | (g << 8) | b;
    }
}
