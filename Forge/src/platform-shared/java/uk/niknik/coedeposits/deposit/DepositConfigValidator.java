package uk.niknik.coedeposits.deposit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import com.tom.createores.CreateOreExcavation;
import com.tom.createores.recipe.DrillingRecipe;
import com.tom.createores.recipe.ExtractorRecipe;
import com.tom.createores.recipe.VeinRecipe;

/**
 * Static, side-effect-free sanity checks over the loaded deposit-type registry.
 *
 * <p><b>1.20.1 line:</b> identical to the 1.21.1 source except the recipe lookups
 * use the recipe directly — {@code RecipeManager.byKey} /
 * {@code getAllRecipesFor} return the {@code Recipe} (not a {@code RecipeHolder}),
 * so there is no {@code .value()} unwrap.
 */
public final class DepositConfigValidator {
    private DepositConfigValidator() {}

    public enum Severity { ERROR, WARN }

    /** One config problem found for a deposit type. */
    public record Issue(ResourceLocation typeId, Severity severity, String message) {}

    private static final int SIZE_WARN_THRESHOLD = 48;
    private static final int CHAT_ISSUE_LIMIT = 6;

    /** Shape-only checks — safe to run before recipes are loaded. */
    public static List<Issue> validateStructure(Map<ResourceLocation, DepositType> types) {
        List<Issue> issues = new ArrayList<>();
        for (Map.Entry<ResourceLocation, DepositType> e : types.entrySet()) {
            ResourceLocation id = e.getKey();
            DepositType t = e.getValue();

            if (t.veinRecipes().isEmpty()) {
                issues.add(new Issue(id, Severity.ERROR,
                        "no vein recipe — add an inline `vein` block or a `vein_recipes` entry; this deposit will never generate"));
            }
            if (t.weight() <= 0) {
                issues.add(new Issue(id, Severity.ERROR,
                        "weight=" + t.weight() + " — never selected; use a positive weight"));
            }
            if (t.dimensions().isEmpty()) {
                issues.add(new Issue(id, Severity.ERROR,
                        "no dimensions — never generates; list at least one dimension id"));
            }

            DepositType.PerChunkUnits u = t.perChunkUnits();
            if (u.min() <= 0 && u.max().isEmpty()) {
                issues.add(new Issue(id, Severity.WARN,
                        "per_chunk_units.min=" + u.min() + " with no max — budget collapses to the recipe floor "
                        + "(≈amountMultiplierMin × finiteAmountBase); set min>0 or add a max"));
            }
            if (t.sizeChunks().max() > SIZE_WARN_THRESHOLD) {
                issues.add(new Issue(id, Severity.WARN,
                        "size_chunks.max=" + t.sizeChunks().max() + " — very large blobs (RAM + overlap churn); 4–12 is typical"));
            }

            if (t.drilling().isPresent() && t.drilling().get().outputs().isEmpty()) {
                issues.add(new Issue(id, Severity.WARN,
                        "inline drilling.outputs is empty — drilling yields no items"));
            }
        }
        return issues;
    }

    /**
     * Recipe-resolution checks — every vein recipe id must resolve to a loaded COE
     * {@link VeinRecipe}; each vein needs a drilling OR extracting recipe to be
     * harvestable.
     */
    public static List<Issue> validateRecipes(Map<ResourceLocation, DepositType> types, RecipeManager recipes) {
        List<Issue> issues = new ArrayList<>();

        // Vein ids that have at least one drilling OR extracting recipe bound (by veinId).
        // 1.20.1: getAllRecipesFor returns the recipes directly (no RecipeHolder).
        Set<ResourceLocation> harvestable = new HashSet<>();
        boolean harvestCheckRan = false;
        try {
            RecipeType<DrillingRecipe> drillType = CreateOreExcavation.DRILLING_RECIPES.getRecipeType();
            recipes.getAllRecipesFor(drillType).stream()
                    .map(r -> r.veinId).filter(Objects::nonNull)
                    .forEach(harvestable::add);
            harvestCheckRan = true;
        } catch (Throwable ignored) {
            // COE drilling recipe type unavailable/changed.
        }
        try {
            RecipeType<ExtractorRecipe> extractType = CreateOreExcavation.EXTRACTING_RECIPES.getRecipeType();
            recipes.getAllRecipesFor(extractType).stream()
                    .map(r -> r.veinId).filter(Objects::nonNull)
                    .forEach(harvestable::add);
            harvestCheckRan = true;
        } catch (Throwable ignored) {
            // COE extracting recipe type unavailable/changed.
        }
        final Set<ResourceLocation> harvestableVeins = harvestable;
        final boolean harvestChecked = harvestCheckRan;

        for (Map.Entry<ResourceLocation, DepositType> e : types.entrySet()) {
            DepositType t = e.getValue();

            // (1) every vein recipe id must resolve to a loaded COE vein recipe.
            for (DepositType.WeightedRecipe wr : t.veinRecipes()) {
                boolean ok = recipes.byKey(wr.recipe())
                        .map(r -> r instanceof VeinRecipe)
                        .orElse(false);
                if (!ok) {
                    issues.add(new Issue(e.getKey(), Severity.ERROR,
                            "vein recipe '" + wr.recipe() + "' is missing or not a COE vein recipe — "
                            + "the deposit is skipped at generation"));
                }
            }

            // (2) the vein needs ≥1 drilling OR extracting recipe to be harvestable.
            if (!t.veinRecipes().isEmpty() && harvestChecked) {
                boolean canHarvest = t.veinRecipes().stream()
                        .anyMatch(wr -> harvestableVeins.contains(wr.recipe()));
                if (!canHarvest) {
                    issues.add(new Issue(e.getKey(), Severity.WARN,
                            "no drilling or extracting recipe bound to its vein — generates but can't be "
                            + "harvested (add a `drilling` or `fluid` block, or a recipe with veinId pointing "
                            + "at the vein)"));
                }
            }
        }
        return issues;
    }

    /** Render an issue list as a compact multi-line chat report for ops. */
    public static Component toComponent(List<Issue> issues) {
        long errors = issues.stream().filter(i -> i.severity() == Severity.ERROR).count();
        Component header = Component.literal(
                "[coedeposits] " + issues.size() + " config issue(s)"
                + (errors > 0 ? " (" + errors + " blocking generation)" : "") + ":")
                .withStyle(errors > 0 ? ChatFormatting.RED : ChatFormatting.GOLD);
        net.minecraft.network.chat.MutableComponent out = Component.empty().append(header);

        int shown = Math.min(issues.size(), CHAT_ISSUE_LIMIT);
        for (int i = 0; i < shown; i++) {
            Issue issue = issues.get(i);
            ChatFormatting colour = issue.severity() == Severity.ERROR ? ChatFormatting.RED : ChatFormatting.GOLD;
            out.append(Component.literal("\n  • " + issue.typeId() + ": ").withStyle(colour))
               .append(Component.literal(issue.message()).withStyle(ChatFormatting.GRAY));
        }
        if (issues.size() > shown) {
            out.append(Component.literal("\n  …and " + (issues.size() - shown) + " more (see server log)")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return out;
    }
}
