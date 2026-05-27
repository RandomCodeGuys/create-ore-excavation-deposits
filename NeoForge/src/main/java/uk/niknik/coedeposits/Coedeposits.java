package uk.niknik.coedeposits;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.server.packs.PackType;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import uk.niknik.coedeposits.command.CoedepositsCommand;
import uk.niknik.coedeposits.deposit.DepositTypeLoader;
import uk.niknik.coedeposits.gen.PickerInstaller;
import uk.niknik.coedeposits.pack.BundledRecipePackProvider;

/**
 * Mod entry point. Wires the global registries and event listeners:
 * <ul>
 *   <li>Common-side {@link Config#SPEC} (radius, density, edge-mul, prospect).</li>
 *   <li>{@link DepositTypeLoader} as a reload listener so the external
 *       {@code config/coedeposits/deposits.json} populates the registry on
 *       server start and {@code /reload}.</li>
 *   <li>{@link CoedepositsCommand} brigadier tree under {@code /coedeposits}.</li>
 *   <li>{@link PickerInstaller} for server-start hooks that swap COE's vein
 *       picker with our {@link uk.niknik.coedeposits.gen.CoedepositsPicker}.</li>
 * </ul>
 */
@Mod(Coedeposits.MODID)
public class Coedeposits {
    /** Mod id as referenced by neoforge.mods.toml and resource locations. */
    public static final String MODID = "coedeposits";

    /** SLF4J logger — shared across our codebase, always prefixed with [coedeposits]. */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Process-singleton registry of deposit types loaded from
     * {@code config/coedeposits/deposits.json}. Populated by
     * {@link DepositTypeLoader} on each {@code /reload} and at server start.
     * Read concurrently by the picker, prospect scanner and commands.
     */
    public static final DepositTypeLoader DEPOSIT_TYPES = new DepositTypeLoader();

    /**
     * Invoked by NeoForge via constructor injection. {@code modEventBus} is
     * the mod-scoped bus (registries, lifecycle); we additionally subscribe
     * to game-bus events via {@link NeoForge#EVENT_BUS}.
     */
    public Coedeposits(IEventBus modEventBus, ModContainer modContainer) {
        // ── Step 1: register the COMMON config ──────────────────────────────
        // Lives at <world>/serverconfig/coedeposits-common.toml on dedicated
        // servers, run/config/ in dev. CLIENT config is registered separately
        // in CoedepositsClient (only loaded on client distribution).
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // ── Step 2: wire game-bus event listeners ───────────────────────────
        // AddReloadListenerEvent + RegisterCommandsEvent both fire on every
        // /reload, so the deposit registry and command tree stay in sync with
        // datapack changes. Method references keep the listener identities
        // stable across reloads (NeoForge would otherwise reject duplicates).
        NeoForge.EVENT_BUS.addListener(Coedeposits::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(Coedeposits::onRegisterCommands);

        // ── Step 3: register the picker installer's @SubscribeEvent methods ─
        // Class-level register (not addListener) so both onServerStarted AND
        // onServerStopping are picked up. PickerInstaller doesn't have an
        // @EventBusSubscriber so we wire it imperatively here.
        NeoForge.EVENT_BUS.register(PickerInstaller.class);

        // ── Step 4: register virtual datapack for inline recipes ─────────────
        // AddPackFindersEvent fires on the MOD bus (not game bus) during
        // PackRepository construction. Our BundledRecipePackProvider injects
        // a pack whose resources are synthesised from deposits.json — admin
        // describes vein + drilling in one config file, no separate datapack
        // required.
        modEventBus.addListener(Coedeposits::onAddPackFinders);
    }

    /** Inject the {@link BundledRecipePackProvider} for server-data reloads. */
    private static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.SERVER_DATA) {
            event.addRepositorySource(new BundledRecipePackProvider());
        }
    }

    /** Registers the deposits.json loader so server start and {@code /reload} pick up changes. */
    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(DEPOSIT_TYPES);
    }

    /** Registers the {@code /coedeposits} brigadier command tree. */
    private static void onRegisterCommands(RegisterCommandsEvent event) {
        CoedepositsCommand.register(event.getDispatcher());
    }
}
