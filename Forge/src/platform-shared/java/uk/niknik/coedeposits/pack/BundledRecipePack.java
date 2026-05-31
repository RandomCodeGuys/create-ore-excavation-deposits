package uk.niknik.coedeposits.pack;

import java.io.ByteArrayInputStream;
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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import net.minecraftforge.fml.loading.FMLPaths;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Virtual datapack that synthesises COE {@code vein} / {@code drilling} /
 * {@code extracting} recipes on the fly from inline {@code vein:} / {@code drilling:}
 * / {@code fluid:} blocks in {@code config/coedeposits/deposits.json}.
 *
 * <p><b>1.20.1 line:</b> built against the 1.20.1 pack API
 * ({@code AbstractPackResources(String, boolean)}, no {@code PackLocationInfo}) and
 * emits the 1.20.1 COE recipe JSON shapes — {@code vein_id} (snake), {@code item}
 * (drilling output), {@code fluid} (extractor output), {@code amountMin/Max},
 * {@code neverFinite} (bool), and {@code icon: {item}}.
 */
public class BundledRecipePack extends AbstractPackResources {
    /** Stable id for the virtual pack — visible in {@code /datapack list}. */
    public static final String PACK_ID = "coedeposits-config";

    /** Path under {@code config/} where the deposits config file lives. */
    private static final String DEPOSITS_JSON_PATH = "coedeposits/deposits.json";

    /** 1.20.1 data-pack format. */
    private static final int PACK_FORMAT = 15;

    @Nullable
    private Map<ResourceLocation, byte[]> generatedRecipes;

    public BundledRecipePack(String packId) {
        super(packId, true);
    }

