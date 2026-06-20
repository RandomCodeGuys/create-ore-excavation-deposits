package uk.niknik.coedeposits.network;

import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Client → Server request to share a deposit with everyone via the clickable
 * chat offer. Sent by the Xaero world-map overlay's share keybind. The server
 * validates the sender can actually see the deposit before broadcasting
 * ({@link CoedepositsNetwork#broadcastShareOffer}).
 *
 * <p><b>Fabric 1.20.1:</b> the only client→server payload; the server-side
 * receiver is registered in {@link CoedepositsNetwork#register()} via
 * {@code ServerPlayNetworking}.
 *
 * @param depositId  SavedData UUID of the deposit to share
 */
public record DepositSharePayload(UUID depositId) {

    /** Fabric play channel id for this payload. */
    public static final ResourceLocation CHANNEL = new ResourceLocation(Coedeposits.MODID, "share");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(depositId);
    }

    public static DepositSharePayload decode(FriendlyByteBuf buf) {
        return new DepositSharePayload(buf.readUUID());
    }
}
