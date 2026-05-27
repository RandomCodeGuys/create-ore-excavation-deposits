package uk.niknik.coedeposits.deposit;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.fml.loading.FMLPaths;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Loads {@link DepositType} blueprints from a single externally-editable
 * config file: {@code config/coedeposits/deposits.json}.
 *
 * <p>On first run (or after the file is deleted) the bundled defaults
 * resource {@code coedeposits-default-deposits.json} is copied verbatim
 * from the jar to that location so server admins always have a known-good
 * starting point. The on-disk file is the only source of truth thereafter
 * — datapacks no longer contribute deposit types.
 *
 * <p>Registered as a server-side reload listener by {@link Coedeposits}
 * so {@code /reload} and server start both re-read the file. Lookup is
 * concurrent-safe enough for read-heavy access by the picker and prospect
 * scanner; writes happen only on reload (server thread).
 *
 * <p>Maintains three indexes built atomically on every apply():
 * <ul>
 *   <li>{@code types} — primary id → type lookup</li>
 *   <li>{@code managedVeinRecipes} — recipe ids consumed by managed types,
 *       used by the picker to discard COE's natural placement of recipes
 *       we manage ourselves (so iron only spawns via our blob algorithm)</li>
 *   <li>{@code byCoeVeinRecipe} — recipe id → COE-mode type id, used by
 *       the picker's COE delegation branch to find the matching tracked
 *       type when COE returns a recipe</li>
 * </ul>
 *
 * <p>File format: top-level JSON object whose keys are deposit ids
 * (namespace:path) and whose values are {@link DepositType} bodies.
 * Keys starting with {@code _} are skipped (reserved for inline comments
 * such as {@code "_comment": "..."}).
 */
public class DepositTypeLoader extends SimplePreparableReloadListener<Map<ResourceLocation, DepositType>> {
    /** Bundled defaults resource at jar root — copied to disk on first run. */
    private static final String DEFAULTS_RESOURCE = "coedeposits-default-deposits.json";

    /** Sub-folder under {@code config/} where the single deposits file lives. */
    private static final String CONFIG_SUBDIR = "coedeposits";

    /** Filename of the editable config holding all deposit types. */
    private static final String CONFIG_FILE = "deposits.json";

    /** Resource-location → parsed type. Replaced atomically by {@link #apply}. */
    private volatile Map<ResourceLocation, DepositType> types = Map.of();

    /** Cached recipe-id set used by managed types — used to suppress COE-native placement of those recipes. */
    private volatile Set<ResourceLocation> managedVeinRecipes = Set.of();

    /** Cached recipe-id → COE-mode type id, used by the picker's COE delegation branch. */
    private volatile Map<ResourceLocation, ResourceLocation> byCoeVeinRecipe = Map.of();

    /**
     * Off-thread phase of the NeoForge reload pipeline. Ensures the config
     * file exists (copies defaults if not), then parses it. Returns a map
     * that {@link #apply} swaps into {@link #types} on the main thread.
     */
    @Override
    protected Map<ResourceLocation, DepositType> prepare(ResourceManager mgr, ProfilerFiller profiler) {
        Path file = configFilePath();
        ensureExists(file);
        return parse(file);
    }

    /**
     * Main-thread phase — publishes the parsed snapshot and rebuilds the
     * derived indexes. All three publishes are independent volatile writes;
     * a concurrent reader may observe an intermediate state where {@code types}
     * is updated but the indexes are not, which is benign (the indexes are
     * advisory caches).
     */
    @Override
    protected void apply(Map<ResourceLocation, DepositType> parsed, ResourceManager mgr, ProfilerFiller profiler) {
        types = Map.copyOf(parsed);
        rebuildIndexes(parsed);
        if (uk.niknik.coedeposits.Config.LOG_LIFECYCLE.get()) {
            Coedeposits.LOGGER.info("[coedeposits] loaded {} deposit types from {}: {}",
                    types.size(), CONFIG_SUBDIR + "/" + CONFIG_FILE, types.keySet());
        }
    }

    /** Recompute managed/COE indexes from the live registry. Called from apply(). */
    private void rebuildIndexes(Map<ResourceLocation, DepositType> registry) {
        Set<ResourceLocation> managed = new HashSet<>();
        Map<ResourceLocation, ResourceLocation> coe = new HashMap<>();
        for (var e : registry.entrySet()) {
            DepositType t = e.getValue();
            // Multi-recipe-aware: each type contributes EVERY recipe in its
            // weighted pool to the indexes. The picker's COE-suppression
            // (Phase 3 in CoedepositsPicker) needs to know about all recipes a
            // managed type might assign so COE can't natural-place any of them.
            if (t.placement() == DepositType.Placement.MANAGED) {
                for (DepositType.WeightedRecipe wr : t.veinRecipes()) {
                    managed.add(wr.recipe());
                }
            } else if (t.placement() == DepositType.Placement.COE) {
                // COE-mode types map every recipe back to the type id — when
                // COE later spawns one of those recipes naturally, the picker
                // looks up which tracked type owns it.
                for (DepositType.WeightedRecipe wr : t.veinRecipes()) {
                    coe.put(wr.recipe(), e.getKey());
                }
            }
        }
        managedVeinRecipes = Set.copyOf(managed);
        byCoeVeinRecipe = Map.copyOf(coe);
    }

