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
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.platform.CoedepositsPlatform;

/**
 * Loads {@link DepositType} blueprints from the datapack layer
 * ({@code data/<ns>/deposit_type/*.json}) plus the optional config overlay
 * ({@code config/coedeposits/deposits.json}, applied last).
 *
 * <p><b>1.20.1 line:</b> identical to the 1.21.1 source except {@code FMLPaths}
 * comes from {@code net.minecraftforge.fml.loading} and {@code ResourceLocation}
 * uses its 1.20.1 constructor. {@code FileToIdConverter} /
 * {@code SimplePreparableReloadListener} / {@code ResourceManager} are unchanged.
 *
 * <p>Inline {@code vein:}/{@code drilling:}/{@code fluid:} synthesis is config-only
 * (see {@link uk.niknik.coedeposits.pack.BundledRecipePack}).
 */
public class DepositTypeLoader extends SimplePreparableReloadListener<DepositTypeLoader.Prepared> {
    /** Datapack directory scanned: {@code data/<ns>/deposit_type/*.json} (namespace-root). */
    private static final FileToIdConverter DEPOSIT_TYPE_LISTER =
            FileToIdConverter.json("deposit_type");

    private static final String CONFIG_SUBDIR = "coedeposits";
    private static final String CONFIG_FILE = "deposits.json";

    /** Resource-location → parsed type. Replaced atomically by {@link #apply}. */
    private volatile Map<ResourceLocation, DepositType> types = Map.of();

    /** Cached recipe-id set used by managed types — suppresses COE-native placement of those recipes. */
    private volatile Set<ResourceLocation> managedVeinRecipes = Set.of();

    /** Cached recipe-id → COE-mode type id, used by the picker's COE delegation branch. */
    private volatile Map<ResourceLocation, ResourceLocation> byCoeVeinRecipe = Map.of();

    /**
     * Lazily-built implicit COE-placement types for vein recipes that no
     * declared {@link DepositType} owns — the {@code auto_adopt_coe_veins}
     * path (add-on / datapack veins). Keyed by, and id-equal to, the vein
     * recipe id, so a {@link Deposit} adopting one stores that vein id as its
     * {@code typeId} and {@link #get} resolves it transparently. Cleared on
     * every {@link #apply} (a reload may now declare the type, or the recipe
     * may be gone). Concurrent because the picker can adopt on a chunk-load
     * thread while another chunk loads.
     */
    private final Map<ResourceLocation, DepositType> implicitTypes = new ConcurrentHashMap<>();

    /** Off-thread reload result: merged registry plus per-layer counts for logging. */
    public record Prepared(Map<ResourceLocation, DepositType> types, int datapackCount, int scriptedCount, int overlayCount) {}

    @Override
    protected Prepared prepare(ResourceManager mgr, ProfilerFiller profiler) {
        Map<ResourceLocation, DepositType> merged = new HashMap<>();
        int datapackCount = loadDatapackTypes(mgr, merged);
        int scriptedCount = loadScriptedTypes(merged);
        int overlayCount = loadConfigOverlay(merged);
        return new Prepared(merged, datapackCount, scriptedCount, overlayCount);
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager mgr, ProfilerFiller profiler) {
        types = Map.copyOf(prepared.types());
        rebuildIndexes(prepared.types());
        // Drop adopted implicit types — a reload may now declare a real type for
        // a vein, change its recipe, or remove it; stale implicit copies would
        // otherwise shadow the declared one in get().
        implicitTypes.clear();
        if (Config.LOG_LIFECYCLE.get()) {
            Coedeposits.LOGGER.info(
                    "[coedeposits] loaded {} deposit types ({} datapack + {} KubeJS + {} config-overlay override(s)): {}",
                    types.size(), prepared.datapackCount(), prepared.scriptedCount(),
                    prepared.overlayCount(), types.keySet());
        }
        for (DepositConfigValidator.Issue issue : DepositConfigValidator.validateStructure(types)) {
            if (issue.severity() == DepositConfigValidator.Severity.ERROR) {
                Coedeposits.LOGGER.error("[coedeposits] config — {}: {}", issue.typeId(), issue.message());
            } else {
                Coedeposits.LOGGER.warn("[coedeposits] config — {}: {}", issue.typeId(), issue.message());
            }
        }
    }

