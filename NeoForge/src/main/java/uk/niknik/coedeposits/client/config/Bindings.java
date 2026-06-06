package uk.niknik.coedeposits.client.config;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;

/**
 * Shared config-binding helpers for the YACL screen groups.
 *
 * <p>{@link #persist} is the write-through setter every option binds to: it writes
 * the value to the ModConfigSpec AND flushes to disk immediately, so persistence
 * does not depend on YACL's separate save callback running after apply. {@link
 * #toggle} is the ONE factory we keep — a boolean is a trivial 3-arg declaration, so
 * a helper is justified there; every option with ranges/scales/enums is written out
 * explicitly in its group file.
 */
public final class Bindings {
    private Bindings() {}

    /** Write a value to its config entry and flush to disk; diagnostic log when lifecycle logging is on. */
    public static <T> void persist(ModConfigSpec.ConfigValue<T> v, T val, String name) {
        if (Config.LOG_LIFECYCLE.get()) {
            Coedeposits.LOGGER.info("[coedeposits] config '{}' applied -> {}", name, val);
        }
        v.set(val);
        v.save();
    }

    /** Simple on/off option — the only case simple enough to keep behind a factory. */
    public static Option<Boolean> toggle(String name, String desc, ModConfigSpec.BooleanValue v) {
        return Option.<Boolean>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(v.getDefault(), v::get, val -> persist(v, val, name))
                .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                .build();
    }
}
