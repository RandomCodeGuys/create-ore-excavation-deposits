package uk.niknik.coedeposits.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Server → Client packet that announces a single newly-placed deposit. Drives
 * the chat notification and the best-effort Xaero waypoint creation on the
 * client. Sent in addition to {@link DepositSyncPayload} when a deposit spawns
 * and the reveal mode unlocks it for the player.
 *
 * <p><b>Fabric 1.20.1 line.</b> Same record components + same wire body as the
 * Forge version; the Fabric receiver (registered in {@code CoedepositsFabricClient})
 * decodes + dispatches to
 * {@link uk.niknik.coedeposits.client.ClientPayloadHandler#handleDiscovery}.
 *
 * @param name    server-generated deposit label (used in chat)
 * @param pos     core position to teleport to / show on the waypoint
 * @param typeId  deposit type id (used for the localized name and color)
 */
public record DepositDiscoveryPayload(String name, BlockPos pos, ResourceLocation typeId) {

    /** Fabric play channel id for this payload. */
    public static final ResourceLocation CHANNEL = new ResourceLocation(Coedeposits.MODID, "discovery");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeBlockPos(pos);
        buf.writeResourceLocation(typeId);
    }

    public static DepositDiscoveryPayload decode(FriendlyByteBuf buf) {
        String name = buf.readUtf();
        BlockPos pos = buf.readBlockPos();
        ResourceLocation typeId = buf.readResourceLocation();
        return new DepositDiscoveryPayload(name, pos, typeId);
    }
}
