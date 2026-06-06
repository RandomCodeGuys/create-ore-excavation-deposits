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

    /**
     * Common vanilla biome tags offered when no world is loaded (e.g. the editor opened from the
     * main menu), where the dynamic biome registry — and thus the real tag list — isn't available,
     * so the biome-filter dropdown would otherwise be empty and grey. In-world this is replaced by
     * the connection's full (incl. modded) tag set in {@link #gatherCandidates}; a pack-specific
     * tag not in this fallback is reachable by opening the editor in a world (the picker adds only
     * values from its candidate list, so a hand-typed off-list id in the menu won't take).
     */
    private static final List<String> FALLBACK_BIOME_TAGS = List.of(
            "minecraft:is_badlands", "minecraft:is_beach", "minecraft:is_forest", "minecraft:is_hill",
            "minecraft:is_jungle", "minecraft:is_mountain", "minecraft:is_ocean", "minecraft:is_deep_ocean",
            "minecraft:is_river", "minecraft:is_savanna", "minecraft:is_taiga");

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
            // 1.20.1: RecipeManager.getRecipes() yields Recipe<?> (getId()), not 1.21's RecipeHolder (id()).
            recipeIds = safe(() -> c.getRecipeManager().getRecipes().stream()
                    .map(h -> h.getId().toString()).sorted().toList());
            biomeTagIds = safe(() -> c.registryAccess().registryOrThrow(Registries.BIOME).getTagNames()
                    .map(t -> t.location().toString()).sorted().toList());
            dimensionIds = safe(() -> c.levels().stream()
                    .map(k -> k.location().toString()).sorted().toList());
        } else {
            // No world (e.g. editor opened from the main menu): recipes are pack-specific and can't
            // be guessed, but offer the common vanilla biome tags + the three default dimensions so
            // the biome/dimension filters stay usable instead of greying out.
            recipeIds = List.of();
            biomeTagIds = FALLBACK_BIOME_TAGS;
            dimensionIds = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
        }
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
