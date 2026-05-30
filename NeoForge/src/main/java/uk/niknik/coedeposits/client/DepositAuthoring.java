package uk.niknik.coedeposits.client;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.loading.FMLPaths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.DepositType;

/**
 * Client-side authoring model for the in-game deposit editor. This is purely a
 * <b>config builder</b>: it loads the optional {@code config/coedeposits/deposits.json}
 * overlay into mutable {@link Draft}s, lets the YACL screens edit them with
 * structured controls (no encoded strings), and writes the result back out as
 * JSON via {@link DepositType#CODEC}. What the player then does with that file
 * (singleplayer, ship to a server, …) is out of scope — we only assemble a
 * valid config. The codec round-trip guarantees the written JSON is loadable by
 * {@link uk.niknik.coedeposits.deposit.DepositTypeLoader}.
 */
public final class DepositAuthoring {
    private DepositAuthoring() {}

    private static final String CONFIG_SUBDIR = "coedeposits";
    private static final String CONFIG_FILE = "deposits.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ═══════════════════════════════════════════════════════════════════════
    // Mutable drafts — bound directly to YACL options. Lists hold typed entries
    // (not encoded strings) so each field gets a real control in the editor.
    // ═══════════════════════════════════════════════════════════════════════

    /** One weighted vein recipe in a deposit's per-chunk pool. */
    public static final class RecipeEntry {
        public String recipe = "";
        public int weight = 1;

        public RecipeEntry() {}

        public RecipeEntry(String recipe, int weight) {
            this.recipe = recipe;
            this.weight = weight;
        }
    }

    /** One drill output: an item with a stack size and a per-cycle drop chance. */
    public static final class OutputEntry {
        public String item = "minecraft:raw_iron";
        public int count = 1;
        public float chance = 1.0f;

        public OutputEntry() {}

        public OutputEntry(String item, int count, float chance) {
            this.item = item;
            this.count = count;
            this.chance = chance;
        }
    }

    /** One editable deposit type. {@link #id} is the {@code namespace:path} key. */
    public static final class Draft {
        public String id = "coedeposits:new_deposit";
        /** Seeded from the live registry (a bundled/datapack default), not the overlay file.
         *  Untouched defaults are NOT written back to deposits.json — see {@link #save}. */
        public boolean fromDefault = false;
        /** Disabled via the editor → persisted as {@code "enabled": false}, which the
         *  loader uses to drop the id from the merged registry entirely. */
        public boolean disabled = false;
        public DepositType.Placement placement = DepositType.Placement.MANAGED;
        public final List<RecipeEntry> veinRecipes = new ArrayList<>();
        public String veinRecipeInfinite = "";
        public final List<Integer> fillers = new ArrayList<>();
        public double replenishRatePerHour = 0.0;
        public int distanceMin = 0;
        public int distanceMax = Integer.MAX_VALUE;
        public int sizeMin = 1;
        public int sizeMax = 1;
        public long unitsMin = 0;
        public boolean unitsHasMax = false;
        public long unitsMax = 0;
        public int weight = 0;
        public boolean hasMapColor = false;
        public int mapColor = 0xFFFFFF;
        public final List<String> biomeFilter = new ArrayList<>();
        public boolean overrideReveal = false;
        public Config.RevealMode reveal = Config.RevealMode.ALWAYS;
        public final List<String> dimensions = new ArrayList<>();
        public boolean hasVein = false;
        public final VeinDraft vein = new VeinDraft();
        public boolean hasDrilling = false;
        public final DrillingDraft drilling = new DrillingDraft();
    }

    /** Inline COE vein spec (synthesised into a recipe by BundledRecipePack). */
    public static final class VeinDraft {
        public String displayName = "";
        public float ampMin = 2.0f;
        public float ampMax = 25.0f;
        public String icon = "";
        public int iconCount = 1;
        public boolean hasPlacement = false;
        public int salt = 0;
        public int separation = 8;
        public int spacing = 256;
        public String finite = "always";
    }

