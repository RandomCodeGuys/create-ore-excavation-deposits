package uk.niknik.coedeposits.client.config.general;

import net.minecraft.network.chat.Component;

import dev.isxander.yacl3.api.OptionGroup;

import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.client.config.Bindings;

/** "Logging" group of the General tab: the per-category log toggles. All booleans, so all via {@link Bindings#toggle}. */
public final class LoggingGroup {
    private LoggingGroup() {}

    public static OptionGroup build() {
        return OptionGroup.createBuilder()
                .name(Component.literal("Logging"))
                .option(Bindings.toggle("Placement", "Log each new deposit placement.", Config.LOG_PLACEMENT))
                .option(Bindings.toggle("Discovery", "Log per-player reveals (walk-into / vein-finder).", Config.LOG_DISCOVERY))
                .option(Bindings.toggle("Depletion", "Log when a chunk's vein is fully extracted.", Config.LOG_DEPLETION))
                .option(Bindings.toggle("Replenish actions", "Log every replenish unit-restore (very noisy).", Config.LOG_REPLENISH_ACTIONS))
                .option(Bindings.toggle("Scan summary", "Log a one-line summary per prospect-scan job.", Config.LOG_SCAN_SUMMARY))
                .option(Bindings.toggle("Scan rejections", "Log per-chunk reasons a scanned chunk placed nothing (very noisy).", Config.LOG_SCAN_REJECTIONS))
                .option(Bindings.toggle("Lifecycle", "Log mod lifecycle events (picker install, types loaded, defaults written).", Config.LOG_LIFECYCLE))
                .build();
    }
}
