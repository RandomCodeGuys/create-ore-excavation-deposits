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
import net.minecraftforge.fml.loading.FMLPaths;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;

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

    /** Off-thread reload result: merged registry plus per-layer counts for logging. */
    public record Prepared(Map<ResourceLocation, DepositType> types, int datapackCount, int overlayCount) {}

    @Override
    protected Prepared prepare(ResourceManager mgr, ProfilerFiller profiler) {
        Map<ResourceLocation, DepositType> merged = new HashMap<>();
        int datapackCount = loadDatapackTypes(mgr, merged);
        int overlayCount = loadConfigOverlay(merged);
        return new Prepared(merged, datapackCount, overlayCount);
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager mgr, ProfilerFiller profiler) {
        types = Map.copyOf(prepared.types());
        rebuildIndexes(prepared.types());
        if (Config.LOG_LIFECYCLE.get()) {
            Coedeposits.LOGGER.info(
                    "[coedeposits] loaded {} deposit types ({} datapack + {} config-overlay override(s)): {}",
                    types.size(), prepared.datapackCount(), prepared.overlayCount(), types.keySet());
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
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_SUBDIR).resolve(CONFIG_FILE);
    }

    /** Direct map view — used by the picker for weighted iteration. Don't mutate. */
    public Map<ResourceLocation, DepositType> all() {
        return types;
    }

    /** Lookup a single type by id. {@code null} if not loaded. */
    public DepositType get(ResourceLocation id) {
        return types.get(id);
    }

    /** Set of vein_recipe ids consumed by MANAGED types — picker discards COE's natural placement of these. */
    public Set<ResourceLocation> managedVeinRecipes() {
        return managedVeinRecipes;
    }

    /** Look up the COE-mode type id whose vein_recipe matches the given id, or {@code null}. */
    public ResourceLocation coeTypeIdForVeinRecipe(ResourceLocation veinRecipe) {
        return byCoeVeinRecipe.get(veinRecipe);
    }
}
