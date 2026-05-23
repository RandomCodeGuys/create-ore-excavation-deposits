package uk.niknik.coedeposits;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only mod entry. Registers {@link ClientConfig} (cosmetic UI knobs
 * like the map button position) and the auto-generated NeoForge config
 * screen so all settings are reachable via Mods → Coedeposits → Config.
 *
 * <p>Loads only on {@link Dist#CLIENT}; dedicated servers skip this class
 * entirely, so {@code ClientConfig} is never loaded server-side.
 */
@Mod(value = Coedeposits.MODID, dist = Dist.CLIENT)
public class CoedepositsClient {
    /** Constructor invoked by NeoForge on client setup; registers CLIENT config + screen factory. */
    public CoedepositsClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