    /** Inline COE drilling spec. */
    public static final class DrillingDraft {
        public final List<OutputEntry> outputs = new ArrayList<>();
        public int ticks = 100;
        public int stress = 256;
        public String drillTag = "";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Load / save — codec round-trip against config/coedeposits/deposits.json.
    // ═══════════════════════════════════════════════════════════════════════

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_SUBDIR).resolve(CONFIG_FILE);
    }

    /** Parse the overlay file into drafts. Empty list when the file is absent. */
    public static List<Draft> load() {
        List<Draft> drafts = new ArrayList<>();
        Path f = file();
        if (!Files.exists(f)) return drafts;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) return drafts;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (e.getKey().startsWith("_")) continue;
                ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                if (id == null) continue;
                // Disable directive: a bare {"enabled": false} entry can't (and
                // shouldn't) decode as a full DepositType — surface it as a
                // disabled draft so the editor shows it greyed-out, not absent.
                if (isDisableDirective(e.getValue())) {
                    Draft off = new Draft();
                    off.id = id.toString();
                    off.disabled = true;
                    drafts.add(off);
                    continue;
                }
                DepositType.CODEC.parse(JsonOps.INSTANCE, e.getValue())
                        .resultOrPartial(err -> Coedeposits.LOGGER.error(
                                "[coedeposits] editor: failed to load '{}': {}", e.getKey(), err))
                        .ifPresent(t -> drafts.add(fromType(id, t)));
            }
        } catch (Exception ex) {
            Coedeposits.LOGGER.error("[coedeposits] editor: failed to read {}: {}", f, ex.toString());
        }
        return drafts;
    }

    /** True for a config entry that is solely an {@code {"enabled": false}} disable switch. */
    private static boolean isDisableDirective(JsonElement el) {
        if (!el.isJsonObject()) return false;
        JsonElement en = el.getAsJsonObject().get("enabled");
        return en != null && en.isJsonPrimitive() && !en.getAsBoolean();
    }

    /**
     * Editor's view of every deposit type: the overlay drafts (authoritative —
     * custom types, customised defaults, and disables) plus every live-registry
     * type the overlay doesn't already cover, tagged {@link Draft#fromDefault}.
     * Lets the editor list and edit the 14 bundled ores, not just config additions.
     *
     * <p>Registry source is {@link Coedeposits#DEPOSIT_TYPES}, populated on the
     * integrated server — so on a dedicated server only the host (same JVM) sees
     * defaults; a remote client still gets its own overlay entries.
     */
    public static List<Draft> loadMerged() {
        List<Draft> drafts = load();
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Draft d : drafts) have.add(d.id);
        for (Map.Entry<ResourceLocation, DepositType> e : defaultTypes().entrySet()) {
            String id = e.getKey().toString();
            if (have.contains(id)) continue;  // overlay already defines/overrides it
            Draft d = fromType(e.getKey(), e.getValue());
            d.fromDefault = true;
            drafts.add(d);
        }
        return drafts;
    }

    /** Cache of the mod jar's bundled deposit_type defaults (lazy, read once). */
    private static Map<ResourceLocation, DepositType> bundledCache;

    /**
     * Source of "default" deposit types for the editor. Prefers the live registry
     * ({@link Coedeposits#DEPOSIT_TYPES}) when populated — that's the in-world case
     * and also surfaces third-party datapack types. Falls back to the mod jar's own
     * bundled defaults when the registry is empty, i.e. the <b>main menu</b>, where
     * no world (hence no datapack reload) has run yet. Without the fallback the
     * editor showed zero default ores in the main menu — you had to enter a world.
     */
    private static Map<ResourceLocation, DepositType> defaultTypes() {
        Map<ResourceLocation, DepositType> live = Coedeposits.DEPOSIT_TYPES.all();
        if (live != null && !live.isEmpty()) return live;
        return bundledDefaults();
    }

    /**
     * Read the 14 bundled {@code data/coedeposits/deposit_type/*.json} straight from
     * the mod jar via the NeoForge mod-file API. Available at any time, including the
     * main menu, because the mod jar is always mounted on the classpath. Cached after
     * the first call.
     */
    private static Map<ResourceLocation, DepositType> bundledDefaults() {
        if (bundledCache != null) return bundledCache;
        Map<ResourceLocation, DepositType> out = new java.util.HashMap<>();
        try {
            var info = net.neoforged.fml.ModList.get().getModFileById(Coedeposits.MODID);
            Path dir = info != null
                    ? info.getFile().findResource("data", Coedeposits.MODID, "deposit_type")
                    : null;
            if (dir != null && Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                        String fn = p.getFileName().toString();
                        String name = fn.substring(0, fn.length() - ".json".length());
                        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Coedeposits.MODID, name);
                        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                            DepositType.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(r))
                                    .result().ifPresent(t -> out.put(id, t));
                        } catch (Exception ex) {
                            Coedeposits.LOGGER.error(
                                    "[coedeposits] editor: failed reading bundled default '{}': {}", id, ex.toString());
                        }
                    });
                }
            }
        } catch (Exception ex) {
            Coedeposits.LOGGER.error("[coedeposits] editor: failed enumerating bundled defaults: {}", ex.toString());
        }
        if (out.isEmpty()) {
            // Fallback: enumerate the known bundled ids off the classpath. The
            // mod-file path read above is the normal route (and works in dev +
            // jar); this guards against an empty result so the editor never
            // silently loses its defaults again.
            for (String name : BUNDLED_DEFAULT_NAMES) {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Coedeposits.MODID, name);
                String res = "/data/" + Coedeposits.MODID + "/deposit_type/" + name + ".json";
                try (var in = DepositAuthoring.class.getResourceAsStream(res)) {
                    if (in == null) continue;
                    try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                        DepositType.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(r))
                                .result().ifPresent(t -> out.put(id, t));
                    }
                } catch (Exception ex) {
                    Coedeposits.LOGGER.error(
                            "[coedeposits] editor: classpath fallback failed for '{}': {}", id, ex.toString());
                }
            }
        }
        bundledCache = Map.copyOf(out);
        return bundledCache;
    }

    /** Ids of the ores shipped in the mod jar's {@code data/coedeposits/deposit_type/} — classpath fallback list. */
    private static final String[] BUNDLED_DEFAULT_NAMES = {
            "ancient_debris", "coal", "copper", "diamond", "emerald", "glowstone",
            "gold", "iron", "lapis", "nether_gold", "nether_quartz", "quartz", "redstone", "zinc"
    };

    /** Encode every draft via the codec and write the overlay file (pretty JSON). */
    public static void save(List<Draft> drafts) {
        JsonObject root = new JsonObject();
        root.addProperty("_comment",
                "Generated by the coedeposits in-game editor. Loaded as the config overlay "
                        + "(wins over datapack deposit_type files of the same id).");
        for (Draft d : drafts) {
            ResourceLocation id = ResourceLocation.tryParse(d.id == null ? "" : d.id.trim());
            if (id == null) {
                Coedeposits.LOGGER.error("[coedeposits] editor: skipping entry with invalid id '{}'", d.id);
                continue;
            }
            // Disabled (default or custom): write only the {"enabled": false}
            // directive — the loader drops the id from the registry. Takes
            // precedence over the full-type encode below.
            if (d.disabled) {
                JsonObject off = new JsonObject();
                off.addProperty("enabled", false);
                root.add(id.toString(), off);
                continue;
            }
            JsonElement encoded = DepositType.CODEC.encodeStart(JsonOps.INSTANCE, toType(d))
                    .resultOrPartial(err -> Coedeposits.LOGGER.error(
                            "[coedeposits] editor: failed to encode '{}': {}", d.id, err))
                    .orElse(null);
            if (encoded == null) continue;
            // An untouched bundled/datapack default is left entirely to its
            // datapack source — writing an identical copy to the overlay would
            // freeze it and silently shadow future default changes. Compare the
            // encoded form against the live default and persist only on a real
            // diff. Errs toward persisting (any mismatch writes), so edits are
            // never lost — only provably-unchanged defaults are skipped.
            if (d.fromDefault) {
                DepositType def = defaultTypes().get(id);
                if (def != null) {
                    JsonElement defJson = DepositType.CODEC.encodeStart(JsonOps.INSTANCE, def)
                            .result().orElse(null);
                    if (encoded.equals(defJson)) continue;
                }
            }
            root.add(id.toString(), encoded);
        }
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(root), StandardCharsets.UTF_8);
            Coedeposits.LOGGER.info("[coedeposits] editor: wrote {} deposit type(s) to {}", drafts.size(), f);
        } catch (IOException ex) {
            Coedeposits.LOGGER.error("[coedeposits] editor: failed to write {}: {}", f, ex.toString());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Conversions: DepositType ←→ Draft.
    // ═══════════════════════════════════════════════════════════════════════

    static Draft fromType(ResourceLocation id, DepositType t) {
        Draft d = new Draft();
        d.id = id.toString();
        d.placement = t.placement();
        for (DepositType.WeightedRecipe wr : t.veinRecipes()) {
            d.veinRecipes.add(new RecipeEntry(wr.recipe().toString(), wr.weight()));
        }
        d.veinRecipeInfinite = t.veinRecipeInfinite().map(ResourceLocation::toString).orElse("");
        for (DepositType.WeightedFiller wf : t.fillers()) d.fillers.add(wf.weight());
        d.replenishRatePerHour = t.replenishRatePerHour();
        d.distanceMin = t.distance().min();
        d.distanceMax = t.distance().max();
        d.sizeMin = t.sizeChunks().min();
        d.sizeMax = t.sizeChunks().max();
        d.unitsMin = t.perChunkUnits().min();
        d.unitsHasMax = t.perChunkUnits().max().isPresent();
        d.unitsMax = t.perChunkUnits().max().orElse(t.perChunkUnits().min());
        d.weight = t.weight();
        d.hasMapColor = t.mapColor().isPresent();
        d.mapColor = t.mapColor().orElse(0xFFFFFF);
        for (TagKey<Biome> tag : t.biomeFilter()) d.biomeFilter.add(tag.location().toString());
        d.overrideReveal = t.reveal().isPresent();
        d.reveal = t.reveal().orElse(Config.RevealMode.ALWAYS);
        for (ResourceLocation dim : t.dimensions()) d.dimensions.add(dim.toString());
        t.vein().ifPresent(v -> {
            d.hasVein = true;
            d.vein.displayName = v.displayName().orElse("");
            d.vein.ampMin = v.amountMultiplierMin();
            d.vein.ampMax = v.amountMultiplierMax();
            d.vein.icon = v.icon().map(ResourceLocation::toString).orElse("");
            d.vein.iconCount = v.iconCount();
            v.placement().ifPresent(p -> {
                d.vein.hasPlacement = true;
                d.vein.salt = p.salt();
                d.vein.separation = p.separation();
                d.vein.spacing = p.spacing();
            });
            d.vein.finite = v.finite();
        });
        t.drilling().ifPresent(dr -> {
            d.hasDrilling = true;
            for (DepositType.DrillOutputSpec o : dr.outputs()) {
                d.drilling.outputs.add(new OutputEntry(o.item().toString(), o.count(), o.chance()));
            }
            d.drilling.ticks = dr.ticks();
            d.drilling.stress = dr.stress();
            d.drilling.drillTag = dr.drillTag().map(ResourceLocation::toString).orElse("");
        });
        return d;
    }

    static DepositType toType(Draft d) {
        List<DepositType.WeightedRecipe> recipes = new ArrayList<>();
        for (RecipeEntry e : d.veinRecipes) {
            ResourceLocation rl = ResourceLocation.tryParse(e.recipe == null ? "" : e.recipe.trim());
            // Require a non-empty path — "minecraft:" parses to a valid RL with an
            // empty path but is junk that resolves to no recipe (renders "infinite").
            if (rl != null && !rl.getPath().isEmpty() && e.weight > 0) {
                recipes.add(new DepositType.WeightedRecipe(rl, e.weight));
            }
        }

        List<DepositType.WeightedFiller> fillers = new ArrayList<>();
        for (Integer w : d.fillers) {
            if (w != null && w > 0) fillers.add(new DepositType.WeightedFiller(w));
        }

        List<TagKey<Biome>> biomes = new ArrayList<>();
        for (String s : d.biomeFilter) {
            String t = s.trim();
            if (t.startsWith("#")) t = t.substring(1);
            ResourceLocation rl = ResourceLocation.tryParse(t);
            if (rl != null) biomes.add(TagKey.create(Registries.BIOME, rl));
        }

        List<ResourceLocation> dimensions = new ArrayList<>();
        for (String s : d.dimensions) {
            ResourceLocation rl = ResourceLocation.tryParse(s.trim());
            if (rl != null) dimensions.add(rl);
        }

        Optional<DepositType.VeinSpec> vein = Optional.empty();
        if (d.hasVein) {
            VeinDraft v = d.vein;
            Optional<DepositType.PlacementSpec> placement = v.hasPlacement
                    ? Optional.of(new DepositType.PlacementSpec(v.salt, v.separation, v.spacing))
                    : Optional.empty();
            vein = Optional.of(new DepositType.VeinSpec(
                    v.displayName.isBlank() ? Optional.empty() : Optional.of(v.displayName),
                    v.ampMin, v.ampMax,
                    v.icon.isBlank() ? Optional.empty() : Optional.ofNullable(ResourceLocation.tryParse(v.icon.trim())),
                    v.iconCount, placement,
                    v.finite.isBlank() ? "always" : v.finite.trim()));
        }

        Optional<DepositType.DrillingSpec> drilling = Optional.empty();
        if (d.hasDrilling || !d.drilling.outputs.isEmpty()) {
            DrillingDraft dr = d.drilling;
            List<DepositType.DrillOutputSpec> outs = new ArrayList<>();
            for (OutputEntry o : dr.outputs) {
                ResourceLocation item = ResourceLocation.tryParse(o.item == null ? "" : o.item.trim());
                if (item == null) continue;
                outs.add(new DepositType.DrillOutputSpec(item, Math.max(1, o.count), o.chance));
            }
            Optional<ResourceLocation> drillTag = dr.drillTag.isBlank()
                    ? Optional.empty()
                    : Optional.ofNullable(ResourceLocation.tryParse(dr.drillTag.trim()));
            drilling = Optional.of(new DepositType.DrillingSpec(outs, dr.ticks, dr.stress, drillTag));
        }

        return new DepositType(
                recipes,
                d.veinRecipeInfinite.isBlank()
                        ? Optional.empty()
                        : Optional.ofNullable(ResourceLocation.tryParse(d.veinRecipeInfinite.trim())),
                fillers,
                d.replenishRatePerHour,
                d.placement,
                new DepositType.IntRange(d.distanceMin, d.distanceMax),
                new DepositType.IntRange(d.sizeMin, d.sizeMax),
                new DepositType.PerChunkUnits(d.unitsMin, d.unitsHasMax ? Optional.of(d.unitsMax) : Optional.empty()),
                d.weight,
                Optional.of(d.mapColor),
                biomes,
                d.overrideReveal ? Optional.of(d.reveal) : Optional.empty(),
                dimensions,
                vein,
                drilling);
    }
}
