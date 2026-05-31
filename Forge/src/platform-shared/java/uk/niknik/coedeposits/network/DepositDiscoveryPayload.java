package uk.niknik.coedeposits.network;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server → Client packet that announces a single newly-placed deposit. Drives
 * the chat notification and the best-effort Xaero waypoint creation on the
 * client. Sent in addition to {@link DepositSyncPayload} when a deposit spawns
 * and the reveal mode unlocks it for the player.
 *
 * <p><b>1.20.1:</b> Forge {@code SimpleChannel} message; the client handling
 * is delegated to {@link uk.niknik.coedeposits.client.ClientPayloadHandler}.
 *
 * @param name    server-generated deposit label (used in chat)
 * @param pos     core position to teleport to / show on the waypoint
 * @param typeId  deposit type id (used for the localized name and color)
 */
public record DepositDiscoveryPayload(String name, BlockPos pos, ResourceLocation typeId) {

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

    /** Client-side: chat notification + best-effort Xaero waypoint. */
    public static void handle(DepositDiscoveryPayload msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> uk.niknik.coedeposits.client.ClientPayloadHandler.handleDiscovery(msg));
        c.setPacketHandled(true);
    }
}
