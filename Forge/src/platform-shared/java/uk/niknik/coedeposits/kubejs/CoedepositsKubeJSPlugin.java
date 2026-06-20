package uk.niknik.coedeposits.kubejs;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;

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
 *
 * <p><b>1.20.1 KubeJS API (2001.x):</b> {@code KubeJSPlugin} is a <b>class</b> in
 * {@code dev.latvian.mods.kubejs} (the 1.21.1 line moved it to {@code .plugin} and
 * switched to a {@code BindingRegistry}); bindings register via
 * {@link #registerBindings(BindingsEvent)} → {@code event.add(name, value)}.
 * Lives in {@code platform-shared} — the KubeJS API is the common (multiloader)
 * module, identical for the Forge + Fabric builds.
 */
public class CoedepositsKubeJSPlugin extends KubeJSPlugin {

    @Override
    public void registerBindings(BindingsEvent event) {
        // Static-method binding (same pattern as COE's `coeutil`): scripts call
        // CoeDeposits.add('ns:id', { ... }) / CoeDeposits.remove('ns:id').
        event.add("CoeDeposits", CoeDepositsBinding.class);
    }
}
