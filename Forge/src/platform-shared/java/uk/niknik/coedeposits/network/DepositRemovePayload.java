package uk.niknik.coedeposits.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server → Client packet announcing one or more deposits that have been
 * removed (admin {@code /coedeposits delete *} or {@code regenerate}).
 * Counterpart to the additive {@link DepositSyncPayload}.
 *
 * <p>Client handler drops the listed UUIDs from
 * {@link uk.niknik.coedeposits.client.DepositClientCache} so the world-map
 * overlay stops drawing them immediately.
 *
 * @param ids  deposit UUIDs to drop from the local cache
 */
public record DepositRemovePayload(List<UUID> ids) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(ids.size());
        for (UUID id : ids) buf.writeUUID(id);
    }

    public static DepositRemovePayload decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<UUID> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readUUID());
        return new DepositRemovePayload(list);
    }

    /** Client-side: drop removed deposits from the render-thread cache. */
    public static void handle(DepositRemovePayload msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> uk.niknik.coedeposits.client.ClientPayloadHandler.handleRemoval(msg));
        c.setPacketHandled(true);
    }
}
