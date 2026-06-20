package uk.niknik.coedeposits.client;

import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import uk.niknik.coedeposits.ClientConfig;
import uk.niknik.coedeposits.mixin.GuiMapAccessor;
import uk.niknik.coedeposits.mixin.ScreenInvoker;
import uk.niknik.coedeposits.network.DepositSharePayload;

import xaero.map.gui.GuiMap;

/**
 * Xaero World Map overlay wiring — deliberately ISOLATED in its own class.
 *
 * <p>Xaero is an OPTIONAL dependency (listed under {@code fabric.mod.json}
 * {@code "suggests"}). This class is the <b>only</b> place that resolves Xaero's
 * {@code xaero.map.gui.GuiMap}, and {@link CoedepositsFabricClient} only calls
 * {@link #register()} when {@code xaeroworldmap} is actually loaded — so on a
 * no-Xaero install the JVM never loads this class, never resolves {@code GuiMap},
 * and never crashes. (The matching {@link GuiMapAccessor} mixin is gated the same
 * way by {@code CoedepositsMixinPlugin}.)
 *
 * <p>The overlay hooks Fabric {@code ScreenEvents} (runtime {@code instanceof})
 * rather than a {@code GuiMap} {@code @Inject}: Xaero's
 * {@code GuiMap → ScreenBase → Screen} chain isn't walkable by the Mixin AP, so an
 * inject couldn't be remapped to intermediary and would no-op in production.
 * {@code AFTER_INIT}/{@code afterRender} fire off vanilla {@code Screen.init}/{@code
 * render}, which GuiMap reaches via {@code super}, so they need no MC-name mapping.
 */
public final class XaeroWorldMapOverlay {
    private XaeroWorldMapOverlay() {}

    /** Register the overlay + toggle button. Call ONLY when {@code xaeroworldmap} is present. */
    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // GuiMap.class.isInstance(), not `screen instanceof GuiMap`: the latter
            // makes javac resolve GuiMap's full supertype chain (drags in Xaero's
            // separate xaero.lib ScreenBase, not on our compile classpath) and fails
            // to compile. Class literal + reflective isInstance does the identical
            // runtime check without loading the hierarchy.
            if (!GuiMap.class.isInstance(screen)) return;

            // Compact toggle widget via the Screen @Invoker (renderables + children
            // + narratables). Xaero clears widgets on every (re-)init, so re-add
            // fresh each time — no dupes.
            if (ClientConfig.MAP_BUTTON_ENABLED.get()) {
                int xOffset = ClientConfig.MAP_BUTTON_X_OFFSET.get();
                int x = switch (ClientConfig.MAP_BUTTON_ANCHOR.get()) {
                    case LEFT  -> xOffset;
                    case RIGHT -> screen.width - xOffset;
                };
                int y = ClientConfig.MAP_BUTTON_Y_OFFSET.get();
                ((ScreenInvoker) (Object) screen).coedeposits$addRenderableWidget(new DepositToggleWidget(x, y));
            }

            // Overlay, drawn after Xaero's map using Xaero's own camera + scale
            // state (read through GuiMapAccessor). Fabric resets a screen's
            // per-screen listeners on every re-init, so this doesn't stack up.
            ScreenEvents.afterRender(screen).register((scr, graphics, mouseX, mouseY, tickDelta) -> {
                GuiMapAccessor acc = (GuiMapAccessor) scr;
                WorldMapDepositRenderer.render(
                        scr, graphics, mouseX, mouseY,
                        acc.coedeposits$scale(), acc.coedeposits$cameraX(), acc.coedeposits$cameraZ(),
                        acc.coedeposits$mouseBlockPosX(), acc.coedeposits$mouseBlockPosZ());
            });

            // Share keybind on the map screen: vanilla doesn't poll KeyMappings
            // while a Screen is open, so catch the raw key here and act when a
            // deposit is hovered (returning false consumes the key). Lives in the
            // Xaero-gated overlay because it shares the deposit hovered on Xaero's map.
            ScreenKeyboardEvents.allowKeyPress(screen).register((scr, key, scancode, modifiers) -> {
                KeyMapping share = CoedepositsFabricClient.SHARE_DEPOSIT;
                if (share.isUnbound() || !share.matches(key, scancode)) return true;
                java.util.UUID id = WorldMapDepositRenderer.hoveredDepositId(scr);
                if (id == null) return true;  // nothing hovered
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                new DepositSharePayload(id).encode(buf);
                ClientPlayNetworking.send(DepositSharePayload.CHANNEL, buf);
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("[coedeposits] share offer posted to chat")
                                    .withStyle(ChatFormatting.GREEN), true /* actionbar */);
                }
                return false;  // consume — don't let the map also handle the key
            });
        });
    }
}
