package uk.niknik.coedeposits.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server → Client bulk update of deposit state for the world-map overlay.
 * Sent on player login (full snapshot), after natural placement (singleton),
 * and periodically every 100 server ticks (~5s) to refresh remaining counts.
 *
 * <p><b>1.20.1:</b> Forge {@code SimpleChannel} message — instance
 * {@link #encode}/static {@link #decode}/static {@link #handle} replace
 * NeoForge's {@code CustomPacketPayload} + {@code StreamCodec}. The client
 * merge is delegated to {@link uk.niknik.coedeposits.client.ClientPayloadHandler}
 * (only class-loaded on the client dist — this packet never arrives server-side).
 *
 * @param deposits  snapshots to additively merge into the client cache
 */
public record DepositSyncPayload(List<DepositSnapshot> deposits) {

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

    /** Client-side: merge incoming snapshots into the render-thread cache. */
    public static void handle(DepositSyncPayload msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> uk.niknik.coedeposits.client.ClientPayloadHandler.handleSync(msg));
        c.setPacketHandled(true);
    }
}
