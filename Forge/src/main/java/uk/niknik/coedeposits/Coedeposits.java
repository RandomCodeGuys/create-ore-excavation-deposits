package uk.niknik.coedeposits;

import com.mojang.logging.LogUtils;

import net.minecraft.server.packs.PackType;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import org.slf4j.Logger;

import uk.niknik.coedeposits.command.CoedepositsCommand;
import uk.niknik.coedeposits.deposit.DepositTypeLoader;
import uk.niknik.coedeposits.network.CoedepositsNetwork;
import uk.niknik.coedeposits.gen.PickerInstaller;
import uk.niknik.coedeposits.pack.BundledRecipePackProvider;

/**
 * Forge 1.20.1 entry point.
 *
 * <p>Server-core wiring: common config, the deposit-type reload listener, the
 * picker-install lifecycle hooks, the virtual recipe pack (inline
 * {@code vein:}/{@code drilling:}/{@code fluid:} synthesis), the async
 * prospect-scan sweep, depletion/replenish, and the {@code /coedeposits} admin
 * command tree. Still deferred: the reveal/sync event listeners (need real
 * networking) and the whole client side (map overlay + YACL editor).
 */
@Mod(Coedeposits.MODID)
public final class Coedeposits {
    public static final String MODID = "coedeposits";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Live deposit-type registry — the reload-listener instance queried by the picker / placer. */
    public static final DepositTypeLoader DEPOSIT_TYPES = new DepositTypeLoader();

    public Coedeposits() {
        // ── Common config (ForgeConfigSpec) ─────────────────────────────────
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // ── Network channel (SimpleChannel) — 3 play-to-client messages ─────
        CoedepositsNetwork.register();

        // ── Client-only init (CLIENT config spec) — client dist only ────────
        // DistExecutor keeps CoedepositsClient (→ ClientConfig) off a dedicated
        // server's class-load path; the supplier is only invoked on the client.
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> CoedepositsClient::init);

        // ── Mod event bus: virtual recipe pack finder ───────────────────────
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onAddPackFinders);

        // ── Game event bus ──────────────────────────────────────────────────
        MinecraftForge.EVENT_BUS.register(this);                   // AddReloadListenerEvent
        MinecraftForge.EVENT_BUS.register(PickerInstaller.class);  // ServerStarted / OnDatapackSync

        LOGGER.info("[coedeposits] Forge 1.20.1 loaded");
    }

    /** Inject the inline-recipe virtual datapack into the server-data repository. */
    private void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.SERVER_DATA) {
            event.addRepositorySource(new BundledRecipePackProvider());
        }
    }

    /** Register the deposit-type loader as a server-data reload listener (server start + /reload). */
    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(DEPOSIT_TYPES);
    }

    /** Register the {@code /coedeposits} admin command tree (fires on every server start + /reload). */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CoedepositsCommand.register(event.getDispatcher());
    }
}
