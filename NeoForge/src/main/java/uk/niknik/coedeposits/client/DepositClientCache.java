package uk.niknik.coedeposits.client;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.network.DepositSnapshot;

/**
 * Client-side mirror of the server's DepositSavedData. Populated by
 * {@code DepositSyncPayload} on login and on each new deposit placement.
 * Reads happen on the render thread (GuiMapMixin), writes on the main thread
 * via {@code IPayloadContext.enqueueWork}, hence ConcurrentHashMap.
 */
public final class DepositClientCache {
    private DepositClientCache() {}

    private static final ConcurrentHashMap<UUID, DepositSnapshot> DEPOSITS = new ConcurrentHashMap<>();

    /** Volatile so the render thread sees toggle changes immediately without sync. */
    private static volatile boolean enabled = true;

    /** Whether the world-map overlay is currently rendering deposits. Toggled by keybind. */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Flip the enabled flag (keybind handler). Returns the new state for chat feedback. */
    public static boolean toggleEnabled() {
        enabled = !enabled;
        return enabled;
    }

    /** Merge incoming snapshots — latest wins per UUID. Called on the main thread. */
    public static void applyUpdate(List<DepositSnapshot> incoming) {
        for (DepositSnapshot s : incoming) {
            DEPOSITS.put(s.id(), s);
        }
        Coedeposits.LOGGER.debug("[coedeposits] cache: +{} → total {}",
                incoming.size(), DEPOSITS.size());
    }

    /**
     * Drop a batch of deposit UUIDs from the cache. Called from the
     * {@code DepositRemovePayload} handler when the server reports admin-
     * deleted or auto-pruned deposits.
     */
    public static void applyRemoval(java.util.Collection<java.util.UUID> ids) {
        for (java.util.UUID id : ids) {
            DEPOSITS.remove(id);
        }
        Coedeposits.LOGGER.debug("[coedeposits] cache: -{} → total {}",
                ids.size(), DEPOSITS.size());
    }

    /** Drops every cached snapshot — call on world unload to free memory. */
    public static void clear() {
        DEPOSITS.clear();
    }

    /** Snapshot collection for the world-map renderer. Safe to iterate concurrently. */
    public static Collection<DepositSnapshot> all() {
        return DEPOSITS.values();
    }
}
