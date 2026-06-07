package uk.niknik.coedeposits.kubejs;

import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;

/**
 * KubeJS integration entry point — mirrors the host mod's
 * {@code com.tom.createores.kubejs.KubeJSExcavation}. Discovered by KubeJS via
 * {@code kubejs.plugins.txt}; only ever class-loaded when KubeJS is installed,
 * so the mod runs fine without it (KubeJS is an <i>optional</i> dependency).
 *
 * <p>Scope (per the project decision): deposit <b>types</b> only. The host mod's
 * vein / drilling / extracting recipes are already scriptable through COE's own
 * KubeJS plugin, so we don't duplicate those — a script builds the recipe with
 * COE's API and references it from a {@code CoeDeposits.add(...)} deposit type.
 */
public class CoedepositsKubeJSPlugin implements KubeJSPlugin {

    @Override
    public void registerBindings(BindingRegistry bindings) {
        // Static-method binding (same pattern as COE's `coeutil`): scripts call
        // CoeDeposits.add('ns:id', { ... }) / CoeDeposits.remove('ns:id').
        bindings.add("CoeDeposits", CoeDepositsBinding.class);
    }
}
