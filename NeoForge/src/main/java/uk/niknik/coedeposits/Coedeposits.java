package uk.niknik.coedeposits;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import uk.niknik.coedeposits.command.CoedepositsCommand;
import uk.niknik.coedeposits.deposit.DepositTypeLoader;
import uk.niknik.coedeposits.gen.PickerInstaller;

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
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.addListener(Coedeposits::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(Coedeposits::onRegisterCommands);
        NeoForge.EVENT_BUS.register(PickerInstaller.class);
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
