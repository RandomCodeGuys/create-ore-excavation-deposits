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

        MutableComponent base = Component.literal("[coedeposits] discovered ")
                .withStyle(ChatFormatting.GOLD);
        MutableComponent typeText = Component.literal(p.typeId().toString())
                .withStyle(ChatFormatting.AQUA);
        MutableComponent atText = Component.literal(" at ")
                .withStyle(ChatFormatting.GRAY);
        MutableComponent coordsText = Component.literal(
                String.format("[%d, ~, %d]", p.pos().getX(), p.pos().getZ()))
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                "/tp @s " + p.pos().getX() + " ~ " + p.pos().getZ()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to suggest /tp"))));

        mc.player.sendSystemMessage(base.append(typeText).append(atText).append(coordsText));

        if (ModList.get().isLoaded(XAERO_MINIMAP)) {
            tryAddXaeroWaypoint(p);
        }
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
            // Newer Xaero (1.21+): xaero.hud.minimap.module.MinimapSession.getCurrentSession()
            Class<?> sessionCls = Class.forName("xaero.hud.minimap.module.MinimapSession");
            Object session = sessionCls.getMethod("getCurrentSession").invoke(null);
            if (session == null) {
                Coedeposits.LOGGER.debug("[xaero] no current MinimapSession (player not in world?)");
                return;
            }

            // session.getWaypointsManager().getCurrentWorld().getCurrentSet().add(waypoint)
            Object wpMgr = session.getClass().getMethod("getWaypointsManager").invoke(session);
            Object curWorld = wpMgr.getClass().getMethod("getCurrentWorld").invoke(wpMgr);
            if (curWorld == null) return;
            Object curSet = curWorld.getClass().getMethod("getCurrentSet").invoke(curWorld);
            if (curSet == null) return;

            // Construct Waypoint(x, y, z, name, symbol, color, type, disabled, rotation)
            // Older constructor varies — try (int,int,int,String,String,int).
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

            curSet.getClass().getMethod("add", wpCls).invoke(curSet, wp);
            Coedeposits.LOGGER.info("[xaero] added waypoint for {}", p.name());
        } catch (Throwable t) {
            // Reflection target shifted — silent failure is fine, chat message above already
            // gave the player coordinates. Logged at debug to avoid noise.
            Coedeposits.LOGGER.debug("[xaero] waypoint add via reflection failed: {}", t.toString());
        }
    }
}
