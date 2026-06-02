package uk.niknik.coedeposits;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import uk.niknik.coedeposits.deposit.DepositTypeLoader;

/**
 * Fabric 1.20.1 static holder — the loader-agnostic core in
 * {@code platform-shared} references {@code Coedeposits.MODID},
 * {@code Coedeposits.LOGGER} and {@code Coedeposits.DEPOSIT_TYPES}, so the Fabric
 * module supplies its own {@code uk.niknik.coedeposits.Coedeposits} with the same
 * three statics (the Forge module's copy is the {@code @Mod} entry; this one is a
 * plain holder). The actual mod-init wiring lives in {@link CoedepositsFabric}
 * (the {@code ModInitializer}).
 */
public final class Coedeposits {
    private Coedeposits() {}

    public static final String MODID = "coedeposits";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Live deposit-type registry — the reload-listener instance queried by the picker / placer. */
    public static final DepositTypeLoader DEPOSIT_TYPES = new DepositTypeLoader();
}
