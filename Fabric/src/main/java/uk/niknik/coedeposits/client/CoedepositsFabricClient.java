package uk.niknik.coedeposits.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import uk.niknik.coedeposits.ClientConfig;
import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.network.DepositDiscoveryPayload;
import uk.niknik.coedeposits.network.DepositRemovePayload;
import uk.niknik.coedeposits.network.DepositSyncPayload;

/**
 * Fabric 1.20.1 client entry point — the {@code ClientModInitializer}
 * counterpart to the Forge {@code @Mod}-dist client init
 * ({@code uk.niknik.coedeposits.CoedepositsClient} +
 * {@code client.CoedepositsClientEvents}).
 *
 * <p>Wires (mirroring the Forge client subscribers):
 * <ul>
 *   <li>the world-map overlay toggle keybind ({@link KeyBindingHelper});</li>
 *   <li>per-tick keybind polling ({@link ClientTickEvents#END_CLIENT_TICK} —
 *       the Forge {@code TickEvent.ClientTickEvent} END counterpart);</li>
 *   <li>cache clear on disconnect ({@link ClientPlayConnectionEvents#DISCONNECT}
 *       — Forge {@code ClientPlayerNetworkEvent.LoggingOut});</li>
 *   <li>the three play-to-client packet receivers ({@link ClientPlayNetworking}
 *       — Forge's {@code SimpleChannel} client handlers), dispatching to
 *       {@link ClientPayloadHandler} on the client main thread.</li>
 * </ul>
 *
 * <p>The CLIENT config spec ({@link ClientConfig}) is registered here, and the
 * YACL config screen is exposed via ModMenu ({@code CoedepositsModMenu}) rather
 * than Forge's {@code ConfigScreenHandler} extension point.
 */
public final class CoedepositsFabricClient implements ClientModInitializer {

    /**
     * Default unbound — players choose their preferred key in Options → Controls.
     * Registered with Fabric's {@link KeyBindingHelper} so it appears there.
     */
    public static final KeyMapping TOGGLE_OVERLAY = new KeyMapping(
            "key.coedeposits.toggle_overlay",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.coedeposits");

    @Override
    public void onInitializeClient() {
        // ── CLIENT config spec (ForgeConfigSpec via Forge Config API Port) ──
        fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry.INSTANCE.register(
                Coedeposits.MODID,
                net.minecraftforge.fml.config.ModConfig.Type.CLIENT,
                ClientConfig.SPEC);

        // ── Toggle keybind ──────────────────────────────────────────────────
        KeyBindingHelper.registerKeyBinding(TOGGLE_OVERLAY);

        // ── Per-tick keybind poll (flip overlay + actionbar feedback) ───────
        ClientTickEvents.END_CLIENT_TICK.register(CoedepositsFabricClient::onClientTick);

        // ── Drop cached snapshots when leaving a world ──────────────────────
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DepositClientCache.clear();
            if (ClientConfig.LOG_CLIENT_SYNC.get()) {
                Coedeposits.LOGGER.info("[coedeposits] client cache cleared on disconnect");
            }
        });

        // ── Play-to-client packet receivers ─────────────────────────────────
        // Decode off-thread into immutable payloads, then dispatch the cache
        // mutation onto the client main thread (mirrors COE Fabric's pattern).
        ClientPlayNetworking.registerGlobalReceiver(DepositSyncPayload.CHANNEL,
                (client, handler, buf, sender) -> {
                    DepositSyncPayload msg = DepositSyncPayload.decode(buf);
                    client.execute(() -> ClientPayloadHandler.handleSync(msg));
                });
        ClientPlayNetworking.registerGlobalReceiver(DepositDiscoveryPayload.CHANNEL,
                (client, handler, buf, sender) -> {
                    DepositDiscoveryPayload msg = DepositDiscoveryPayload.decode(buf);
                    client.execute(() -> ClientPayloadHandler.handleDiscovery(msg));
                });
        ClientPlayNetworking.registerGlobalReceiver(DepositRemovePayload.CHANNEL,
                (client, handler, buf, sender) -> {
                    DepositRemovePayload msg = DepositRemovePayload.decode(buf);
                    client.execute(() -> ClientPayloadHandler.handleRemoval(msg));
                });

        Coedeposits.LOGGER.info("[coedeposits] Fabric client init done");
    }

    /** Poll the toggle key; on press flip the cache flag and chat the new state. */
    private static void onClientTick(Minecraft mc) {
        while (TOGGLE_OVERLAY.consumeClick()) {
            boolean now = DepositClientCache.toggleEnabled();
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
}
