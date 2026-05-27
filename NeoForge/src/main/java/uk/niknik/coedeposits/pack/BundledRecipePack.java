package uk.niknik.coedeposits.pack;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;

import net.neoforged.fml.loading.FMLPaths;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Virtual datapack that synthesises COE {@code vein} and {@code drilling}
 * recipes on the fly from inline {@code vein:} / {@code drilling:} blocks in
 * {@code config/coedeposits/deposits.json}. Lets admins describe a complete
 * ore (placement + drill output + slag) in a single config entry without
 * shipping a separate datapack.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Datapack reload fires; our pack is queried for resources.</li>
 *   <li>First {@link #getResource} or {@link #listResources} call triggers
 *       {@link #getOrLoadRecipes()} which reads {@code deposits.json} from
 *       disk, parses each entry, and generates JSON bytes for any inline
 *       {@code vein:} / {@code drilling:} block.</li>
 *   <li>The generated recipes are cached for the lifetime of this pack
 *       instance (one per reload), then served from memory.</li>
 *   <li>Recipe IDs are derived: {@code vein:} → {@code <vein_recipe id>}
 *       (or {@code <typeId>_vein} when no explicit recipe id is set);
 *       {@code drilling:} → vein id with {@code _vein} swapped for
 *       {@code _drilling}.</li>
 * </ol>
 *
 * <h2>Pack metadata</h2>
 * Synthesised at runtime via {@link #getRootResource} — a minimal
 * {@code pack.mcmeta} describing pack format SERVER_DATA at the current
 * Minecraft version. AbstractPackResources reads this via the inherited
 * {@code getMetadataSection} implementation.
 *
 * <h2>Lifecycle</h2>
 * One instance per datapack reload. {@link #close()} drops the cache so the
 * next reload reads {@code deposits.json} fresh.
 */
public class BundledRecipePack extends AbstractPackResources {
    /** Stable id for the virtual pack — visible in {@code /datapack list}. */
    public static final String PACK_ID = "coedeposits-config";

    /** Path under {@code config/} where the deposits config file lives. */
    private static final String DEPOSITS_JSON_PATH = "coedeposits/deposits.json";

    /** Per-reload cache: full pack resource map (location → JSON bytes). */
    @Nullable
    private Map<ResourceLocation, byte[]> generatedRecipes;

    public BundledRecipePack(PackLocationInfo location) {
        super(location);
    }

    @Override
    @Nullable
    public IoSupplier<InputStream> getRootResource(String... elements) {
        // Only serve pack.mcmeta — no pack.png or other root resources.
        // AbstractPackResources.getMetadataSection reads this to populate
        // PackMetadataSection during Pack.readMetaAndCreate.
        if (elements.length == 1 && "pack.mcmeta".equals(elements[0])) {
            int packFormat = SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA);
            String meta = "{\"pack\":{\"description\":\"Coedeposits config-synthesised recipes\","
                    + "\"pack_format\":" + packFormat + "}}";
            byte[] bytes = meta.getBytes(StandardCharsets.UTF_8);
            return () -> new ByteArrayInputStream(bytes);
        }
        return null;
    }

    @Override
    @Nullable
    public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
        if (packType != PackType.SERVER_DATA) return null;
        Map<ResourceLocation, byte[]> recipes = getOrLoadRecipes();
        byte[] bytes = recipes.get(location);
        if (bytes == null) return null;
        return () -> new ByteArrayInputStream(bytes);
    }

    /**
     * Enumerate every generated recipe whose location matches the namespace
     * and path prefix. Empty when packType is CLIENT_RESOURCES (no client
     * assets) or the namespace has no recipes in our pack.
     */
    @Override
    public void listResources(PackType packType, String namespace, String path,
                               net.minecraft.server.packs.PackResources.ResourceOutput resourceOutput) {
        if (packType != PackType.SERVER_DATA) return;
        Map<ResourceLocation, byte[]> recipes = getOrLoadRecipes();
        for (Map.Entry<ResourceLocation, byte[]> e : recipes.entrySet()) {
            ResourceLocation rl = e.getKey();
            if (!rl.getNamespace().equals(namespace)) continue;
            if (!rl.getPath().startsWith(path)) continue;
            byte[] bytes = e.getValue();
            resourceOutput.accept(rl, () -> new ByteArrayInputStream(bytes));
        }
    }

    @Override
    public Set<String> getNamespaces(PackType packType) {
        if (packType != PackType.SERVER_DATA) return Set.of();
        Map<ResourceLocation, byte[]> recipes = getOrLoadRecipes();
        Set<String> ns = new HashSet<>();
        for (ResourceLocation rl : recipes.keySet()) ns.add(rl.getNamespace());
        return ns;
    }

    @Override
    public void close() {
        generatedRecipes = null;
    }

    /** Lazy-init cache; idempotent for the pack's lifetime. */
    private synchronized Map<ResourceLocation, byte[]> getOrLoadRecipes() {
        if (generatedRecipes == null) {
            generatedRecipes = generateAll();
        }
        return generatedRecipes;
    }

    /**
     * Read {@code deposits.json}, parse every non-comment entry, and emit
     * synthesised vein + drilling recipe JSONs for entries that have inline
     * {@code vein:} / {@code drilling:} blocks. Returns empty map when the
     * config file is missing or unreadable (treated as "no inline recipes",
     * the picker still works via externally-bundled recipes).
     */
    private Map<ResourceLocation, byte[]> generateAll() {
        Map<ResourceLocation, byte[]> out = new HashMap<>();
        Path file = FMLPaths.CONFIGDIR.get().resolve(DEPOSITS_JSON_PATH);
        if (!Files.exists(file)) {
            // Config not yet copied — happens during fresh server start before
            // DepositTypeLoader's ensureExists path runs. No inline recipes
            // until next reload; bundled ones (in jar's data/) still load.
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return out;
            JsonObject obj = root.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String key = e.getKey();
                if (key.startsWith("_")) continue;
                if (!e.getValue().isJsonObject()) continue;
                ResourceLocation typeId = ResourceLocation.tryParse(key);
                if (typeId == null) continue;
                try {
                    processEntry(typeId, e.getValue().getAsJsonObject(), out);
                } catch (Exception inner) {
                    Coedeposits.LOGGER.error(
                            "[coedeposits] failed to synthesise recipes for '{}': {}",
                            key, inner.toString());
                }
            }
        } catch (Exception ex) {
            Coedeposits.LOGGER.error(
                    "[coedeposits] couldn't read deposits.json for recipe synthesis: {}",
                    ex.toString());
        }
        return out;
    }

    /** Generate vein/drilling recipes for one deposit-type entry if it has inline blocks. */
    private void processEntry(ResourceLocation typeId, JsonObject entry, Map<ResourceLocation, byte[]> out) {
        ResourceLocation veinId = deriveVeinId(typeId, entry);
        if (veinId == null) return;

        // Inline vein spec → synthesise vein recipe JSON.
        if (entry.has("vein") && entry.get("vein").isJsonObject()) {
            String json = synthesizeVeinRecipe(entry.getAsJsonObject("vein"), entry);
            ResourceLocation resourceLoc = recipeResourceLocation(veinId);
            out.put(resourceLoc, json.getBytes(StandardCharsets.UTF_8));
        }

        // Inline drilling spec → synthesise drilling recipe JSON bound to veinId.
        if (entry.has("drilling") && entry.get("drilling").isJsonObject()) {
            String json = synthesizeDrillingRecipe(entry.getAsJsonObject("drilling"), veinId);
            ResourceLocation drillId = deriveDrillingId(veinId);
            ResourceLocation resourceLoc = recipeResourceLocation(drillId);
            out.put(resourceLoc, json.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Resolve which vein-recipe ID this entry's inline {@code vein:} block
     * should target. Three sources tried in order: legacy {@code vein_recipe},
     * first entry of {@code vein_recipes}, or derive from typeId as
     * {@code <ns>:<path>_vein}.
     */
    @Nullable
    private static ResourceLocation deriveVeinId(ResourceLocation typeId, JsonObject entry) {
        if (entry.has("vein_recipe") && entry.get("vein_recipe").isJsonPrimitive()) {
            return ResourceLocation.tryParse(entry.get("vein_recipe").getAsString());
        }
        if (entry.has("vein_recipes")) {
            JsonElement vr = entry.get("vein_recipes");
            JsonObject first = null;
            if (vr.isJsonArray() && vr.getAsJsonArray().size() > 0) {
                JsonElement firstElem = vr.getAsJsonArray().get(0);
                if (firstElem.isJsonObject()) first = firstElem.getAsJsonObject();
            } else if (vr.isJsonObject()) {
                first = vr.getAsJsonObject();
            }
            if (first != null && first.has("recipe")) {
                return ResourceLocation.tryParse(first.get("recipe").getAsString());
            }
        }
        // Auto-derive when no explicit recipe id specified — admin only filled
        // in the inline spec.
        return ResourceLocation.fromNamespaceAndPath(
                typeId.getNamespace(), typeId.getPath() + "_vein");
    }

    /**
     * Drilling recipe id derived from vein id: swap trailing {@code _vein}
     * for {@code _drilling}, or append {@code _drilling} when no such suffix.
     */
    private static ResourceLocation deriveDrillingId(ResourceLocation veinId) {
        String path = veinId.getPath();
        if (path.endsWith("_vein")) {
            path = path.substring(0, path.length() - "_vein".length()) + "_drilling";
        } else {
            path = path + "_drilling";
        }
        return ResourceLocation.fromNamespaceAndPath(veinId.getNamespace(), path);
    }

    /** Recipe id → pack-resource location ({@code data/<ns>/recipe/<path>.json}). */
    private static ResourceLocation recipeResourceLocation(ResourceLocation recipeId) {
        return ResourceLocation.fromNamespaceAndPath(
                recipeId.getNamespace(), "recipe/" + recipeId.getPath() + ".json");
    }

    /**
     * Build a COE {@code vein} recipe JSON from the inline spec + a few
     * fallbacks. The recipe needs every field COE's serializer expects;
     * missing fields fall back to sensible defaults.
     */
    private String synthesizeVeinRecipe(JsonObject veinSpec, JsonObject entry) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "createoreexcavation:vein");

        // Display name — COE expects a serialized text-component JSON string.
        String displayName = veinSpec.has("display_name")
                ? veinSpec.get("display_name").getAsString() : "Deposit";
        out.addProperty("name", "{\"text\":\""
                + displayName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");

        out.addProperty("priority", 0);
        out.addProperty("finite",
                veinSpec.has("finite") ? veinSpec.get("finite").getAsString() : "always");
        out.addProperty("amountMultiplierMin", veinSpec.has("amount_multiplier_min")
                ? veinSpec.get("amount_multiplier_min").getAsFloat() : 2.0f);
        out.addProperty("amountMultiplierMax", veinSpec.has("amount_multiplier_max")
                ? veinSpec.get("amount_multiplier_max").getAsFloat() : 25.0f);

        // Placement — required by COE serializer even though our MANAGED
        // picker ignores it. Use admin-supplied values or derive a default
        // salt from entry hash so distinct types don't collide.
        JsonObject placement = new JsonObject();
        if (veinSpec.has("placement") && veinSpec.get("placement").isJsonObject()) {
            JsonObject p = veinSpec.getAsJsonObject("placement");
            placement.addProperty("salt", p.get("salt").getAsInt());
            placement.addProperty("separation",
                    p.has("separation") ? p.get("separation").getAsInt() : 8);
            placement.addProperty("spacing",
                    p.has("spacing") ? p.get("spacing").getAsInt() : 256);
        } else {
            placement.addProperty("salt", Math.abs(entry.toString().hashCode()));
            placement.addProperty("separation", 8);
            placement.addProperty("spacing", 256);
        }
        out.add("placement", placement);

        // Icon — vein finder uses this in its hover text + JEI category icon.
        // If admin didn't specify, try to derive from the first drilling output.
        JsonObject icon = new JsonObject();
        icon.addProperty("count", veinSpec.has("icon_count")
                ? veinSpec.get("icon_count").getAsInt() : 1);
        String iconId = null;
        if (veinSpec.has("icon")) iconId = veinSpec.get("icon").getAsString();
        if (iconId == null && entry.has("drilling")
                && entry.get("drilling").isJsonObject()) {
            JsonObject drilling = entry.getAsJsonObject("drilling");
            if (drilling.has("outputs") && drilling.get("outputs").isJsonArray()) {
                JsonArray outs = drilling.getAsJsonArray("outputs");
                if (outs.size() > 0 && outs.get(0).isJsonObject()
                        && outs.get(0).getAsJsonObject().has("item")) {
                    iconId = outs.get(0).getAsJsonObject().get("item").getAsString();
                }
            }
        }
        if (iconId == null) iconId = "minecraft:stone";
        icon.addProperty("id", iconId);
        out.add("icon", icon);

        return out.toString();
    }

    /**
     * Build a COE {@code drilling} recipe JSON. Each output entry rolls its
     * own chance per cycle (Create's ProcessingOutput semantic). Omit
     * {@code chance} from the wire JSON when it equals 1.0 — that's COE's
     * default and writing it explicitly bloats the payload.
     */
    private String synthesizeDrillingRecipe(JsonObject drillSpec, ResourceLocation veinId) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "createoreexcavation:drilling");

        // Drill ingredient — single tag (default "createoreexcavation:drills").
        JsonObject drill = new JsonObject();
        String drillTag = drillSpec.has("drill_tag")
                ? drillSpec.get("drill_tag").getAsString()
                : "createoreexcavation:drills";
        drill.addProperty("tag", drillTag);
        out.add("drill", drill);

        // Outputs — each entry becomes a Create ProcessingOutput {id, count?, chance?}.
        JsonArray outputs = new JsonArray();
        if (drillSpec.has("outputs") && drillSpec.get("outputs").isJsonArray()) {
            for (JsonElement el : drillSpec.getAsJsonArray("outputs")) {
                if (!el.isJsonObject()) continue;
                JsonObject src = el.getAsJsonObject();
                if (!src.has("item")) continue;
                JsonObject dst = new JsonObject();
                dst.addProperty("id", src.get("item").getAsString());
                int count = src.has("count") ? src.get("count").getAsInt() : 1;
                if (count != 1) dst.addProperty("count", count);
                float chance = src.has("chance") ? src.get("chance").getAsFloat() : 1.0f;
                if (chance < 1.0f) dst.addProperty("chance", chance);
                outputs.add(dst);
            }
        }
        out.add("output", outputs);

        out.addProperty("priority", 0);
        out.addProperty("stress",
                drillSpec.has("stress") ? drillSpec.get("stress").getAsInt() : 256);
        out.addProperty("ticks",
                drillSpec.has("ticks") ? drillSpec.get("ticks").getAsInt() : 100);
        out.addProperty("veinId", veinId.toString());

        return out.toString();
    }
}
