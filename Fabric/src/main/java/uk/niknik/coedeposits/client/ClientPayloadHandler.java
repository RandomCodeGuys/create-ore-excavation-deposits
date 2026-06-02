package uk.niknik.coedeposits.client;

import uk.niknik.coedeposits.compat.xaero.XaeroBridge;
import uk.niknik.coedeposits.network.DepositDiscoveryPayload;
import uk.niknik.coedeposits.network.DepositRemovePayload;
import uk.niknik.coedeposits.network.DepositSyncPayload;

/**
 * Client-dist receive handlers for the three play-to-client payloads.
 *
 * <p><b>Fabric 1.20.1 line.</b> Verbatim copy of the Forge {@code src/main}
 * version. On Fabric these are invoked from the
 * {@code ClientPlayNetworking.registerGlobalReceiver} callbacks registered in
 * {@code CoedepositsFabricClient}, on the client main thread (via
 * {@code Minecraft#execute}). {@link DepositClientCache} / {@link XaeroBridge}
 * are therefore class-loaded only on the client, never on a dedicated server.
 */
public final class ClientPayloadHandler {
    private ClientPayloadHandler() {}

    /** Merge bulk snapshot into the render-thread cache. */
    public static void handleSync(DepositSyncPayload msg) {
        DepositClientCache.applyUpdate(msg.deposits());
    }

    /** Chat notification + best-effort Xaero waypoint. */
    public static void handleDiscovery(DepositDiscoveryPayload msg) {
        XaeroBridge.onDepositDiscovered(msg);
    }

    /** Drop removed deposits from the render-thread cache. */
    public static void handleRemoval(DepositRemovePayload msg) {
        DepositClientCache.applyRemoval(msg.ids());
    }
}