    /** Decode every datapack {@code deposit_type/*.json} into {@code out}. */
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
     * Merge KubeJS-script-registered types ({@link ScriptedDepositRegistry}) over
     * the datapack layer — same parse + inline-binding + junk-pruning as the
     * datapack path. A no-op when KubeJS isn't installed (the registry is then
     * always empty), so this stays free for vanilla setups.
     *
     * @return number of scripted types merged
     */
    private static int loadScriptedTypes(Map<ResourceLocation, DepositType> out) {
        int count = 0;
        for (Map.Entry<ResourceLocation, com.google.gson.JsonObject> e : ScriptedDepositRegistry.snapshot().entrySet()) {
            Optional<DepositType> parsed = DepositType.CODEC.parse(JsonOps.INSTANCE, e.getValue())
                    .resultOrPartial(err -> Coedeposits.LOGGER.error(
                            "[coedeposits] KubeJS deposit type '{}' failed to parse: {}", e.getKey(), err));
            if (parsed.isPresent()) {
                out.put(e.getKey(), bindInlineRecipe(e.getKey(), parsed.get()));
                count++;
            }
        }
        return count;
    }

    /** Read the optional config overlay and apply each non-comment entry over {@code out}. */
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
            // {"enabled": false} disable directive — drops the id from the registry.
            if (e.getValue().isJsonObject()) {
                JsonElement en = e.getValue().getAsJsonObject().get("enabled");
                if (en != null && en.isJsonPrimitive() && !en.getAsBoolean()) {
                    out.remove(id);
                    count++;
                    continue;
                }
            }
            Optional<DepositType> parsed = DepositType.CODEC.parse(JsonOps.INSTANCE, e.getValue())
                    .resultOrPartial(err -> Coedeposits.LOGGER.error(
                            "[coedeposits] {}: failed to parse '{}': {}", file, key, err));
            if (parsed.isPresent()) {
                out.put(id, bindInlineRecipe(id, parsed.get()));
                count++;
            }
        }
        return count;
    }

    /** Bind the inline-vein recipe and drop junk references. */
    private static DepositType bindInlineRecipe(ResourceLocation id, DepositType type) {
        if (type.vein().isPresent()) {
            ResourceLocation derived = new ResourceLocation(id.getNamespace(), id.getPath() + "_vein");
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
                t.biomeFilter(), t.reveal(), t.dimensions(), t.vein(), t.drilling(), t.extracting());
    }

    /** Recompute managed/COE indexes from the live registry. */
    private void rebuildIndexes(Map<ResourceLocation, DepositType> registry) {
        Set<ResourceLocation> managed = new HashSet<>();
        Map<ResourceLocation, ResourceLocation> coe = new HashMap<>();
        for (var e : registry.entrySet()) {
            DepositType t = e.getValue();
            if (t.placement() == DepositType.Placement.MANAGED) {
                for (DepositType.WeightedRecipe wr : t.veinRecipes()) {
                    managed.add(wr.recipe());
                }
            } else if (t.placement() == DepositType.Placement.COE) {
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
        return CoedepositsPlatform.get().configDir().resolve(CONFIG_SUBDIR).resolve(CONFIG_FILE);
    }

    /** Direct map view — used by the picker for weighted iteration. Don't mutate. */
    public Map<ResourceLocation, DepositType> all() {
        return types;
    }

    /**
     * Lookup a single type by id. Falls back to the implicit (auto-adopted)
     * type table so a {@link Deposit} that adopted a foreign COE vein — whose
     * {@code typeId} is the vein id — still resolves its type for snapshots,
     * the finder, refill, depletion, etc. {@code null} only if neither a
     * declared nor an adopted type exists for the id.
     */
    public DepositType get(ResourceLocation id) {
        DepositType t = types.get(id);
        return t != null ? t : implicitTypes.get(id);
    }

    /**
     * Get (or lazily build) the implicit COE-placement type for a vein recipe
     * that no declared type owns — the {@code auto_adopt_coe_veins} path. The
     * type's id is the vein id itself; it carries a single-recipe pool, COE
     * placement, single-chunk size and otherwise inert defaults (the OreData
     * amount/finite all come from the vein recipe). Idempotent + concurrent.
     */
    public DepositType adoptImplicit(ResourceLocation veinId) {
        return implicitTypes.computeIfAbsent(veinId, DepositTypeLoader::buildImplicit);
    }

    /** Construct the default implicit COE type for {@code veinId}. See {@link #adoptImplicit}. */
    private static DepositType buildImplicit(ResourceLocation veinId) {
        return new DepositType(
                List.of(new DepositType.WeightedRecipe(veinId, 1)),
                Optional.empty(),                                   // veinRecipeInfinite
                List.of(),                                          // fillers
                0.0,                                                // replenishRatePerHour
                DepositType.Placement.COE,                          // generation: COE spread
                new DepositType.IntRange(0, Integer.MAX_VALUE),     // distance: any
                new DepositType.IntRange(1, 1),                     // size: single chunk
                new DepositType.PerChunkUnits(0L, Optional.of(0L)), // per-chunk units: unused for COE
                0,                                                  // weight: inert in the managed roll
                Optional.empty(),                                   // map_color → typeId-hash fallback
                List.of(),                                          // biome_filter: any
                Optional.empty(),                                   // reveal → global default
                List.of(),                                          // dimensions: any
                Optional.empty(), Optional.empty(), Optional.empty()); // no inline vein/drilling/fluid
    }

    /** Set of vein_recipe ids consumed by MANAGED types — picker discards COE's natural placement of these. */
    public Set<ResourceLocation> managedVeinRecipes() {
        return managedVeinRecipes;
    }

    /** Look up the COE-mode type id whose vein_recipe matches the given id, or {@code null}. */
    public ResourceLocation coeTypeIdForVeinRecipe(ResourceLocation veinRecipe) {
        return byCoeVeinRecipe.get(veinRecipe);
    }

    /** True when at least one declared {@code placement=coe} type owns a vein recipe. */
    public boolean hasCoePlacementType() {
        return !byCoeVeinRecipe.isEmpty();
    }
}
