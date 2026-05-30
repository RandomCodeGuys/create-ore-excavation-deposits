package uk.niknik.coedeposits.deposit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import com.tom.createores.CreateOreExcavation;
import com.tom.createores.recipe.DrillingRecipe;
import com.tom.createores.recipe.VeinRecipe;

/**
 * Static, side-effect-free sanity checks over the loaded deposit-type registry.
 * Surfaces the silent-failure classes that otherwise only show up as "nothing
 * generates" — empty recipe pools, degenerate budgets, missing vein recipes.
 *
 * <p>Two passes with different prerequisites:
 * <ul>
 *   <li>{@link #validateStructure} — pure config-shape checks, no recipe manager
 *       needed. Run at every reload in {@link DepositTypeLoader#apply} so server
 *       start and {@code /reload} both log problems.</li>
 *   <li>{@link #validateRecipes} — resolves each vein recipe id against the live
 *       recipe manager. Run once recipes are loaded (datapack sync / player join)
 *       so a missing or mistyped recipe is reported instead of the picker
 *       silently dropping the deposit at materialize time.</li>
 * </ul>
 *
 * <p>Neither pass mutates anything; callers decide how to surface the
 * {@link Issue}s (log, chat to ops, …).
 */
public final class DepositConfigValidator {
    private DepositConfigValidator() {}

    public enum Severity { ERROR, WARN }

    /**
     * One config problem found for a deposit type.
     *
     * @param typeId    the offending type
     * @param severity  ERROR = won't generate at all; WARN = generates but
     *                   likely not as intended
     * @param message   human-readable, already includes the offending value
     */
    public record Issue(ResourceLocation typeId, Severity severity, String message) {}

    /**
     * size_chunks.max above this is flagged as excessive. Set above the largest
     * bundled common-ore vein (coal = 40) so normal large veins (iron/copper 30,
     * quartz 25) don't warn — only genuinely degenerate sizes do.
     */
    private static final int SIZE_WARN_THRESHOLD = 48;

    /** Max issues rendered inline in the in-game op report before truncating. */
    private static final int CHAT_ISSUE_LIMIT = 6;

    /**
     * Shape-only checks — safe to run before recipes are loaded.
     *
     * @param types  the live registry snapshot
     * @return       issues found (empty when the config is clean)
     */
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

            // "Can this vein be mined at all" is a recipe-level question — a
            // standalone <id>_drilling recipe counts just as much as an inline
            // block — so it lives in validateRecipes. Here we only flag an inline
            // drilling block that was added but left with no outputs.
            if (t.drilling().isPresent() && t.drilling().get().outputs().isEmpty()) {
                issues.add(new Issue(id, Severity.WARN,
                        "inline drilling.outputs is empty — drilling yields no items"));
            }
        }
        return issues;
    }

    /**
     * Recipe-resolution checks — every vein recipe id in every type's pool must
     * resolve to a loaded COE {@link VeinRecipe}. Catches inline-vein synthesis
     * that didn't land and hand-typed {@code vein_recipes} ids that don't exist.
     *
     * @param types    the live registry snapshot
     * @param recipes  the loaded recipe manager (server side, post datapack load)
     * @return         issues for every unresolved / wrong-type recipe id
     */
    public static List<Issue> validateRecipes(Map<ResourceLocation, DepositType> types, RecipeManager recipes) {
        List<Issue> issues = new ArrayList<>();

        // Vein ids that have at least one drilling recipe bound (by veinId).
        // A vein with none generates but can never be mined. Mirrors COE's own
        // lookup in ExcavatingBlockEntity. Defensive try/catch: if COE's recipe
        // API shifts we skip this single check, not the whole validation pass.
        Set<ResourceLocation> mineable = Set.of();
        try {
            RecipeType<DrillingRecipe> drillType = CreateOreExcavation.DRILLING_RECIPES.getRecipeType();
            mineable = recipes.getAllRecipesFor(drillType).stream()
                    .map(h -> h.value().veinId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (Throwable ignored) {
            // COE drilling recipe type unavailable/changed — binding check skipped.
        }
        // Effectively-final snapshot for the lambda below.
        final Set<ResourceLocation> mineableVeins = mineable;

        for (Map.Entry<ResourceLocation, DepositType> e : types.entrySet()) {
            DepositType t = e.getValue();

            // (1) every vein recipe id must resolve to a loaded COE vein recipe.
            for (DepositType.WeightedRecipe wr : t.veinRecipes()) {
                boolean ok = recipes.byKey(wr.recipe())
                        .map(h -> h.value() instanceof VeinRecipe)
                        .orElse(false);
                if (!ok) {
                    issues.add(new Issue(e.getKey(), Severity.ERROR,
                            "vein recipe '" + wr.recipe() + "' is missing or not a COE vein recipe — "
                            + "the deposit is skipped at generation"));
                }
            }

            // (2) the vein needs ≥1 drilling recipe to be mineable — a standalone
            //     <id>_drilling recipe or a synthesised inline one both count.
            //     Skipped when mineableVeins is empty (COE check unavailable) so a
            //     false negative can't flag every type at once.
            if (!t.veinRecipes().isEmpty() && !mineableVeins.isEmpty()) {
                boolean canMine = t.veinRecipes().stream()
                        .anyMatch(wr -> mineableVeins.contains(wr.recipe()));
                if (!canMine) {
                    issues.add(new Issue(e.getKey(), Severity.WARN,
                            "no drilling recipe bound to its vein — generates but can't be mined "
                            + "(add a `drilling` block, or a drilling recipe with veinId pointing at the vein)"));
                }
            }
        }
        return issues;
    }

    /**
     * Render an issue list as a compact multi-line chat report for ops. Errors
     * are red, warnings gold; the list is capped at {@link #CHAT_ISSUE_LIMIT}
     * with a "…and N more (see server log)" footer.
     *
     * @param issues  non-empty issue list (caller guards the empty case)
     * @return         a single {@link Component} ready for {@code sendSystemMessage}
     */
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
