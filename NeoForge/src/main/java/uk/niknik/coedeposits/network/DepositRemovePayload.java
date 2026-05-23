package uk.niknik.coedeposits.network;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Server → Client packet announcing one or more deposits that have been
 * removed (either by admin {@code /coedeposits delete *} or by future
 * automatic pruning). Counterpart to the additive {@link DepositSyncPayload}.
 *
 * <p>Client handler drops the listed UUIDs from {@link uk.niknik.coedeposits.client.DepositClientCache}
 * so the world-map overlay stops drawing them immediately — no need to wait
 * for the next periodic re-sync.
 *
 * @param ids  deposit UUIDs to drop from the local cache
 */
public record DepositRemovePayload(List<UUID> ids) implements CustomPacketPayload {

    /** Type identity used by NeoForge's payload registry. */
    public static final CustomPacketPayload.Type<DepositRemovePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Coedeposits.MODID, "deposit_remove"));

    /** Encodes a length-prefixed list of UUIDs. */
    public static final StreamCodec<RegistryFriendlyByteBuf, DepositRemovePayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    DepositRemovePayload::ids,
                    DepositRemovePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
