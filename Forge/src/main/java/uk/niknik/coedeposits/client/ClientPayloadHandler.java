package uk.niknik.coedeposits.client;

import uk.niknik.coedeposits.compat.xaero.XaeroBridge;
import uk.niknik.coedeposits.network.DepositDiscoveryPayload;
import uk.niknik.coedeposits.network.DepositRemovePayload;
import uk.niknik.coedeposits.network.DepositSyncPayload;

/**
 * Client-dist receive handlers for the three play-to-client payloads.
 *
 * <p>Split out of the payload classes so the network layer never statically
 * references client-only types on the registration path. These methods are
 * only invoked from each payload's {@code handle()} on the client dist, inside
 * an {@code enqueueWork(...)} main-thread task — so {@link DepositClientCache}
 * / {@link XaeroBridge} are class-loaded only on the client, never on a
 * dedicated server (which never receives a play-to-client packet).
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
