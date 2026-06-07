package uk.niknik.coedeposits.deposit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;

/**
 * In-memory deposit types registered by KubeJS scripts through the
 * {@code CoeDeposits} binding (see {@code uk.niknik.coedeposits.kubejs}). This
 * class deliberately references <b>no</b> KubeJS type — only Gson + Minecraft —
 * so {@link DepositTypeLoader} can read it whether or not KubeJS is installed.
 * The KubeJS plugin, which owns the binding, is the only writer and is only
 * class-loaded when KubeJS is present (via {@code kubejs.plugins.txt}).
 *
 * <p>Define types in {@code kubejs/startup_scripts} so they're registered once
 * at launch — present before any world load, hence before
 * {@link DepositTypeLoader#prepare} runs. Keyed by id ({@link Map#put}), so a
 * re-run on {@code /reload} simply overwrites; removing a script's entry needs a
 * restart. In the merged registry these sit between the datapack defaults and
 * the {@code config/coedeposits/deposits.json} overlay — scripts override
 * datapack types, a hand-edited overlay still wins over scripts.
 *
 * <p>Like datapack-supplied types, a scripted type must reference real recipes
 * via {@code vein_recipes} — inline {@code vein}/{@code drilling}/{@code fluid}
 * synthesis only runs for the on-disk config overlay.
 */
public final class ScriptedDepositRegistry {
    private ScriptedDepositRegistry() {}

    /** id → raw deposit-type JSON; concurrent because scripts may register off the main thread. */
    private static final Map<ResourceLocation, JsonObject> TYPES = new ConcurrentHashMap<>();

    /** Register (or overwrite) a scripted deposit type. */
    public static void put(ResourceLocation id, JsonObject json) {
        TYPES.put(id, json);
    }

    /** Drop a scripted deposit type. */
    public static void remove(ResourceLocation id) {
        TYPES.remove(id);
    }

    /** Drop every scripted deposit type. */
    public static void clear() {
        TYPES.clear();
    }

    /** Immutable-ish snapshot for {@link DepositTypeLoader} to merge. Empty map when none registered. */
    public static Map<ResourceLocation, JsonObject> snapshot() {
        return TYPES.isEmpty() ? Map.of() : new LinkedHashMap<>(TYPES);
    }
}