    /** Absolute path of {@code config/coedeposits/deposits.json}. */
    private static Path configFilePath() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_SUBDIR).resolve(CONFIG_FILE);
    }

    /**
     * Copy the bundled {@code coedeposits-default-deposits.json} to the
     * given path if it doesn't already exist. Creates parent directories
     * as needed. Silently no-ops when the file is already there so admin
     * edits survive across server restarts.
     */
    private static void ensureExists(Path file) {
        if (Files.exists(file)) return;
        try {
            Files.createDirectories(file.getParent());
            try (InputStream is = DepositTypeLoader.class.getClassLoader().getResourceAsStream(DEFAULTS_RESOURCE)) {
                if (is == null) {
                    Coedeposits.LOGGER.error(
                            "[coedeposits] bundled defaults resource {} missing — generation will be empty until {} is created manually",
                            DEFAULTS_RESOURCE, file);
                    return;
                }
                Files.copy(is, file);
                if (uk.niknik.coedeposits.Config.LOG_LIFECYCLE.get()) {
                    Coedeposits.LOGGER.info("[coedeposits] wrote default deposit config to {}", file);
                }
            }
        } catch (IOException e) {
            Coedeposits.LOGGER.error("[coedeposits] failed to create default {} at {}: {}",
                    CONFIG_FILE, file, e.toString());
        }
    }

    /**
     * Read the on-disk config and decode each non-comment entry through
     * {@link DepositType#CODEC}. Per-entry failures are logged but don't
     * abort the rest of the load — a typo in one ore should leave the
     * others working.
     */
    private static Map<ResourceLocation, DepositType> parse(Path file) {
        Map<ResourceLocation, DepositType> out = new HashMap<>();

        // ── Phase 1: existence check ────────────────────────────────────────
        // Caller (prepare) already calls ensureExists before this, so missing
        // here means ensureExists itself failed. Treat as empty registry —
        // mod still loads but no deposits will spawn.
        if (!Files.exists(file)) {
            Coedeposits.LOGGER.warn("[coedeposits] {} not found — no deposit types loaded", file);
            return out;
        }

        // ── Phase 2: read + JSON-parse the file ─────────────────────────────
        // IOException → file got removed between exists() and read; Exception →
        // syntactically invalid JSON (admin saved a malformed file). Both
        // produce an empty map so the previous registry remains intact.
        JsonElement root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r);
        } catch (IOException e) {
            Coedeposits.LOGGER.error("[coedeposits] failed to read {}: {}", file, e.toString());
            return out;
        } catch (Exception e) {
            Coedeposits.LOGGER.error("[coedeposits] {} is not valid JSON: {}", file, e.getMessage());
            return out;
        }
        if (!root.isJsonObject()) {
            Coedeposits.LOGGER.error("[coedeposits] {} top-level must be a JSON object", file);
            return out;
        }

        // ── Phase 3: decode each entry via the DepositType codec ────────────
        // Per-entry try-or-skip: one bad entry doesn't kill the whole file.
        // Keys starting with `_` are reserved for inline JSON comments (since
        // standard JSON doesn't allow comments) — e.g. "_comment": "...".
        JsonObject obj = root.getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("_")) continue;
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id == null) {
                Coedeposits.LOGGER.error("[coedeposits] {}: invalid deposit id '{}'", file, key);
                continue;
            }
            // resultOrPartial logs the per-field DataResult error; ifPresent
            // skips broken entries so a typo in one ore doesn't prevent the
            // rest from loading.
            DepositType.CODEC.parse(JsonOps.INSTANCE, e.getValue())
                    .resultOrPartial(err -> Coedeposits.LOGGER.error(
                            "[coedeposits] {}: failed to parse '{}': {}", file, key, err))
                    .ifPresent(t -> out.put(id, t));
        }
        return out;
    }

    /** Direct map view — used by the picker for weighted iteration. Don't mutate. */
    public Map<ResourceLocation, DepositType> all() {
        return types;
    }

    /** Lookup a single type by id. {@code null} if not loaded (parse failure or missing entry). */
    public DepositType get(ResourceLocation id) {
        return types.get(id);
    }

    /**
     * Set of vein_recipe ids consumed by {@link DepositType.Placement#MANAGED}
     * types. The picker checks against this set in the COE-delegation branch
     * to discard COE's natural placement of recipes we own — managed types
     * must spawn only through our blob algorithm.
     */
    public Set<ResourceLocation> managedVeinRecipes() {
        return managedVeinRecipes;
    }

    /**
     * Look up the {@link DepositType.Placement#COE} type id whose
     * {@code vein_recipe} matches the given id, or {@code null} if none.
     * Used by the picker's COE-delegation branch to find the tracked type
     * for the recipe COE chose.
     */
    public ResourceLocation coeTypeIdForVeinRecipe(ResourceLocation veinRecipe) {
        return byCoeVeinRecipe.get(veinRecipe);
    }
}
