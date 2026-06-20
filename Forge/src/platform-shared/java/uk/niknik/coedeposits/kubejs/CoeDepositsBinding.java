package uk.niknik.coedeposits.kubejs;

import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.deposit.ScriptedDepositRegistry;

/**
 * The {@code CoeDeposits} KubeJS script binding (registered by
 * {@link CoedepositsKubeJSPlugin}). Lets pack authors define coedeposits deposit
 * types from a script instead of a JSON file, mirroring how the host mod exposes
 * its recipe builders to KubeJS.
 *
 * <p>Use in {@code kubejs/startup_scripts}:
 * <pre>{@code
 * CoeDeposits.add('mypack:ruby', {
 *   vein_recipes: [{ recipe: 'mypack:ruby_vein', weight: 1 }],
 *   placement: 'managed',
 *   dimensions: 'minecraft:overworld',
 *   distance: { min: 2000, max: 99999 },
 *   size_chunks: { min: 4, max: 12 },
 *   per_chunk_units: { min: 20000, max: 120000 },
 *   weight: 80,
 *   map_color: 0xE0115F,
 *   biome_filter: ['c:is_mountain']
 * })
 * }</pre>
 *
 * <p>The object is the exact {@code deposit_type} JSON schema (see the README).
 * KubeJS converts the JS object to a {@link JsonObject} via its registered type
 * wrapper. A scripted type must reference a real vein recipe — inline
 * {@code vein}/{@code drilling}/{@code fluid} synthesis only runs for the on-disk
 * config overlay (build the recipe with the host mod's own KubeJS API if you need
 * one). Re-runs overwrite by id; {@link #remove} drops one.
 *
 * <p><b>1.20.1 line:</b> this binding class is loader-agnostic (Gson + Minecraft
 * + {@link ScriptedDepositRegistry} only — no KubeJS type), so it lives in
 * {@code platform-shared}; only {@link CoedepositsKubeJSPlugin} touches the KubeJS API.
 */
public final class CoeDepositsBinding {
    private CoeDepositsBinding() {}

    /** Register (or overwrite) a deposit type from a script. {@code def} is the deposit_type JSON. */
    public static void add(String id, JsonObject def) {
        ResourceLocation rid = ResourceLocation.tryParse(id == null ? "" : id.trim());
        if (rid == null) {
            Coedeposits.LOGGER.error("[coedeposits] KubeJS CoeDeposits.add: invalid id '{}'", id);
            return;
        }
        if (def == null) {
            Coedeposits.LOGGER.error("[coedeposits] KubeJS CoeDeposits.add('{}'): null definition", id);
            return;
        }
        ScriptedDepositRegistry.put(rid, def);
    }

    /** Drop a previously-registered scripted deposit type. */
    public static void remove(String id) {
        ResourceLocation rid = ResourceLocation.tryParse(id == null ? "" : id.trim());
        if (rid != null) ScriptedDepositRegistry.remove(rid);
    }
}
