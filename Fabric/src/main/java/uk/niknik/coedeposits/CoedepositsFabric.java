package uk.niknik.coedeposits;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;

import net.minecraftforge.fml.config.ModConfig;

import io.github.fabricators_of_create.porting_lib.event.common.AddPackFindersEvent;

import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry;

import uk.niknik.coedeposits.command.CoedepositsCommand;
import uk.niknik.coedeposits.event.CoedepositsServerEvents;
import uk.niknik.coedeposits.network.CoedepositsNetwork;
import uk.niknik.coedeposits.pack.BundledRecipePackProvider;

/**
 * Fabric 1.20.1 entry point — the {@code ModInitializer} counterpart to the
 * Forge {@code @Mod} {@code uk.niknik.coedeposits.Coedeposits}.
 *
 * <p>Server-core wiring, mirroring the Forge constructor + Forge event
 * subscribers, but expressed through Fabric / Porting-Lib / Forge-Config-API-Port
 * registration:
 * <ul>
 *   <li><b>Common config</b> — {@link ForgeConfigRegistry} (Forge Config API
 *       Port) registers {@link Config#SPEC}; same pattern COE Fabric uses.</li>
 *   <li><b>Deposit-type reload listener</b> — {@link ResourceManagerHelper}
 *       on {@link PackType#SERVER_DATA} (Forge used {@code AddReloadListenerEvent}).</li>
 *   <li><b>Networking</b> — {@link CoedepositsNetwork#register()} (no-op on
 *       Fabric; client receivers live in {@code CoedepositsFabricClient}).</li>
 *   <li><b>Virtual recipe pack</b> — Porting-Lib's
 *       {@link AddPackFindersEvent} (the Fabric mirror of Forge's
 *       {@code AddPackFindersEvent}).</li>
 *   <li><b>Picker install + lifecycle, prospect-scan, depletion/replenish,
 *       reveal listeners</b> — registered by {@link CoedepositsServerEvents}.</li>
 *   <li><b>{@code /coedeposits} command</b> — {@link CommandRegistrationCallback}.</li>
 * </ul>
 */
public final class CoedepositsFabric implements ModInitializer {
    public static final String MODID = Coedeposits.MODID;

    @Override
    public void onInitialize() {
        // ── Common config (ForgeConfigSpec via Forge Config API Port) ───────
        ForgeConfigRegistry.INSTANCE.register(MODID, ModConfig.Type.COMMON, Config.SPEC);

        // ── Network channels — no-op on Fabric (receivers are client-side) ──
        CoedepositsNetwork.register();

        // ── Deposit-type reload listener (server-data) ──────────────────────
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
                new DepositTypeReloadListener());

        // ── Virtual recipe pack finder (inline vein/drilling/fluid synthesis) ─
        AddPackFindersEvent.EVENT.register(event -> {
            if (event.getPackType() == PackType.SERVER_DATA) {
                event.addRepositorySource(new BundledRecipePackProvider());
            }
        });

        // ── Server lifecycle + tick + chunk + reveal listeners ──────────────
        CoedepositsServerEvents.register();

        // ── /coedeposits admin command tree ─────────────────────────────────
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        CoedepositsCommand.register(dispatcher));

        Coedeposits.LOGGER.info("[coedeposits] Fabric 1.20.1 loaded");
    }

    /**
     * Fabric requires reload listeners to be {@link IdentifiableResourceReloadListener}.
     * COE's {@link uk.niknik.coedeposits.deposit.DepositTypeLoader} (in
     * platform-shared) extends {@code SimplePreparableReloadListener} and can't
     * carry a Fabric-only interface, and the picker / placer query the single
     * {@link Coedeposits#DEPOSIT_TYPES} instance — so this adapter <b>composes</b>
     * that exact instance and just forwards {@code reload(...)} to it, adding the
     * required {@code getFabricId()}. (Forge used {@code AddReloadListenerEvent}
     * to add {@code DEPOSIT_TYPES} directly; Fabric needs the id wrapper.)
     */
    private static final class DepositTypeReloadListener
            implements IdentifiableResourceReloadListener {
        private static final ResourceLocation ID = new ResourceLocation(MODID, "deposit_types");

        @Override
        public ResourceLocation getFabricId() {
            return ID;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> reload(
                net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier barrier,
                net.minecraft.server.packs.resources.ResourceManager manager,
                net.minecraft.util.profiling.ProfilerFiller prepareProfiler,
                net.minecraft.util.profiling.ProfilerFiller applyProfiler,
                java.util.concurrent.Executor prepareExecutor,
                java.util.concurrent.Executor applyExecutor) {
            return Coedeposits.DEPOSIT_TYPES.reload(
                    barrier, manager, prepareProfiler, applyProfiler, prepareExecutor, applyExecutor);
        }
    }
}
