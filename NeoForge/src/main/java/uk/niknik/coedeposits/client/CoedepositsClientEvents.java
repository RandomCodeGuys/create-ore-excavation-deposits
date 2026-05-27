package uk.niknik.coedeposits.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Client-only hookups: world-map overlay toggle keybind, and stale-cache
 * cleanup when the player disconnects (so switching worlds doesn't carry
 * over the old world's deposits).
 *
 * <p>Two subscribers in one file:
 * <ul>
 *   <li>{@link Mod} bus — {@link RegisterKeyMappingsEvent} for the keybind</li>
 *   <li>Game bus — {@link ClientTickEvent.Post} to poll the key + login/logout</li>
 * </ul>
 */
public final class CoedepositsClientEvents {
    private CoedepositsClientEvents() {}

    /**
     * Default unbound — players choose their preferred key in Options → Controls.
     * Conflict context UNIVERSAL so it works both in-game and on the Xaero map screen.
     */
    public static final KeyMapping TOGGLE_OVERLAY = new KeyMapping(
            "key.coedeposits.toggle_overlay",
            KeyConflictContext.UNIVERSAL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.coedeposits");

    /** Mod-bus subscriber for the keybind registration event. */
    @SuppressWarnings("removal")
    @EventBusSubscriber(modid = Coedeposits.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Mod {
        /** Register the toggle keybind so it appears in Options → Controls. */
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_OVERLAY);
        }
    }

    /** Game-bus subscriber for runtime events (tick polling + login/logout). */
    @EventBusSubscriber(modid = Coedeposits.MODID, value = Dist.CLIENT)
    public static final class Game {
        /** Poll the toggle key; on press flip the cache flag and chat the new state. */
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            while (TOGGLE_OVERLAY.consumeClick()) {
                boolean now = DepositClientCache.toggleEnabled();
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal(now
                                    ? "[coedeposits] map overlay ON"
                                    : "[coedeposits] map overlay OFF")
                                    .withStyle(now ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                            true /* actionbar */);
                }
            }
        }

        /**
         * Drop every cached snapshot when the player leaves a world. Otherwise
         * jumping between saves would leave the previous world's deposits
         * showing on the new world's map until the server re-syncs.
         */
        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            DepositClientCache.clear();
            if (uk.niknik.coedeposits.ClientConfig.LOG_CLIENT_SYNC.get()) {
                Coedeposits.LOGGER.info("[coedeposits] client cache cleared on disconnect");
            }
        }
    }
}
