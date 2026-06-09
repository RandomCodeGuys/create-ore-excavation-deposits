package uk.niknik.coedeposits.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Server → Client packet that announces a single newly-placed deposit. Drives
 * the chat notification and the best-effort Xaero waypoint creation on the
 * client. Sent in addition to {@link DepositSyncPayload} when a deposit
 * spawns naturally and {@link uk.niknik.coedeposits.Config#REVEAL_MODE} is
 * {@code ALWAYS}.
 *
 * @param name    friendly deposit name (used as %name% in chat)
 * @param pos     core position to teleport to / show on the waypoint
 * @param typeId  deposit type id (used for the localized name and color)
 * @param player  the discovering player's name for a GLOBAL-scope reveal
 *                (used as %player% in chat), or empty for ALWAYS placements
 */
public record DepositDiscoveryPayload(String name, BlockPos pos, ResourceLocation typeId, String player)
        implements CustomPacketPayload {

    /** Type identity used by NeoForge's payload registry. */
    public static final CustomPacketPayload.Type<DepositDiscoveryPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Coedeposits.MODID, "deposit_discovery"));

    /** Wire encoding: UTF-8 name + BlockPos + ResourceLocation typeId + UTF-8 player. */
    public static final StreamCodec<RegistryFriendlyByteBuf, DepositDiscoveryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, DepositDiscoveryPayload::name,
                    BlockPos.STREAM_CODEC, DepositDiscoveryPayload::pos,
                    ResourceLocation.STREAM_CODEC, DepositDiscoveryPayload::typeId,
                    ByteBufCodecs.STRING_UTF8, DepositDiscoveryPayload::player,
                    DepositDiscoveryPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
