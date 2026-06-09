package uk.niknik.coedeposits.compat.xaero;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.neoforged.fml.ModList;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.network.DepositDiscoveryPayload;

/**
 * Client-side handler for {@link DepositDiscoveryPayload}. Always emits a
 * chat message with click-to-{@code /tp}; if Xaero's Minimap is present,
 * additionally attempts to add a waypoint via reflection into its internal
 * API (Xaero is closed-source so we can't compile against it).
 */
public final class XaeroBridge {
    private XaeroBridge() {}

    /** Mod id of Xaero's Minimap (waypoint API lives here, not in World Map). */
    private static final String XAERO_MINIMAP = "xaerominimap";

    /** Client handler entry — runs on the main thread via IPayloadContext.enqueueWork. */
    public static void onDepositDiscovered(DepositDiscoveryPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Chat line is opt-out (the map marker / Xaero waypoint still appear).
        if (Config.DISCOVERY_CHAT.get()) {
            mc.player.sendSystemMessage(buildDiscoveryMessage(p));
        }

        if (ModList.get().isLoaded(XAERO_MINIMAP)) {
            tryAddXaeroWaypoint(p);
        }
    }

    /**
     * Render the discovery line from {@link Config#DISCOVERY_MESSAGE_FORMAT},
     * substituting placeholders. Literal text between placeholders is passed
     * through verbatim (Minecraft {@code §} colour codes render); unknown
     * {@code %token%}s are left as-is.
     */
    private static MutableComponent buildDiscoveryMessage(DepositDiscoveryPayload p) {
        String fmt = Config.DISCOVERY_MESSAGE_FORMAT.get();
        MutableComponent out = Component.empty();
        int i = 0;
        while (i < fmt.length()) {
            int pct = fmt.indexOf('%', i);
            if (pct < 0) {                                   // no more tokens — rest is literal
                out.append(Component.literal(fmt.substring(i)));
                break;
            }
            if (pct > i) out.append(Component.literal(fmt.substring(i, pct)));
            int end = fmt.indexOf('%', pct + 1);
            if (end < 0) {                                   // dangling '%' — emit it literally
                out.append(Component.literal(fmt.substring(pct)));
                break;
            }
            out.append(placeholder(fmt.substring(pct + 1, end), p));
            i = end + 1;
        }
        return out;
    }

    /** Resolve a single {@code %token%} to its styled component. */
    private static MutableComponent placeholder(String token, DepositDiscoveryPayload p) {
        return switch (token) {
            case "name" -> Component.literal(p.name()).withStyle(ChatFormatting.GOLD);
            case "pos"  -> coordComponent(p);
            case "x"    -> Component.literal(Integer.toString(p.pos().getX()));
            case "y"    -> Component.literal(Integer.toString(p.pos().getY()));
            case "z"    -> Component.literal(Integer.toString(p.pos().getZ()));
            case "type" -> Component.literal(p.typeId().toString()).withStyle(ChatFormatting.AQUA);
            case ""     -> Component.literal("%");           // %% → a literal percent
            default     -> Component.literal("%" + token + "%");  // unknown token, leave verbatim
        };
    }

    /** Clickable "[x, ~, z]" coordinate that suggests {@code /tp}. */
    private static MutableComponent coordComponent(DepositDiscoveryPayload p) {
        return Component.literal(String.format("[%d, ~, %d]", p.pos().getX(), p.pos().getZ()))
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                "/tp @s " + p.pos().getX() + " ~ " + p.pos().getZ()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to suggest /tp"))));
    }

    /**
     * Best-effort Xaero waypoint creation via reflection. Tolerates any
     * upstream API drift — silent debug log on failure since the chat
     * message above already gave the player the coordinates.
     */
    private static void tryAddXaeroWaypoint(DepositDiscoveryPayload p) {
        // Xaero is closed-source with no published maven artifact, so we attempt
        // known internal API paths via reflection. If any signature changes
        // upstream the catch logs once and we fall back to the chat-suggest path.
        try {
            // ── Step 1: locate the active MinimapSession ────────────────────
            // Newer Xaero (1.21+): xaero.hud.minimap.module.MinimapSession.getCurrentSession()
            // returns the per-world session, or null when no world is loaded
            // (e.g. user is on the title screen — shouldn't happen in our
            // discovery flow but defensive null-check kept anyway).
            Class<?> sessionCls = Class.forName("xaero.hud.minimap.module.MinimapSession");
            Object session = sessionCls.getMethod("getCurrentSession").invoke(null);
            if (session == null) {
                Coedeposits.LOGGER.debug("[xaero] no current MinimapSession (player not in world?)");
                return;
            }

            // ── Step 2: walk session → waypointsManager → world → set ───────
            // Each layer is reflectively resolved; null at any step means the
            // current dimension has no waypoint storage configured yet (Xaero
            // creates per-dim sets lazily). Bail silently — chat fallback is
            // enough.
            Object wpMgr = session.getClass().getMethod("getWaypointsManager").invoke(session);
            Object curWorld = wpMgr.getClass().getMethod("getCurrentWorld").invoke(wpMgr);
            if (curWorld == null) return;
            Object curSet = curWorld.getClass().getMethod("getCurrentSet").invoke(curWorld);
            if (curSet == null) return;

            // ── Step 3: construct a Waypoint instance ───────────────────────
            // Xaero's Waypoint constructor signature has shifted across MC
            // versions; the (int,int,int,String,String,int) form covers 1.20+.
            // y is clamped at 0 in case the discovery packet came from a
            // chunk where the spawn-Y reference was negative (custom dims).
            // The single-character "symbol" defaults to the first letter of
            // the deposit type path so different ores get visually distinct
            // markers without us needing a real icon asset.
            Class<?> wpCls = Class.forName("xaero.hud.minimap.waypoint.Waypoint");
            Object wp = wpCls.getConstructor(int.class, int.class, int.class,
                    String.class, String.class, int.class)
                    .newInstance(
                            p.pos().getX(),
                            Math.max(0, p.pos().getY()),
                            p.pos().getZ(),
                            p.name(),
                            p.typeId().getPath().substring(0, 1).toUpperCase(),
                            6 /* gold-ish color */);

            // ── Step 4: append to the active set ────────────────────────────
            curSet.getClass().getMethod("add", wpCls).invoke(curSet, wp);
            if (uk.niknik.coedeposits.ClientConfig.LOG_CLIENT_SYNC.get()) {
                Coedeposits.LOGGER.info("[xaero] added waypoint for {}", p.name());
            }
        } catch (Throwable t) {
            // Reflection target shifted (Xaero API drift) — silent failure is
            // fine, the chat message above already gave the player coordinates.
            // Debug log so it's discoverable when debugging but doesn't noise
            // the normal info log.
            Coedeposits.LOGGER.debug("[xaero] waypoint add via reflection failed: {}", t.toString());
        }
    }
}
