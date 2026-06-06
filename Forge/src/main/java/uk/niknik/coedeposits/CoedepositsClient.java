package uk.niknik.coedeposits;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Client-only init. Registers {@link ClientConfig} (cosmetic UI knobs) and the
 * Mods-menu config screen. Invoked from the main {@link Coedeposits} constructor
 * via {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)} so the class — and
 * the YACL-importing {@code CoedepositsConfigScreen} it may reference — is only
 * ever loaded on the client dist.
 *
 * <p><b>1.20.1 line:</b> Forge 1.20.1 has no {@code @Mod(dist=CLIENT)} entry or
 * {@code ModContainer} ctor like NeoForge — client init is a plain static
 * method dispatched via {@code DistExecutor}. The config screen is wired through
 * Forge's {@code ConfigScreenHandler.ConfigScreenFactory} extension point
 * (NeoForge uses {@code IConfigScreenFactory}); there is no built-in
 * {@code ConfigurationScreen} fallback (that's a NeoForge 1.20.5+ feature), so
 * without YACL the config is editable via the generated {@code .toml} only.
 */
public final class CoedepositsClient {
    private CoedepositsClient() {}

    /** YACL mod id — its jar artifact is {@code yet_another_config_lib_v3}. */
    private static final String YACL_MODID = "yet_another_config_lib_v3";

    /** Client init — register the CLIENT config spec + the config screen. Client dist only. */
    public static void init() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        registerConfigScreen();
        Coedeposits.LOGGER.info("[coedeposits] client init done");
    }

    /**
     * Register the Mods-menu config screen. When YACL is present we use the rich
     * {@link uk.niknik.coedeposits.client.config.CoedepositsConfigScreen} (two tabs:
     * General settings + the inline deposit editor). The reference is kept inside the
     * YACL-loaded branch so {@code CoedepositsConfigScreen} (which imports the YACL API)
     * is never class-loaded when YACL is absent — Forge 1.20.1 has no native fallback,
     * so that case falls back to {@code .toml}-only editing.
     */
    private static void registerConfigScreen() {
        if (!ModList.get().isLoaded(YACL_MODID)) {
            Coedeposits.LOGGER.info("[coedeposits] YACL absent — config editable via the .toml only");
            return;
        }
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> uk.niknik.coedeposits.client.config.CoedepositsConfigScreen.create(parent)));
        Coedeposits.LOGGER.info("[coedeposits] YACL present — config screen + deposit editor registered");
    }
}
