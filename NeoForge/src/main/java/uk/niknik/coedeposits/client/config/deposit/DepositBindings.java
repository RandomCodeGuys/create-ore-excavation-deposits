package uk.niknik.coedeposits.client.config.deposit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import dev.isxander.yacl3.api.OptionEventListener;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.client.DepositAuthoring;
import uk.niknik.coedeposits.client.DepositAuthoring.Draft;
import uk.niknik.coedeposits.deposit.DepositType;

/**
 * Shared state + the write-through binding for the deposit editor — the deposit-side
 * analogue of {@link uk.niknik.coedeposits.client.config.Bindings}. Holds the working
 * draft list and the autocomplete candidate pools, persists them to deposits.json, and
 * exposes {@link #persistOnChange} — the auto-save listener every editor field hangs on.
 *
 * <p>The actual screens live in sibling files ({@link DepositListScreen}, {@link CoreTab},
 * {@link InlineTab}, {@link DepositPickers}); this class is purely the data hub they share,
 * so each of them stays a flat list of explicit {@code Option} declarations.
 */
public final class DepositBindings {
    private DepositBindings() {}

    /** Working set for the current editor session; (re)loaded by {@link #reload}. */
    public static List<Draft> drafts = new ArrayList<>();

    // Autocomplete candidate pools, refreshed by reload() from client state.
    public static List<String> recipeIds = List.of();
    public static List<String> itemTagIds = List.of();
    public static List<String> biomeTagIds = List.of();
    public static List<String> dimensionIds = List.of();
    /** All COE vein-recipe ids known to the client — superset; {@link #adoptableVeinIds} filters it. */
    public static List<String> coeVeinIds = List.of();

    /** UI choice for reveal: DEFAULT means "use the global setting" (no per-type override). */
    public enum RevealChoice implements StringRepresentable {
        DEFAULT, ALWAYS, ON_DISCOVERY, ON_PROXIMITY, ON_PROSPECT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static RevealChoice fromMode(Config.RevealMode mode) {
            return RevealChoice.valueOf(mode.name());
        }

        public Config.RevealMode toMode() {
            return Config.RevealMode.valueOf(name());
        }
    }

    /**
     * Reload drafts + autocomplete pools for a fresh editor session. Called once when the
     * host config screen first opens — not on the internal rebuilds that follow an
     * add/disable/delete, which keep the already-loaded, just-persisted drafts.
     */
    public static void reload() {
        drafts = DepositAuthoring.loadMerged();
        gatherCandidates();
    }

    /** Write the working drafts to deposits.json. */
    public static void persist() {
        DepositAuthoring.save(drafts);
    }

    /**
     * Write-through listener: on a value change, push the pending value into the draft via
     * {@code setter} and persist the file. Filtered to STATE_CHANGE so it doesn't fire while
     * the screen is being built. Every editor field carries one (paired with a binding whose
     * setter is the same lambda, so YACL's own apply path stays consistent).
     */
    public static <T> OptionEventListener<T> persistOnChange(Consumer<T> setter) {
        return (opt, event) -> {
            if (event == OptionEventListener.Event.STATE_CHANGE) {
                setter.accept(opt.pendingValue());
                persist();
            }
        };
    }

    /** A fresh deposit pre-filled with values that actually generate, under a guaranteed-unique id. */
    public static Draft newDraft() {
        Draft d = new Draft();
        d.id = uniqueNewId();
        d.weight = 100;
        d.sizeMin = 4;
        d.sizeMax = 12;
        d.unitsMin = 20_000;
        d.unitsHasMax = true;
        d.unitsMax = 120_000;
        return d;
    }

    /**
     * {@code coedeposits:new_N} one past the highest existing {@code new_N} — never collides with
     * an existing id (deposits.json is keyed by id, so a duplicate would silently overwrite). Using
     * {@code drafts.size()+1} previously clashed after deletes, producing two identical ids.
     */
    private static String uniqueNewId() {
        String prefix = "coedeposits:new_";
        int max = 0;
        for (Draft d : drafts) {
            if (d.id != null && d.id.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(d.id.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // a renamed/non-numeric suffix — doesn't participate in the counter
                }
            }
        }
        return prefix + (max + 1);
    }

    public static Item itemFromId(String id) {
        if (id == null || id.isBlank()) return Items.AIR;
        ResourceLocation rl = ResourceLocation.tryParse(id.trim());
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }

