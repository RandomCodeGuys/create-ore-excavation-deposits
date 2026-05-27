package uk.niknik.coedeposits.event;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import com.tom.createores.OreData;
import com.tom.createores.OreDataAttachment;
import com.tom.createores.item.OreVeinFinderItem;
import com.tom.createores.recipe.VeinRecipe;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.network.CoedepositsNetwork;
import uk.niknik.coedeposits.store.DepositSavedData;

/**
 * Appends a "[coedeposits] ..." chat line after COE's Vein Finder output so
 * the player sees the deposit's remaining units, chunk count, and richness
 * without leaving chat.
 *
 * <p>We can't override COE's item, so we subscribe to both
 * {@link PlayerInteractEvent.RightClickItem} (use-in-air) and
 * {@link PlayerInteractEvent.RightClickBlock} (use-on-block) and queue our
 * extra message on the next tick so it falls below COE's lines.
 */
@EventBusSubscriber(modid = Coedeposits.MODID)
public final class VeinFinderListener {
    private VeinFinderListener() {}

    /** use-in-air path — relevant chunk is the player's current chunk. */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // COE's OreVeinFinderItem.use() is hand-aware — fire once per click only
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        handle(event.getEntity(), event.getLevel(), event.getEntity().blockPosition(), event.getItemStack());
    }

    /** use-on-block path — relevant chunk is the clicked block's chunk. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        handle(event.getEntity(), event.getLevel(), event.getPos(), event.getItemStack());
    }

    /** Shared body — filters to OreVeinFinderItem, schedules extra chat for next tick. */
    private static void handle(Player player, Level level, BlockPos pos, ItemStack stack) {
        // ── Phase 1: filter to server-side Vein Finder uses ─────────────────
        // PlayerInteractEvent fires both on the client (predicted) and the
        // server (authoritative). We only want the server-side execution to
        // avoid double messages.
        if (level.isClientSide) return;
        // Registration.VEIN_FINDER_ITEM returns a Registrate type we don't have
        // on the classpath, so type-check the Item subclass directly.
        if (!(stack.getItem() instanceof OreVeinFinderItem)) return;

        // ── Phase 2: look up the deposit owning the clicked chunk ───────────
        // No deposit means COE handled the finder normally and we have nothing
        // to add — bail without spamming chat.
        ServerLevel slvl = (ServerLevel) level;
        ChunkPos cp = new ChunkPos(pos);
        Deposit d = DepositSavedData.get(slvl).lookup(cp);
        if (d == null) return;

        // ── Phase 3: ON_PROSPECT reveal trigger ─────────────────────────────
        // For deposits gated behind prospect, this is the moment the player
        // "discovers" them. Register the per-player reveal so the marker
        // appears on their world map even if it's hidden from everyone else.
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            DepositType type = Coedeposits.DEPOSIT_TYPES.get(d.typeId());
            Config.RevealMode mode = type != null ? type.effectiveReveal() : Config.REVEAL_MODE.get();
            if (mode == Config.RevealMode.ON_PROSPECT) {
                if (CoedepositsNetwork.revealAndNotify(slvl, sp, d)) {
                    if (Config.LOG_DISCOVERY.get()) {
                        Coedeposits.LOGGER.info("[coedeposits] {} prospected {}",
                                sp.getName().getString(), d.name());
                    }
                }
            }
        }

        // ── Phase 4: compute remaining-units string ─────────────────────────
        // Sentinel values from COE: 0 = infinite vein, -1 = depleted. Format
        // with thousands separators for the common large-units case.
        LevelChunk chunk = slvl.getChunk(cp.x, cp.z);
        OreData od = OreDataAttachment.getData(chunk);
        RecipeHolder<VeinRecipe> rh = od.getRecipe(slvl.getRecipeManager());
        if (rh == null) return;

        long remaining = od.getResourcesRemaining(rh.value());
        String remStr;
        if (remaining == 0L) remStr = "infinite";
        else if (remaining == -1L) remStr = "DEPLETED";
        else remStr = String.format("%,d units", remaining);

        // ── Phase 5: schedule chat line on next tick ────────────────────────
        // server.execute runs on the next tick — runs after COE's vein-finder
        // messages so ours appears at the bottom of the player's chat dump
        // rather than getting buried above them.
        slvl.getServer().execute(() -> {
            player.sendSystemMessage(Component.literal(String.format(
                    "[coedeposits] deposit %s | this chunk remaining: %s | total chunks: %d | amountMul: %.2f",
                    d.name(), remStr, d.chunks().size(), d.amountMul())));
        });
    }
}
