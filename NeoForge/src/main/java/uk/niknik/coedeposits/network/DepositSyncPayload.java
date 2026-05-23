package uk.niknik.coedeposits.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Server → Client bulk update of deposit state for the world-map overlay.
 * Sent on player login (full snapshot), after natural placement (singleton),
 * and periodically every 100 server ticks (~5s) to refresh remaining counts.
 *
 * @param deposits  snapshots to additively merge into the client cache
 */
public record DepositSyncPayload(List<DepositSnapshot> deposits) implements CustomPacketPayload {

    /** Type identity used by NeoForge's payload registry. */
    public static final CustomPacketPayload.Type<DepositSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Coedeposits.MODID, "deposit_sync"));

    /** Encodes the list of snapshots via {@link DepositSnapshot#STREAM_CODEC}. */
    public static final StreamCodec<RegistryFriendlyByteBuf, DepositSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    DepositSnapshot.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    DepositSyncPayload::deposits,
                    DepositSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
