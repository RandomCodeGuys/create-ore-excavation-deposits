package uk.niknik.coedeposits.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client → Server request to share a deposit with everyone via the clickable
 * chat offer. Sent by the world-map overlay when the player presses the share
 * keybind while hovering a deposit. The server validates the sender can
 * actually see the deposit before broadcasting
 * ({@link CoedepositsNetwork#broadcastShareOffer}).
 *
 * <p><b>1.20.1:</b> Forge {@code SimpleChannel} PLAY_TO_SERVER message — the only
 * client→server payload; the handler runs on {@code ctx.getSender()}.
 *
 * @param depositId  SavedData UUID of the deposit to share
 */
public record DepositSharePayload(UUID depositId) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(depositId);
    }

    public static DepositSharePayload decode(FriendlyByteBuf buf) {
        return new DepositSharePayload(buf.readUUID());
    }

    /** Server-side: validate the sender can see the deposit, then broadcast the offer. */
    public static void handle(DepositSharePayload msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerPlayer sender = c.getSender();
            if (sender != null) {
                CoedepositsNetwork.handleShareRequest(sender, msg.depositId());
            }
        });
        c.setPacketHandled(true);
    }
}