    public static String itemToId(Item item) {
        return item == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** Friendly label for the Fluid button: the fluid's display name + id, or a prompt when unset. */
    public static String fluidLabel(String id) {
        if (id == null || id.isBlank()) return "Pick →";
        ResourceLocation rl = ResourceLocation.tryParse(id.trim());
        if (rl == null || !BuiltInRegistries.FLUID.containsKey(rl)) return id;
        return BuiltInRegistries.FLUID.get(rl).getFluidType().getDescription().getString() + "  §8(" + id + ")";
    }

    private static void gatherCandidates() {
        itemTagIds = safe(() -> BuiltInRegistries.ITEM.getTagNames()
                .map(t -> t.location().toString()).sorted().toList());

        ClientPacketListener c = Minecraft.getInstance().getConnection();
        if (c != null) {
            recipeIds = safe(() -> c.getRecipeManager().getRecipes().stream()
                    .map(h -> h.id().toString()).sorted().toList());
            // COE vein recipes only — the pool of potentially-adoptable foreign veins.
            coeVeinIds = safe(() -> c.getRecipeManager().getRecipes().stream()
                    .filter(h -> h.value() instanceof com.tom.createores.recipe.VeinRecipe)
                    .map(h -> h.id().toString()).sorted().toList());
            biomeTagIds = safe(() -> c.registryAccess().registryOrThrow(Registries.BIOME).getTagNames()
                    .map(t -> t.location().toString()).sorted().toList());
            dimensionIds = safe(() -> c.levels().stream()
                    .map(k -> k.location().toString()).sorted().toList());
        } else {
            recipeIds = List.of();
            coeVeinIds = List.of();
            biomeTagIds = List.of();
            dimensionIds = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
        }
    }

    /**
     * COE vein recipes that no current draft references — i.e. veins that would be
     * auto-<b>adopted</b> (no deposit_type of their own). These are surfaced in the
     * Deposits tab so they can be customised. Sorted by id.
     */
    public static List<String> adoptableVeinIds() {
        java.util.Set<String> referenced = new java.util.HashSet<>();
        for (Draft d : drafts) {
            for (DepositAuthoring.RecipeEntry e : d.veinRecipes) {
                if (e.recipe != null && !e.recipe.isBlank()) referenced.add(e.recipe.trim());
            }
            if (d.veinRecipeInfinite != null && !d.veinRecipeInfinite.isBlank()) {
                referenced.add(d.veinRecipeInfinite.trim());
            }
            // Inline-vein types synthesise a <id>_vein recipe; the editor draft's
            // veinRecipes is empty for them (load() doesn't bind inline veins), so
            // add it explicitly — otherwise a type's own synthesised vein would
            // wrongly appear as an adoptable foreign vein.
            if (d.hasVein && d.id != null && !d.id.isBlank()) {
                ResourceLocation rid = ResourceLocation.tryParse(d.id.trim());
                if (rid != null) referenced.add(rid.getNamespace() + ":" + rid.getPath() + "_vein");
            }
        }
        List<String> out = new ArrayList<>();
        for (String v : coeVeinIds) {
            if (!referenced.contains(v)) out.add(v);
        }
        return out;
    }

    /**
     * Build a draft that customises an adopted foreign vein: a {@code placement=coe}
     * type whose <b>id equals the vein id</b>, so already-placed adopted deposits
     * (whose typeId is the vein id) seamlessly upgrade to this declared type once it
     * loads. Seeded with the same hash colour the adopted vein already showed.
     */
    public static Draft adoptDraft(String veinId) {
        Draft d = new Draft();
        d.id = veinId;
        d.placement = DepositType.Placement.COE;
        d.veinRecipes.add(new DepositAuthoring.RecipeEntry(veinId, 1));
        d.hasMapColor = true;
        d.mapColor = defaultColorFor(veinId);
        return d;
    }

    /** Hash colour matching the world-map renderer's fallback, so customising doesn't change the marker colour. */
    private static int defaultColorFor(String id) {
        int h = id.hashCode();
        int r = 80 + ((h >>> 16) & 0x7F);
        int g = 80 + ((h >>> 8) & 0x7F);
        int b = 80 + (h & 0x7F);
        return (r << 16) | (g << 8) | b;
    }

    /** Compact display label for a vein id: drop COE's {@code ore_vein_type/} folder + trailing {@code _vein}. */
    public static String shortVeinLabel(String veinId) {
        String s = veinId;
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(0, s.indexOf(':') + 1) + s.substring(slash + 1);
        if (s.endsWith("_vein")) s = s.substring(0, s.length() - "_vein".length());
        return s;
    }

    private static List<String> safe(Supplier<List<String>> src) {
        try {
            return src.get();
        } catch (Exception e) {
            Coedeposits.LOGGER.warn("[coedeposits] editor: failed to gather suggestions: {}", e.toString());
            return List.of();
        }
    }
}