    @Override
    @Nullable
    public IoSupplier<InputStream> getRootResource(String... elements) {
        if (elements.length == 1 && "pack.mcmeta".equals(elements[0])) {
            String meta = "{\"pack\":{\"description\":\"Coedeposits config-synthesised recipes\","
                    + "\"pack_format\":" + PACK_FORMAT + "}}";
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

    private synchronized Map<ResourceLocation, byte[]> getOrLoadRecipes() {
        if (generatedRecipes == null) {
            generatedRecipes = generateAll();
        }
        return generatedRecipes;
    }

    private Map<ResourceLocation, byte[]> generateAll() {
        Map<ResourceLocation, byte[]> out = new HashMap<>();
        Path file = FMLPaths.CONFIGDIR.get().resolve(DEPOSITS_JSON_PATH);
        if (!Files.exists(file)) {
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
                            "[coedeposits] failed to synthesise recipes for '{}': {}", key, inner.toString());
                }
            }
        } catch (Exception ex) {
            Coedeposits.LOGGER.error(
                    "[coedeposits] couldn't read deposits.json for recipe synthesis: {}", ex.toString());
        }
        return out;
    }

    private void processEntry(ResourceLocation typeId, JsonObject entry, Map<ResourceLocation, byte[]> out) {
        ResourceLocation veinId = deriveVeinId(typeId, entry);
        if (veinId == null) return;

        if (entry.has("vein") && entry.get("vein").isJsonObject()) {
            String json = synthesizeVeinRecipe(entry.getAsJsonObject("vein"), entry);
            out.put(recipeResourceLocation(veinId), json.getBytes(StandardCharsets.UTF_8));
        }
        if (entry.has("drilling") && entry.get("drilling").isJsonObject()) {
            String json = synthesizeDrillingRecipe(entry.getAsJsonObject("drilling"), veinId);
            out.put(recipeResourceLocation(deriveDrillingId(veinId)), json.getBytes(StandardCharsets.UTF_8));
        }
        if (entry.has("fluid") && entry.get("fluid").isJsonObject()) {
            String json = synthesizeExtractingRecipe(entry.getAsJsonObject("fluid"), veinId);
            if (json != null) {
                out.put(recipeResourceLocation(deriveExtractingId(veinId)), json.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @Nullable
    private static ResourceLocation deriveVeinId(ResourceLocation typeId, JsonObject entry) {
        if (entry.has("vein") && entry.get("vein").isJsonObject()) {
            return new ResourceLocation(typeId.getNamespace(), typeId.getPath() + "_vein");
        }
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
        return new ResourceLocation(typeId.getNamespace(), typeId.getPath() + "_vein");
    }

    private static ResourceLocation deriveDrillingId(ResourceLocation veinId) {
        String path = veinId.getPath();
        path = path.endsWith("_vein")
                ? path.substring(0, path.length() - "_vein".length()) + "_drilling"
                : path + "_drilling";
        return new ResourceLocation(veinId.getNamespace(), path);
    }

    private static ResourceLocation deriveExtractingId(ResourceLocation veinId) {
        String path = veinId.getPath();
        path = path.endsWith("_vein")
                ? path.substring(0, path.length() - "_vein".length()) + "_extracting"
                : path + "_extracting";
        return new ResourceLocation(veinId.getNamespace(), path);
    }

    /** Recipe id → pack-resource location ({@code recipe/<path>.json} — 1.20.1 has no {@code recipes/} variance). */
    private static ResourceLocation recipeResourceLocation(ResourceLocation recipeId) {
        return new ResourceLocation(recipeId.getNamespace(), "recipe/" + recipeId.getPath() + ".json");
    }

    /** Build a COE 1.20.1 {@code vein} recipe JSON from the inline spec. */
    private String synthesizeVeinRecipe(JsonObject veinSpec, JsonObject entry) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "createoreexcavation:vein");

        String displayName = veinSpec.has("display_name")
                ? veinSpec.get("display_name").getAsString() : "Deposit";
        out.addProperty("name", "{\"text\":\""
                + displayName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");

        out.addProperty("priority", 0);
        // 1.20.1: a vein is finite by default; "neverFinite": true marks an infinite one.
        String finite = veinSpec.has("finite") ? veinSpec.get("finite").getAsString() : "always";
        if ("never".equals(finite)) out.addProperty("neverFinite", true);
        out.addProperty("amountMin", veinSpec.has("amount_multiplier_min")
                ? veinSpec.get("amount_multiplier_min").getAsFloat() : 2.0f);
        out.addProperty("amountMax", veinSpec.has("amount_multiplier_max")
                ? veinSpec.get("amount_multiplier_max").getAsFloat() : 25.0f);

        JsonObject placement = new JsonObject();
        if (veinSpec.has("placement") && veinSpec.get("placement").isJsonObject()) {
            JsonObject p = veinSpec.getAsJsonObject("placement");
            placement.addProperty("salt", p.get("salt").getAsInt());
            placement.addProperty("separation", p.has("separation") ? p.get("separation").getAsInt() : 8);
            placement.addProperty("spacing", p.has("spacing") ? p.get("spacing").getAsInt() : 256);
        } else {
            placement.addProperty("salt", Math.abs(entry.toString().hashCode()));
            placement.addProperty("separation", 8);
            placement.addProperty("spacing", 256);
        }
        out.add("placement", placement);

        // Icon — 1.20.1 vein icon is {"item": "<id>"} (no count).
        String iconId = null;
        if (veinSpec.has("icon")) iconId = veinSpec.get("icon").getAsString();
        if (iconId == null && entry.has("drilling") && entry.get("drilling").isJsonObject()) {
            JsonObject drilling = entry.getAsJsonObject("drilling");
            if (drilling.has("outputs") && drilling.get("outputs").isJsonArray()) {
                JsonArray outs = drilling.getAsJsonArray("outputs");
                if (outs.size() > 0 && outs.get(0).isJsonObject()
                        && outs.get(0).getAsJsonObject().has("item")) {
                    iconId = outs.get(0).getAsJsonObject().get("item").getAsString();
                }
            }
        }
        if (iconId == null && entry.has("fluid") && entry.get("fluid").isJsonObject()) {
            JsonObject fluidSpec = entry.getAsJsonObject("fluid");
            if (fluidSpec.has("fluid") && fluidSpec.get("fluid").isJsonPrimitive()) {
                iconId = bucketItemId(fluidSpec.get("fluid").getAsString());
            }
        }
        if (iconId == null) iconId = "minecraft:stone";
        JsonObject icon = new JsonObject();
        icon.addProperty("item", iconId);
        out.add("icon", icon);

        return out.toString();
    }

    /** Build a COE 1.20.1 {@code drilling} recipe JSON (output items keyed {@code item}, {@code vein_id}). */
    private String synthesizeDrillingRecipe(JsonObject drillSpec, ResourceLocation veinId) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "createoreexcavation:drilling");

        JsonObject drill = new JsonObject();
        drill.addProperty("tag", drillSpec.has("drill_tag")
                ? drillSpec.get("drill_tag").getAsString() : "createoreexcavation:drills");
        out.add("drill", drill);

        JsonArray outputs = new JsonArray();
        if (drillSpec.has("outputs") && drillSpec.get("outputs").isJsonArray()) {
            for (JsonElement el : drillSpec.getAsJsonArray("outputs")) {
                if (!el.isJsonObject()) continue;
                JsonObject src = el.getAsJsonObject();
                if (!src.has("item")) continue;
                JsonObject dst = new JsonObject();
                dst.addProperty("item", src.get("item").getAsString());
                int count = src.has("count") ? src.get("count").getAsInt() : 1;
                if (count != 1) dst.addProperty("count", count);
                float chance = src.has("chance") ? src.get("chance").getAsFloat() : 1.0f;
                if (chance < 1.0f) dst.addProperty("chance", chance);
                outputs.add(dst);
            }
        }
        out.add("output", outputs);

        out.addProperty("priority", 0);
        out.addProperty("stress", drillSpec.has("stress") ? drillSpec.get("stress").getAsInt() : 256);
        out.addProperty("ticks", drillSpec.has("ticks") ? drillSpec.get("ticks").getAsInt() : 100);
        out.addProperty("vein_id", veinId.toString());

        return out.toString();
    }

    /** Build a COE 1.20.1 {@code extracting} (fluid) recipe JSON (output {@code {fluid, amount}}, {@code vein_id}). */
    @Nullable
    private String synthesizeExtractingRecipe(JsonObject fluidSpec, ResourceLocation veinId) {
        if (!fluidSpec.has("fluid") || !fluidSpec.get("fluid").isJsonPrimitive()) {
            Coedeposits.LOGGER.warn(
                    "[coedeposits] inline `fluid` block for vein '{}' has no 'fluid' id — extracting recipe skipped",
                    veinId);
            return null;
        }
        JsonObject out = new JsonObject();
        out.addProperty("type", "createoreexcavation:extracting");

        JsonObject drill = new JsonObject();
        drill.addProperty("tag", fluidSpec.has("drill_tag")
                ? fluidSpec.get("drill_tag").getAsString() : "createoreexcavation:drills");
        out.add("drill", drill);

        JsonObject output = new JsonObject();
        output.addProperty("fluid", fluidSpec.get("fluid").getAsString());
        output.addProperty("amount", fluidSpec.has("amount") ? fluidSpec.get("amount").getAsInt() : 500);
        out.add("output", output);

        out.addProperty("priority", 0);
        out.addProperty("stress", fluidSpec.has("stress") ? fluidSpec.get("stress").getAsInt() : 256);
        out.addProperty("ticks", fluidSpec.has("ticks") ? fluidSpec.get("ticks").getAsInt() : 20);
        out.addProperty("vein_id", veinId.toString());

        return out.toString();
    }

    /** Fluid id → its bucket item id for a vein icon (water → minecraft:water_bucket). */
    private static String bucketItemId(String fluidId) {
        ResourceLocation rl = ResourceLocation.tryParse(fluidId == null ? "" : fluidId.trim());
        if (rl != null) {
            Item bucket = BuiltInRegistries.FLUID.get(rl).getBucket();
            if (bucket != Items.AIR) {
                return BuiltInRegistries.ITEM.getKey(bucket).toString();
            }
        }
        return "minecraft:bucket";
    }
}
