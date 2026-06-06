package uk.niknik.coedeposits.client.config;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.YetAnotherConfigLib;

import uk.niknik.coedeposits.ClientConfig;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.client.config.deposit.DepositBindings;
import uk.niknik.coedeposits.client.config.deposit.DepositListScreen;
import uk.niknik.coedeposits.client.config.general.ClientGroup;
import uk.niknik.coedeposits.client.config.general.GenerationGroup;
import uk.niknik.coedeposits.client.config.general.LoggingGroup;
import uk.niknik.coedeposits.client.config.general.RevealGroup;

/**
 * YACL config-screen assembly. Two tabs:
 * <ul>
 *   <li><b>General</b> — Generation / Reveal / Logging / Client groups (each built in
 *       its own file under {@code config.general}).</li>
 *   <li><b>Deposits</b> — the deposit-type editor inline, one row per deposit; built by
 *       {@link DepositListScreen#depositsCategory}.</li>
 * </ul>
 *
 * <p>This class only wires the tabs together; it holds no option logic. Built only when
 * YACL is installed (see {@code CoedepositsClient#registerConfigScreen}); it is a nicer
 * front-end over the toml-backed {@link Config}/{@link ClientConfig} specs plus the
 * {@code deposits.json} overlay, and keeps no storage of its own.
 *
 * <p>The Deposits tab mutates a list (add / disable / delete), which YACL can't reflect
 * in place — so those actions rebuild the whole screen via {@link #rebuild}, and
 * {@code screenInit} re-selects the Deposits tab so the rebuild doesn't bounce the user
 * back to General.
 *
 * <p><b>Loader note.</b> Nothing here is loader-specific except the {@link ForgeConfigSpec}
 * types bound in the groups; the same specs exist on Fabric via Forge Config API Port and
 * on NeoForge as {@code ModConfigSpec}, so this stays a thin front-end over the shared
 * config plus the {@code deposits.json} overlay.
 */
public final class CoedepositsConfigScreen {
    private CoedepositsConfigScreen() {}

    /** Index of the Deposits tab among the categories (General is 0). */
    private static final int DEPOSITS_TAB = 1;

    /** Build the YACL screen with {@code parent} as the back-button target. */
    public static Screen create(Screen parent) {
        DepositBindings.reload();   // fresh drafts + autocomplete pools for this session
        return rebuild(parent, false);
    }

    /**
     * (Re)build the screen. {@code openOnDeposits} selects the Deposits tab once the screen
     * has initialised — used when a deposit-list mutation rebuilds it, so the user is not
     * thrown back to General.
     */
    private static Screen rebuild(Screen parent, boolean openOnDeposits) {
        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Coedeposits"))
                .category(general())
                .category(DepositListScreen.depositsCategory(parent, () -> rebuild(parent, true)))
                .save(() -> {
                    Config.SPEC.save();
                    ClientConfig.SPEC.save();
                    DepositBindings.persist();
                })
                .screenInit(screen -> {
                    if (openOnDeposits) {
                        screen.tabNavigationBar.selectTab(DEPOSITS_TAB, false);
                    }
                })
                .build()
                .generateScreen(parent);
    }

    private static ConfigCategory general() {
        return ConfigCategory.createBuilder()
                .name(Component.literal("General"))
                .group(GenerationGroup.build())
                .group(RevealGroup.build())
                .group(LoggingGroup.build())
                .group(ClientGroup.build())
                .build();
    }
}
