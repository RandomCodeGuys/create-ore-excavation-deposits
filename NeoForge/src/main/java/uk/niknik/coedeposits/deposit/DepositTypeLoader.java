package uk.niknik.coedeposits.deposit;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.fml.loading.FMLPaths;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;

/**
 * Loads {@link DepositType} blueprints from two layered sources — datapack-first
 * with a config-file override on top:
 *
 * <ol>
 *   <li><b>Datapack layer</b> — every {@code data/<ns>/coedeposits/deposit_type/*.json}
 *       in the active server-data packs, decoded through {@link DepositType#CODEC}.
 *       The mod ships the 14 standard ores here in its own jar; other datapacks,
 *       KubeJS or CraftTweaker can add new types or override the bundled ones via
 *       higher pack priority (normal datapack semantics, resolved by the
 *       {@link ResourceManager}).</li>
 *   <li><b>Config overlay</b> — the optional, externally-editable
 *       {@code config/coedeposits/deposits.json}. Entries here are applied
 *       <i>last</i>, so a hand-edit always wins over a datapack type of the same
 *       id. This is also the only layer that supports inline {@code vein:} /
 *       {@code drilling:} synthesis (see below).</li>
 * </ol>
 *
 * <p><b>Inline recipe synthesis is config-only.</b>
 * {@link uk.niknik.coedeposits.pack.BundledRecipePack} synthesises COE vein /
 * drilling recipes by reading {@code deposits.json} straight from disk, because
 * the generated recipes must already exist when the same reload loads recipes —
 * other packs' contents can't be read back at that point. So a deposit_type
 * supplied <i>via a datapack</i> must reference an existing recipe through
 * {@code vein_recipe} / {@code vein_recipes}; only the config overlay can use the
 * inline {@code vein:} / {@code drilling:} convenience blocks.
 *
 * <p>Unlike pre-refactor builds, the config file is no longer auto-created — a
 * fresh install runs entirely on the bundled datapack defaults. Drop a
 * {@code config/coedeposits/deposits.json} only to override or extend them (the
 * bundled {@code coedeposits-default-deposits.json} resource is a ready-to-copy
 * reference that also includes the inline-recipe example entries).
 *
 * <p>Registered as a server-side reload listener by {@link Coedeposits} so
 * {@code /reload} and server start both re-read every layer. Lookup is
 * concurrent-safe enough for read-heavy access by the picker and prospect
 * scanner; writes happen only on reload (server thread).
 *
 * <p>Maintains three indexes built atomically on every {@link #apply} — see
 * {@link #rebuildIndexes}.
 *
 * <p>Entry keys starting with {@code _} are skipped in the config overlay
 * (reserved for inline JSON comments such as {@code "_comment": "..."}).
 */
public class DepositTypeLoader extends SimplePreparableReloadListener<DepositTypeLoader.Prepared> {
    /** Datapack directory scanned for type files: {@code data/<ns>/coedeposits/deposit_type/*.json}. */
    private static final FileToIdConverter DEPOSIT_TYPE_LISTER =
            FileToIdConverter.json("coedeposits/deposit_type");

    /** Sub-folder under {@code config/} where the optional override file lives. */
    private static final String CONFIG_SUBDIR = "coedeposits";

    /** Filename of the optional config overlay holding admin override types. */
    private static final String CONFIG_FILE = "deposits.json";

    /** Resource-location → parsed type. Replaced atomically by {@link #apply}. */
    private volatile Map<ResourceLocation, DepositType> types = Map.of();

    /** Cached recipe-id set used by managed types — used to suppress COE-native placement of those recipes. */
    private volatile Set<ResourceLocation> managedVeinRecipes = Set.of();

    /** Cached recipe-id → COE-mode type id, used by the picker's COE delegation branch. */
    private volatile Map<ResourceLocation, ResourceLocation> byCoeVeinRecipe = Map.of();

    /** Off-thread reload result: merged registry plus per-layer counts for logging. */
    public record Prepared(Map<ResourceLocation, DepositType> types, int datapackCount, int overlayCount) {}

    /**
     * Off-thread phase of the NeoForge reload pipeline. Builds the merged
     * registry from the datapack layer then the config overlay; {@link #apply}
     * swaps it into {@link #types} on the main thread.
     */
    @Override
    protected Prepared prepare(ResourceManager mgr, ProfilerFiller profiler) {
        Map<ResourceLocation, DepositType> merged = new HashMap<>();
        int datapackCount = loadDatapackTypes(mgr, merged);
        int overlayCount = loadConfigOverlay(merged);
        return new Prepared(merged, datapackCount, overlayCount);
    }

    /**
     * Main-thread phase — publishes the parsed snapshot and rebuilds the derived
     * indexes. All publishes are independent volatile writes; a concurrent reader
     * may observe {@code types} updated before the indexes, which is benign (the
     * indexes are advisory caches).
     */
    @Override
    protected void apply(Prepared prepared, ResourceManager mgr, ProfilerFiller profiler) {
        types = Map.copyOf(prepared.types());
        rebuildIndexes(prepared.types());
        if (Config.LOG_LIFECYCLE.get()) {
            Coedeposits.LOGGER.info(
                    "[coedeposits] loaded {} deposit types ({} datapack + {} config-overlay override(s)): {}",
                    types.size(), prepared.datapackCount(), prepared.overlayCount(), types.keySet());
        }
    }

