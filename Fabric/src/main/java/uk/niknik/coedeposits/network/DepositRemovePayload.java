package uk.niknik.coedeposits.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Server → Client packet announcing one or more deposits that have been
 * removed (admin {@code /coedeposits delete *} or {@code regenerate}).
 * Counterpart to the additive {@link DepositSyncPayload}.
 *
 * <p><b>Fabric 1.20.1 line.</b> Same record components + same wire body as the
 * Forge version; the Fabric receiver (registered in {@code CoedepositsFabricClient})
 * decodes + dispatches to
 * {@link uk.niknik.coedeposits.client.ClientPayloadHandler#handleRemoval}.
 *
 * @param ids  deposit UUIDs to drop from the local cache
 */
public record DepositRemovePayload(List<UUID> ids) {

    /** Fabric play channel id for this payload. */
    public static final ResourceLocation CHANNEL = new ResourceLocation(Coedeposits.MODID, "remove");

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
}
