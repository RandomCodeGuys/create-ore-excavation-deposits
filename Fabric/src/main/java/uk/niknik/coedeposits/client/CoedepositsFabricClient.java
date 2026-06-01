package uk.niknik.coedeposits.client;

import net.fabricmc.api.ClientModInitializer;

import uk.niknik.coedeposits.CoedepositsFabric;

/**
 * Fabric 1.20.1 client entry point — <b>SKELETON</b>.
 *
 * <p>Loom registers a {@code runClient} task off this module; this initializer
 * is its client hook. When the feature port lands it will wire the client
 * config, keybinds, the Xaero map-overlay, and the YACL editor/config screen —
 * mirroring {@code uk.niknik.coedeposits.CoedepositsClient} +
 * {@code client.CoedepositsClientEvents} on the Forge line.
 */
public final class CoedepositsFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CoedepositsFabric.LOGGER.info("[coedeposits] Fabric client skeleton init");
    }
}
