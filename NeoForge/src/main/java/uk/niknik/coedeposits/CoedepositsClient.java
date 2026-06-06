package uk.niknik.coedeposits;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only mod entry. Registers {@link ClientConfig} (cosmetic UI knobs
 * like the map button position) and a config screen reachable via
 * Mods → Coedeposits → Config.
 *
 * <p>Loads only on {@link Dist#CLIENT}; dedicated servers skip this class
 * entirely, so {@code ClientConfig} is never loaded server-side.
 *
 * <p><b>Adaptive screen registration.</b> {@code IConfigScreenFactory} is a
 * single-slot extension point — claiming it blocks third-party "auto-screen"
 * mods (Configured et al.) from supplying their own screen for us. So we only
 * claim the slot when no such provider is present, falling back to NeoForge's
 * built-in {@link ConfigurationScreen}. See {@link #registerConfigScreen}.
 *
 * <p><b>Multiloader seam.</b> Only the {@code registerExtensionPoint} call
 * here is NeoForge-specific; on the planned Fabric port the equivalent hook is
 * a ModMenu entrypoint. The screen-<i>building</i> logic (native autogen now,
 * YACL next) stays loader-agnostic so the Fabric side reuses it — YACL becomes
 * the primary screen there, since Fabric has no native ConfigurationScreen.
 */
@Mod(value = Coedeposits.MODID, dist = Dist.CLIENT)
public class CoedepositsClient {
    /**
     * Mod ids of third-party providers that build a config screen from a mod's
     * {@link net.neoforged.neoforge.common.ModConfigSpec} on their own. When
     * one is loaded we deliberately don't claim the {@code IConfigScreenFactory}
     * slot, ceding it so the (usually richer) third-party screen is used
     * instead of our plain native fallback.
     */
    private static final String[] AUTO_SCREEN_PROVIDERS = { "configured" };

    /** YACL mod id — its jar artifact is {@code yet_another_config_lib_v3}. */
    private static final String YACL_MODID = "yet_another_config_lib_v3";

    /** Constructor invoked by NeoForge on client setup; registers CLIENT config + screen factory. */
    public CoedepositsClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        registerConfigScreen(container);
    }

    /**
     * Pick which config screen to register, in priority order:
     * <ol>
     *   <li>YACL present → our {@link uk.niknik.coedeposits.client.config.CoedepositsConfigScreen}
     *       (also the primary screen on the planned Fabric port).</li>
     *   <li>An {@link #AUTO_SCREEN_PROVIDERS auto-screen provider} present →
     *       register nothing and let it generate the screen from our specs.</li>
     *   <li>Otherwise → NeoForge's built-in {@link ConfigurationScreen}.</li>
     * </ol>
     */
    private static void registerConfigScreen(ModContainer container) {
        if (ModList.get().isLoaded(YACL_MODID)) {
            // Guarded so CoedepositsConfigScreen (which imports the YACL API) is only
            // ever class-loaded when YACL is actually present. First lambda
            // param (Minecraft or ModContainer depending on NeoForge version)
            // is unused, so the lambda fits the IConfigScreenFactory SAM either way.
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (ignored, parent) -> uk.niknik.coedeposits.client.config.CoedepositsConfigScreen.create(parent));
            Coedeposits.LOGGER.info("[coedeposits] YACL present — using the YACL config screen");
            return;
        }
        if (hasAutoScreenProvider()) {
            Coedeposits.LOGGER.info("[coedeposits] auto-screen config mod present — ceding the config screen to it");
            return;
        }
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    /** True if any known third-party auto-screen provider is loaded. */
    private static boolean hasAutoScreenProvider() {
        for (String modid : AUTO_SCREEN_PROVIDERS) {
            if (ModList.get().isLoaded(modid)) return true;
        }
        return false;
    }
}
