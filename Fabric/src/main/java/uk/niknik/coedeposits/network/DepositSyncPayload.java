package uk.niknik.coedeposits.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Server → Client bulk update of deposit state for the world-map overlay.
 * Sent on player login (full snapshot), after natural placement (singleton),
 * and periodically every 100 server ticks (~5s) to refresh remaining counts.
 *
 * <p><b>Fabric 1.20.1 line.</b> Same record components + same wire body as the
 * Forge {@code SimpleChannel} version, but the Forge {@code handle()} hook is
 * replaced by Fabric's channel-based networking: the payload carries a stable
 * {@link #CHANNEL} id, {@link CoedepositsNetwork} sends it with
 * {@code ServerPlayNetworking.send}, and {@code CoedepositsFabricClient}
 * registers a {@code ClientPlayNetworking} receiver that decodes + dispatches to
 * {@link uk.niknik.coedeposits.client.ClientPayloadHandler#handleSync}.
 *
 * @param deposits  snapshots to additively merge into the client cache
 */
public record DepositSyncPayload(List<DepositSnapshot> deposits) {

    /** Fabric play channel id for this payload. */
    public static final ResourceLocation CHANNEL = new ResourceLocation(Coedeposits.MODID, "sync");

    /** Length-prefixed list of {@link DepositSnapshot}s. */
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(deposits.size());
        for (DepositSnapshot s : deposits) s.encode(buf);
    }

    public static DepositSyncPayload decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<DepositSnapshot> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(DepositSnapshot.decode(buf));
        return new DepositSyncPayload(list);
    }
}