    /**
     * Decode every {@code coedeposits/deposit_type/*.json} the resource manager
     * exposes into {@code out}. The manager already collapses pack priority, so
     * each id maps to its single winning resource (a higher-priority datapack
     * shadows the bundled jar file). Per-entry failures are logged and skipped so
     * one bad file doesn't abort the rest.
     *
     * @return number of datapack types loaded
     */
    private static int loadDatapackTypes(ResourceManager mgr, Map<ResourceLocation, DepositType> out) {
        int count = 0;
        for (Map.Entry<ResourceLocation, Resource> e : DEPOSIT_TYPE_LISTER.listMatchingResources(mgr).entrySet()) {
            ResourceLocation id = DEPOSIT_TYPE_LISTER.fileToId(e.getKey());
            try (Reader r = e.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(r);
                Optional<DepositType> parsed = DepositType.CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(err -> Coedeposits.LOGGER.error(
                                "[coedeposits] datapack deposit_type '{}' failed to parse: {}", id, err));
                if (parsed.isPresent()) {
                    out.put(id, bindInlineRecipe(id, parsed.get()));
                    count++;
                }
            } catch (Exception ex) {
                Coedeposits.LOGGER.error("[coedeposits] failed reading datapack deposit_type '{}': {}",
                        id, ex.toString());
            }
        }
        return count;
    }

    /**
     * Read the optional {@code config/coedeposits/deposits.json} and apply each
     * non-comment entry over {@code out} — overlay wins on id collision. No-op
     * when the file is absent, which is the normal fresh-install state now.
     *
     * @return number of overlay entries applied
     */
    private static int loadConfigOverlay(Map<ResourceLocation, DepositType> out) {
        Path file = configFilePath();
        if (!Files.exists(file)) return 0;

        JsonElement root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r);
        } catch (IOException e) {
            Coedeposits.LOGGER.error("[coedeposits] failed to read {}: {}", file, e.toString());
            return 0;
        } catch (Exception e) {
            Coedeposits.LOGGER.error("[coedeposits] {} is not valid JSON: {}", file, e.getMessage());
            return 0;
        }
        if (!root.isJsonObject()) {
            Coedeposits.LOGGER.error("[coedeposits] {} top-level must be a JSON object", file);
            return 0;
        }

        int count = 0;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
            String key = e.getKey();
            if (key.startsWith("_")) continue;
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id == null) {
                Coedeposits.LOGGER.error("[coedeposits] {}: invalid deposit id '{}'", file, key);
                continue;
            }
            Optional<DepositType> parsed = DepositType.CODEC.parse(JsonOps.INSTANCE, e.getValue())
                    .resultOrPartial(err -> Coedeposits.LOGGER.error(
                            "[coedeposits] {}: failed to parse '{}': {}", file, key, err));
            if (parsed.isPresent()) {
                out.put(id, parsed.get());
                count++;
            }
        }
        return count;
    }

    /**
     * Bind the inline-vein recipe and drop junk references. Inline vein takes
     * precedence: when a type has an inline {@code vein} block, its synthesized
     * recipe ({@code <id>_vein}) becomes the sole pool entry — so inline-vein
     * deposits work without an explicit reference, and a stray {@code vein_recipes}
     * id can't override the inline ore. Without inline vein, the explicit pool is
     * kept but empty-path junk ids (e.g. {@code "minecraft:"}) are removed so the
     * picker never resolves to a missing recipe (which renders as a bogus
     * "infinite" chunk).
     */
    private static DepositType bindInlineRecipe(ResourceLocation id, DepositType type) {
        if (type.vein().isPresent()) {
            ResourceLocation derived = ResourceLocation.fromNamespaceAndPath(
                    id.getNamespace(), id.getPath() + "_vein");
            List<DepositType.WeightedRecipe> recipes = List.of(new DepositType.WeightedRecipe(derived, 1));
            return recipes.equals(type.veinRecipes()) ? type : withVeinRecipes(type, recipes);
        }
        List<DepositType.WeightedRecipe> valid = new ArrayList<>();
        for (DepositType.WeightedRecipe wr : type.veinRecipes()) {
            if (!wr.recipe().getPath().isEmpty()) valid.add(wr);
        }
        if (valid.size() == type.veinRecipes().size()) return type;
        Coedeposits.LOGGER.warn("[coedeposits] {}: dropped {} invalid vein recipe id(s) (empty path)",
                id, type.veinRecipes().size() - valid.size());
        return withVeinRecipes(type, valid);
    }

    /** Copy of {@code type} with a replaced {@code veinRecipes} list. */
    private static DepositType withVeinRecipes(DepositType t, List<DepositType.WeightedRecipe> recipes) {
        return new DepositType(recipes, t.veinRecipeInfinite(), t.fillers(), t.replenishRatePerHour(),
                t.placement(), t.distance(), t.sizeChunks(), t.perChunkUnits(), t.weight(), t.mapColor(),
                t.biomeFilter(), t.reveal(), t.dimensions(), t.vein(), t.drilling());
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
