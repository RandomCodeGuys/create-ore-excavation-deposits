package uk.niknik.coedeposits.network;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Client → Server request to share a deposit with everyone via the clickable
 * chat offer. Sent by the world-map overlay when the player presses the share
 * keybind while hovering a deposit. The server validates the sender can
 * actually see the deposit before broadcasting
 * ({@link CoedepositsNetwork#broadcastShareOffer}).
 *
 * @param depositId  SavedData UUID of the deposit to share
 */
public record DepositSharePayload(UUID depositId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DepositSharePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Coedeposits.MODID, "deposit_share"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DepositSharePayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, DepositSharePayload::depositId,
                    DepositSharePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
