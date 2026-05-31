package uk.niknik.coedeposits.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Client-only hookups: world-map overlay toggle keybind, and stale-cache
 * cleanup when the player disconnects (so switching worlds doesn't carry over
 * the old world's deposits).
 *
 * <p><b>1.20.1 line:</b> Forge {@code @Mod.EventBusSubscriber}; the mod-bus
 * nested subscriber is named {@code KeyRegistration} (not {@code Mod}) to avoid
 * clashing with the {@code @Mod} annotation reference. Tick polling uses
 * {@code TickEvent.ClientTickEvent} (phase END) instead of NeoForge's
 * {@code ClientTickEvent.Post}; logout via {@code ClientPlayerNetworkEvent.LoggingOut}.
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
    @Mod.EventBusSubscriber(modid = Coedeposits.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class KeyRegistration {
        /** Register the toggle keybind so it appears in Options → Controls. */
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_OVERLAY);
        }
    }

    /** Game-bus subscriber for runtime events (tick polling + login/logout). */
    @Mod.EventBusSubscriber(modid = Coedeposits.MODID, value = Dist.CLIENT)
    public static final class Game {
        /** Poll the toggle key; on press flip the cache flag and chat the new state. */
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
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
         * Drop every cached snapshot when the player leaves a world, so jumping
         * between saves doesn't leave the previous world's deposits showing.
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
