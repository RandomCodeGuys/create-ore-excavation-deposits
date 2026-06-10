package uk.niknik.coedeposits.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

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
 * Renders coedeposits as solid coloured blobs on Xaero's world map. Each
 * deposit's footprint is drawn by merging contiguous chunks of identical
 * colour into one {@code graphics.fill()} per row, then outlining only the
 * deposit's external perimeter — avoids the 50+ draw calls per chunk that
 * a striped/per-chunk-outlined renderer would issue.
 *
 * <p>Color is the type's {@code map_color} JSON field or a deterministic
 * hash of the typeId. On hover, builds a tooltip with localized name,
 * location, composition, drill yields and per-chunk remaining units.
 *
 * Called from {@link uk.niknik.coedeposits.mixin.GuiMapMixin#coedeposits$renderOverlay}.
 */
public final class WorldMapDepositRenderer {
    private WorldMapDepositRenderer() {}

    private static final int FILL_ALPHA = 0xB0;     // solid fill across deposit footprint
    private static final int OUTLINE_ALPHA = 0xF0;  // external perimeter of deposit

    /**
     * Solid grey for filler / tailings chunks (sentinel -3 in
     * {@link DepositSnapshot#chunkRemaining}). Distinct from the type's own
     * map_color so admins reading the map can tell at a glance which chunks
     * of a deposit are productive vs which are filler. Chosen warm-grey so
     * it reads as "rock" rather than "ore that's mid-depletion".
     */
    private static final int FILLER_COLOR_GREY = 0x9C9082;

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

        // Reset the share-keybind handoff every frame; Phase 7 re-sets it while
        // a deposit is actually hovered, so it can never go stale.
        shareHoveredScreen = null;
        shareHoveredId = null;

        // ── Phase 1: cheap early-exits ──────────────────────────────────────
        // Both guards avoid the per-frame pose-stack setup cost when there's
        // nothing to draw.
        if (!DepositClientCache.isEnabled()) return;       // player toggled overlay off
        var cache = DepositClientCache.all();
        if (cache.isEmpty()) return;

        // ── Phase 2: compute screen↔world scale and visible bounds ──────────
        // Xaero's mapScale is in absolute pixels per block; we need it relative
        // to MC's GUI scale so our draws line up with Xaero's tiles.
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

        // ── Phase 3: pick LOD ───────────────────────────────────────────────
        // Detailed mode adds an external perimeter outline around the deposit
        // blob. Below this threshold the outline is invisible anyway and the
        // line draws cost more than they're worth.
        boolean detailedDraw = scale >= 0.4;

        // ── Phase 4: apply Xaero's world-space pose ─────────────────────────
        // Combination of translate-to-centre + scale + translate-by-camera
        // maps world coordinates directly to screen pixels so we can pass
        // x0/y0 = chunkX*16 / chunkZ*16 to graphics.fill() unchanged.
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(screen.width / 2.0f, screen.height / 2.0f, 0);
        pose.scale((float) scale, (float) scale, 1f);
        pose.translate(-cameraX, -cameraZ, 0);

        // ── Phase 5: pre-loop snapshots used by every iteration ─────────────
        // Player world position drives the ON_PROXIMITY filter. Falls back to
        // camera centre when there's no local player (Xaero allows opening
        // the map without a world loaded).
        int playerX = mc.player != null ? (int) mc.player.getX() : (int) cameraX;
        int playerZ = mc.player != null ? (int) mc.player.getZ() : (int) cameraZ;

        // Current dimension — used to skip snapshots that don't belong here.
        // Chunk coords are world-relative but identical between dimensions, so
        // without this check an overworld deposit at (50, 50) would draw on
        // the nether map at (50, 50) too.
        net.minecraft.resources.ResourceLocation currentDim =
                mc.level != null ? mc.level.dimension().location() : null;

        // ── Phase 6: per-deposit draw loop ──────────────────────────────────
        // For each deposit we:
        //   1. Build a per-chunk array of (cp, color) tuples for visible chunks
        //      only — filler chunks get the shared grey, ore chunks get the
        //      type colour with per-chunk fade alpha.
        //   2. Sort by (z, x) and merge contiguous runs of same-color chunks
        //      into a single graphics.fill — usually collapses 10-30 chunks
        //      per row into 1-3 fills.
        //   3. Draw the deposit's external perimeter (only edges where the
        //      neighbour chunk is NOT part of this deposit) as 1-pixel lines.
        // Tooltip-tracking unchanged: latest hovered chunk wins.
        DepositSnapshot hovered = null;
        long hoveredChunkLong = 0L;
        List<ChunkFill> rowBuf = new ArrayList<>();
        for (DepositSnapshot d : cache) {
            // Filter 1: dimension match.
            if (currentDim != null && !currentDim.equals(d.dimension())) {
                continue;
            }

            // Filter 2: ON_PROXIMITY reveal mode — hide the deposit unless the
            // player is within proximityBlocks of any of its chunks (centre).
            if (d.revealMode() == Config.RevealMode.ON_PROXIMITY
                    && !withinProximity(d, playerX, playerZ)) {
                continue;
            }

            // Colour comes from the type's map_color JSON field, or falls
            // back to a deterministic hash of the typeId string.
            int rgb = d.mapColor() >= 0 ? (d.mapColor() & 0xFFFFFF) : colorFor(d.typeId().toString());

            // ── 6a: collect visible chunks + hover hit-test ──────────────
            // chunkSet is needed for the outline-perimeter pass — O(1)
            // neighbour lookups instead of O(N) list scans.
            int n = d.packedChunks().size();
            LongOpenHashSet chunkSet = new LongOpenHashSet(n);
            rowBuf.clear();
            for (int i = 0; i < n; i++) {
                long packed = d.packedChunks().get(i);
                chunkSet.add(packed);
                ChunkPos cp = new ChunkPos(packed);
                int x0 = cp.x * 16;
                int z0 = cp.z * 16;
                int x1 = x0 + 16;
                int z1 = z0 + 16;

                // Hover hit-test against this chunk in world-space — must
                // happen even for off-screen chunks (mouse could be over a
                // partially-visible edge chunk).
                if (mouseBlockX >= x0 && mouseBlockX < x1
                        && mouseBlockZ >= z0 && mouseBlockZ < z1) {
                    hovered = d;
                    hoveredChunkLong = packed;
                }

                // Cull chunks outside the visible map area.
                if (x0 > maxBlockX || x1 < minBlockX || z0 > maxBlockZ || z1 < minBlockZ) {
                    continue;
                }

                // Per-chunk fade — depleted chunks ghost out, infinite/unknown
                // render at full opacity. Filler chunks use the shared grey.
                long rem = i < d.chunkRemaining().size() ? d.chunkRemaining().get(i) : -2L;
                long init = i < d.chunkInitial().size() ? d.chunkInitial().get(i) : 0L;
                float fade = fadeFor(rem, init);
                int chunkRgb = (rem == -3L) ? FILLER_COLOR_GREY : rgb;
                int color = (Math.round(FILL_ALPHA * fade) << 24) | chunkRgb;
                rowBuf.add(new ChunkFill(cp.x, cp.z, color));
            }

            // ── 6b: sort by (z, x) and merge horizontal runs ─────────────
            // Same color + same row + contiguous X → one graphics.fill().
            rowBuf.sort(CHUNK_FILL_ORDER);
            int sz = rowBuf.size();
            int i = 0;
            while (i < sz) {
                ChunkFill start = rowBuf.get(i);
                int j = i + 1;
                while (j < sz) {
                    ChunkFill cur = rowBuf.get(j);
                    ChunkFill prev = rowBuf.get(j - 1);
                    if (cur.z != start.z || cur.color != start.color || cur.x != prev.x + 1) break;
                    j++;
                }
                ChunkFill end = rowBuf.get(j - 1);
                graphics.fill(start.x * 16, start.z * 16, end.x * 16 + 16, start.z * 16 + 16, start.color);
                i = j;
            }

            // ── 6c: external-perimeter outline (detailed mode only) ──────
            // Draw a 1-pixel line on each chunk edge that faces a non-member
            // neighbour. Inner edges (between two chunks of the deposit) are
            // skipped — this gives one clean outline around the whole blob.
            if (detailedDraw) {
                int outline = (OUTLINE_ALPHA << 24) | rgb;
                for (int k = 0; k < n; k++) {
                    long packed = d.packedChunks().get(k);
                    ChunkPos cp = new ChunkPos(packed);
                    int x0 = cp.x * 16;
                    int z0 = cp.z * 16;
                    int x1 = x0 + 16;
                    int z1 = z0 + 16;
                    if (x0 > maxBlockX || x1 < minBlockX || z0 > maxBlockZ || z1 < minBlockZ) {
                        continue;
                    }
                    if (!chunkSet.contains(ChunkPos.asLong(cp.x, cp.z - 1)))
                        graphics.hLine(x0, x1 - 1, z0, outline);
                    if (!chunkSet.contains(ChunkPos.asLong(cp.x, cp.z + 1)))
                        graphics.hLine(x0, x1 - 1, z1 - 1, outline);
                    if (!chunkSet.contains(ChunkPos.asLong(cp.x - 1, cp.z)))
                        graphics.vLine(x0, z0, z1 - 1, outline);
                    if (!chunkSet.contains(ChunkPos.asLong(cp.x + 1, cp.z)))
                        graphics.vLine(x1 - 1, z0, z1 - 1, outline);
                }
            }
        }

        // Restore screen-space pose for the tooltip pass.
        pose.popPose();

        // ── Phase 7: tooltip for hovered chunk (if any) ─────────────────────
        // renderTooltip uses raw mouseX/Y in screen space — that's why the
        // pose was popped first. Lines are built from the deposit metadata
        // + per-chunk remaining via buildTooltip.
        if (hovered != null) {
            // Hand the hovered deposit to the share keybind (CoedepositsClientEvents
            // polls hoveredDepositId on consumeClick).
            shareHoveredScreen = screen;
            shareHoveredId = hovered.id();
            List<FormattedCharSequence> lines = buildTooltip(hovered, hoveredChunkLong).stream()
                    .map(Component::getVisualOrderText)
                    .toList();
            graphics.renderTooltip(mc.font, lines, mouseX, mouseY);
        }
    }

    /** Hovered-deposit handoff to the share keybind: written every frame by render(). */
    private static volatile Screen shareHoveredScreen = null;
    private static volatile java.util.UUID shareHoveredId = null;

    /** Deposit id under the mouse when {@code screen} is the live map screen, else null. */
    public static java.util.UUID hoveredDepositId(Screen screen) {
        return screen != null && screen == shareHoveredScreen ? shareHoveredId : null;
    }

    /** Build the multi-line hover tooltip for the chunk under the mouse. */
    private static List<Component> buildTooltip(DepositSnapshot d, long hoveredChunkLong) {
        List<Component> out = new ArrayList<>();

        // ── Phase 1: title line (localized deposit name) ────────────────────
        // translatableWithFallback lets resource-packs override per-deposit
        // display names without hardcoding strings — falls back to the raw
        // typeId when no translation exists for the player's locale.
        String key = "deposit." + d.typeId().getNamespace() + "." + d.typeId().getPath();
        out.add(Component.translatableWithFallback(key, d.typeId().toString())
                .copy().withStyle(ChatFormatting.GOLD));

        // ── Phase 2: location line (extracted from deposit name) ────────────
        // Deposit names follow "<path>@x,z" convention (see Deposit ctor).
        // extractCoords parses the suffix; null result means a future name
        // scheme we don't recognise — skip the line silently.
        String coordStr = extractCoords(d.name());
        if (coordStr != null) {
            String[] parts = coordStr.split(",", 2);
            out.add(Component.translatable("coedeposits.tooltip.location",
                    parts[0], parts[1]).withStyle(ChatFormatting.DARK_GRAY));
        }

        // ── Phase 3: stats line (chunk count only) ──────────────────────────
        // Intentionally NOT showing amountMul / tierFraction — they're
        // implementation-level numbers that confuse players. The richness
        // info comes through the per-chunk remaining line below instead.
        out.add(Component.translatable("coedeposits.tooltip.stats",
                d.packedChunks().size()).withStyle(ChatFormatting.GRAY));

        // ── Phase 4: deposit composition line (only when >1 recipe) ────────
        // For multi-recipe deposits, list all constituent ores and their
        // percentage shares so the player knows what they're hunting before
        // they start drilling. Single-recipe deposits skip this line — the
        // title already tells them what the deposit yields.
        if (d.recipeIds().size() > 1) {
            int totalWeight = 0;
            for (int w : d.recipeWeights()) totalWeight += w;
            StringBuilder oresList = new StringBuilder();
            for (int i = 0; i < d.recipeIds().size(); i++) {
                if (i > 0) oresList.append(", ");
                String oreName = formatRecipeName(d.recipeIds().get(i));
                int pct = totalWeight > 0
                        ? Math.round(d.recipeWeights().get(i) * 100f / totalWeight)
                        : 0;
                oresList.append(oreName).append(" (").append(pct).append("%)");
            }
            out.add(Component.translatable("coedeposits.tooltip.ores", oresList.toString())
                    .withStyle(ChatFormatting.YELLOW));
        }

        // ── Phase 4b: drill yields line — show actual per-cycle outputs ────
        // For the hovered chunk's recipe, render its drilling recipe's output
        // list as "drill yields: raw iron 65%, raw copper 35%, cobblestone 20%".
        // Only shown when the drill produces something interesting — multiple
        // outputs, or a single output with chance < 1.0. A simple iron-only
        // recipe (one output, chance 1.0) is redundant with the deposit title.
        // Filler chunks (chunkRecipeIndex == -1) skip this line entirely —
        // the dedicated "tailings — no ore" line already conveys the state.
        int previewIdx = -1;
        boolean hoverIsFiller = false;
        int hoverIdxForDrill = d.packedChunks().indexOf(hoveredChunkLong);
        if (hoverIdxForDrill >= 0 && hoverIdxForDrill < d.chunkRecipeIndex().size()) {
            int idx = d.chunkRecipeIndex().get(hoverIdxForDrill);
            if (idx >= 0 && idx < d.drillOutputsByRecipe().size()) previewIdx = idx;
            else if (idx == -1) hoverIsFiller = true;
        }
        // Fallback to the first recipe when we couldn't resolve the chunk-
        // specific recipe (e.g. defensive guard, never triggers in practice).
        if (previewIdx < 0 && !hoverIsFiller && !d.drillOutputsByRecipe().isEmpty()) {
            previewIdx = 0;
        }

        if (previewIdx >= 0) {
            List<DepositSnapshot.DrillOutputEntry> outs = d.drillOutputsByRecipe().get(previewIdx);
            boolean isInteresting = outs.size() > 1
                    || (outs.size() == 1 && outs.get(0).chance() < 1.0f);
            if (isInteresting) {
                // Header + one indented line per output, so long recipe lists
                // stack vertically instead of stretching the tooltip off-screen.
                out.add(Component.translatable("coedeposits.tooltip.drill_yields_header")
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
                for (DepositSnapshot.DrillOutputEntry o : outs) {
                    out.add(Component.literal("  " + formatDrillOutput(o))
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
                }
            }
        }

        // ── Phase 4c: replenish rate line (only when > 0) ───────────────────
        // Deposit-level info: server's effective replenish rate (combining
        // type default with any per-deposit override set via /coedeposits
        // replenish). Skipped when rate is 0 — most deposits aren't
        // replenishing by default, so the line would be visual noise.
        if (d.replenishRatePerHour() > 0.0) {
            String rateStr = d.replenishRatePerHour() >= 100
                    ? String.format("%,.0f", d.replenishRatePerHour())
                    : String.format("%.1f", d.replenishRatePerHour());
            out.add(Component.translatable("coedeposits.tooltip.replenishing", rateStr)
                    .withStyle(ChatFormatting.AQUA));
        }

        // ── Phase 5: per-chunk remaining line with sentinel routing ─────────
        // Sentinel-aware: -3 = filler, -2 = unloaded (we don't know),
        // -1 = depleted, 0 = infinite vein, >0 = real remaining count. Each
        // sentinel gets a distinct translation key + colour so the player can
        // tell at a glance which case applies. For ore chunks of a multi-
        // recipe deposit, the chunk's specific ore name is prepended (so the
        // player knows what THIS chunk yields, not just the deposit overall).
        int hoveredIdx = d.packedChunks().indexOf(hoveredChunkLong);
        long remaining = hoveredIdx >= 0 && hoveredIdx < d.chunkRemaining().size()
                ? d.chunkRemaining().get(hoveredIdx) : -2L;
        long initial = hoveredIdx >= 0 && hoveredIdx < d.chunkInitial().size()
                ? d.chunkInitial().get(hoveredIdx) : 0L;
        int recipeIdx = hoveredIdx >= 0 && hoveredIdx < d.chunkRecipeIndex().size()
                ? d.chunkRecipeIndex().get(hoveredIdx) : -1;

        MutableComponent remainLine;
        if (remaining == -3L)      remainLine = Component.translatable("coedeposits.tooltip.chunk_filler");
        else if (remaining == -2L) remainLine = Component.translatable("coedeposits.tooltip.chunk_unknown");
        else if (remaining == -1L) remainLine = Component.translatable("coedeposits.tooltip.chunk_depleted");
        else if (remaining == 0L)  remainLine = Component.translatable("coedeposits.tooltip.chunk_infinite");
        else {
            // Ore chunk with a known recipe — use the "named" variant of the
            // remaining-line key so the format includes the ore name.
            // Multi-recipe deposits route here with the chunk's specific ore;
            // single-recipe falls through to the legacy chunk_remaining key
            // (no name needed — the deposit title already conveys it).
            boolean showOreName = recipeIdx >= 0 && recipeIdx < d.recipeIds().size()
                    && d.recipeIds().size() > 1;
            if (showOreName) {
                String oreName = formatRecipeName(d.recipeIds().get(recipeIdx));
                remainLine = Component.translatable("coedeposits.tooltip.chunk_remaining_named",
                        oreName,
                        String.format("%,d", remaining),
                        // Math.max guards against initial<remaining
                        // (can happen if initial was computed before a refill).
                        String.format("%,d", Math.max(initial, remaining)));
            } else {
                remainLine = Component.translatable("coedeposits.tooltip.chunk_remaining",
                        String.format("%,d", remaining),
                        String.format("%,d", Math.max(initial, remaining)));
            }
        }
        // Colour-code by state — players associate red=bad, green=good
        // intuitively, so the route maps cleanly to deposit health.
        if      (remaining == -3L) remainLine.withStyle(ChatFormatting.GRAY);
        else if (remaining == -1L) remainLine.withStyle(ChatFormatting.RED);
        else if (remaining == -2L) remainLine.withStyle(ChatFormatting.DARK_GRAY);
        else if (remaining == 0L)  remainLine.withStyle(ChatFormatting.AQUA);
        else                       remainLine.withStyle(ChatFormatting.GREEN);
        out.add(remainLine);

        // Share-keybind hint — only when the player has actually bound the key
        // (default is unbound), so the tooltip doesn't advertise a dead button.
        if (!CoedepositsClientEvents.SHARE_DEPOSIT.isUnbound()) {
            out.add(Component.literal("[")
                    .append(CoedepositsClientEvents.SHARE_DEPOSIT.getTranslatedKeyMessage())
                    .append(Component.literal("] share to chat"))
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }

        return out;
    }

    /**
     * Format a vein-recipe id into a human-readable ore name for the tooltip.
     * Tries a translation key first ({@code coedeposits.recipe.<ns>.<path>})
     * so resource-packs can localise — falls back to a heuristic format on
     * the path: strip trailing {@code "_vein"} suffix (COE recipes typically
     * end with it), then replace underscores with spaces.
     *
     * <p>Examples (without translation):
     * <ul>
     *   <li>{@code coedeposits:iron_vein} → {@code "iron"}</li>
     *   <li>{@code coedeposits:nether_quartz_vein} → {@code "nether quartz"}</li>
     *   <li>{@code mymod:custom_ore} → {@code "custom ore"}</li>
     * </ul>
     */
    /**
     * Format ONE drilling output for a line of the "drill yields" tooltip
     * column. {@code chance == 1.0} shows just the name; lower chances append
     * " 65%". Multi-count outputs prefix the name with "{N}× ".
     *
     * <p>Examples: {@code "Raw Iron"}, {@code "Raw Copper 50%"},
     * {@code "64× Crushed Raw Uranium 20%"}.
     */
    private static String formatDrillOutput(DepositSnapshot.DrillOutputEntry e) {
        StringBuilder sb = new StringBuilder();
        String name = formatItemName(e.itemId());
        if (e.count() > 1) {
            sb.append(e.count()).append("× ");
        }
        sb.append(name);
        // Chance display: hide when 1.0 (guaranteed drop — clutters with 100%
        // on every guaranteed item), show as integer percentage otherwise.
        if (e.chance() < 1.0f) {
            int pct = Math.round(e.chance() * 100f);
            sb.append(' ').append(pct).append('%');
        }
        return sb.toString();
    }

    /**
     * Resolve an item id into its display name via the item registry's
     * description id (which routes through the active lang file). Falls back
     * to the path with underscores → spaces when the item is unknown
     * (admin renamed/removed an item that's still referenced by a recipe).
     */
    private static String formatItemName(net.minecraft.resources.ResourceLocation itemId) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId);
        if (item != null && item != net.minecraft.world.item.Items.AIR) {
            return Component.translatable(item.getDescriptionId()).getString();
        }
        return itemId.getPath().replace('_', ' ');
    }

    private static String formatRecipeName(net.minecraft.resources.ResourceLocation recipeId) {
        String path = recipeId.getPath();
        if (path.endsWith("_vein")) {
            path = path.substring(0, path.length() - "_vein".length());
        }
        String fallback = path.replace('_', ' ');
        String key = "coedeposits.recipe." + recipeId.getNamespace() + "." + recipeId.getPath();
        // Build a translatable, then resolve it to a string via Component's
        // own getString() — gives us the localised result if a key exists,
        // or the fallback string otherwise. Cheap (no rendering, just lookup).
        return Component.translatableWithFallback(key, fallback).getString();
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

    /**
     * Fade factor for a chunk based on how much of its initial pool remains.
     * Returns 1.0 for fresh / infinite / unknown chunks, scales to 0.15 for
     * mostly-depleted chunks, 0.05 for fully depleted (still visible as ghost).
     */
    private static float fadeFor(long remaining, long initial) {
        if (remaining == -3L) return 0.55f;                  // filler / tailings — visible but muted
        if (remaining == -1L) return 0.05f;                  // depleted
        if (remaining == -2L) return 1.0f;                   // unknown
        if (remaining == 0L || initial <= 0L) return 1.0f;   // infinite
        float ratio = Math.min(1.0f, (float) remaining / initial);
        return 0.15f + 0.85f * ratio;                        // never fully invisible
    }

    /**
     * Per-chunk record for the run-merging pass. Holds chunk-space (x, z)
     * (not block-space) plus the resolved fill colour for this chunk so the
     * pass can group adjacent chunks of identical colour into one fill call.
     */
    private record ChunkFill(int x, int z, int color) {}

    /** Sort by row (z) first, then column (x) — required for run merging. */
    private static final Comparator<ChunkFill> CHUNK_FILL_ORDER =
            Comparator.comparingInt((ChunkFill c) -> c.z).thenComparingInt(c -> c.x);

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
