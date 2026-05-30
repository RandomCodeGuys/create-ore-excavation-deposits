package uk.niknik.coedeposits.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.neoforged.neoforge.common.ModConfigSpec;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;

import uk.niknik.coedeposits.ClientConfig;
import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;

/**
 * Optional YACL-backed config screen. Built only when YACL is installed (see
 * {@link uk.niknik.coedeposits.CoedepositsClient#registerConfigScreen}); binds
 * every {@link Config} (COMMON) and {@link ClientConfig} (CLIENT) value to a
 * YACL option whose getter/setter read/write the same {@link ModConfigSpec}
 * entry. YACL is therefore purely a nicer front-end over the existing
 * toml-backed config — it keeps no storage of its own, and saving goes straight
 * back through {@code SPEC.save()}.
 *
 * <p><b>Loader note.</b> Nothing here is NeoForge-specific except the
 * {@code ModConfigSpec} types being bound; with Forge Config API Port the same
 * specs exist on Fabric, so this class moves to the common module unchanged and
 * becomes the <em>primary</em> screen on Fabric (reached via ModMenu), where
 * there is no native {@code ConfigurationScreen}.
 *
 * <p><b>COMMON caveat.</b> Editing COMMON values here writes the client's local
 * {@code coedeposits-common.toml}; on a dedicated server the authoritative copy
 * is the server's, exactly as with NeoForge's native screen.
 */
public final class YaclConfigScreen {
    private YaclConfigScreen() {}

    /** Build the YACL screen with {@code parent} as the back-button target. */
    public static Screen create(Screen parent) {
        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Coedeposits"))
                .category(generationCategory())
                .category(revealCategory())
                .category(loggingCategory())
                .category(clientCategory())
                .category(depositsLauncher())
                .save(() -> {
                    Config.SPEC.save();
                    ClientConfig.SPEC.save();
                })
                .build()
                .generateScreen(parent);
    }

    // ── categories ──────────────────────────────────────────────────────────

    private static ConfigCategory generationCategory() {
        return ConfigCategory.createBuilder()
                .name(Component.literal("Generation"))
                .option(dbl("Base radius", "Base radius (blocks) for the distance-gradient log curve.",
                        Config.BASE_RADIUS, 1.0, 1_000_000.0, 0))
                .option(dbl("Max radius", "Saturation radius (blocks): tier ~= 1.0 at and beyond this distance.",
                        Config.MAX_RADIUS, 1.0, 10_000_000.0, 0))
                .option(scaledDbl("Core spawn (per 100k chunks)",
                        "Deposit-core candidate rolls per 100,000 chunks. Default 50 (= 0.0005/chunk, ~1 per 2000). "
                                + "Higher = denser maps (500 ≈ 0.005); lower = rarer (10 ≈ 0.0001). Whole numbers "
                                + "so fine probabilities stay editable.",
                        Config.CORE_SPAWN_PROBABILITY, 100_000.0, 0, 100_000))
                .option(scaledDbl("Edge richness (%)",
                        "Outer-chunk richness as a percentage of the core's amountMul. Default 30 (= 0.30).",
                        Config.EDGE_AMOUNT_MUL, 100.0, 0, 100))
                .option(dbl("Unbounded growth", "Far-deposit scaling used when a type's per_chunk_units.max is absent.",
                        Config.UNBOUNDED_GROWTH, 0.0, 100_000.0, 1))
                .group(OptionGroup.createBuilder()
                        .name(Component.literal("Prospect scan"))
                        .option(intOpt("Prospect radius", "Block radius pre-scanned around spawn on server start (0 = off).",
                                Config.PROSPECT_RADIUS, 0, 16_000))
                        .option(intOpt("Chunks per tick", "Max pending deposit placements materialized per server tick.",
                                Config.PROSPECT_CHUNKS_PER_TICK, 16, 4_096))
                        .build())
                .option(dimensionsButton())
                .build();
    }

    private static ConfigCategory revealCategory() {
        return ConfigCategory.createBuilder()
                .name(Component.literal("Reveal"))
                .option(enumOpt("Reveal mode", "Default map-reveal policy when a type does not override `reveal`.",
                        Config.REVEAL_MODE, Config.RevealMode.class))
                .option(intOpt("Proximity reveal blocks", "Block radius for ON_PROXIMITY reveal mode.",
                        Config.PROXIMITY_REVEAL_BLOCKS, 16, 100_000))
                .option(intOpt("Discovery radius blocks", "Block radius checked for ON_DISCOVERY reveals each tick.",
                        Config.DISCOVERY_RADIUS_BLOCKS, 8, 1_024))
                .build();
    }

    private static ConfigCategory loggingCategory() {
        return ConfigCategory.createBuilder()
                .name(Component.literal("Logging"))
                .option(bool("Placement", "Log each new deposit placement.", Config.LOG_PLACEMENT))
                .option(bool("Discovery", "Log per-player reveals (walk-into / vein-finder).", Config.LOG_DISCOVERY))
                .option(bool("Depletion", "Log when a chunk's vein is fully extracted.", Config.LOG_DEPLETION))
                .option(bool("Replenish actions", "Log every replenish unit-restore (very noisy).", Config.LOG_REPLENISH_ACTIONS))
                .option(bool("Scan summary", "Log a one-line summary per prospect-scan job.", Config.LOG_SCAN_SUMMARY))
                .option(bool("Scan rejections", "Log per-chunk reasons a scanned chunk placed nothing (very noisy).", Config.LOG_SCAN_REJECTIONS))
                .option(bool("Lifecycle", "Log mod lifecycle events (picker install, types loaded, defaults written).", Config.LOG_LIFECYCLE))
                .build();
    }

    private static ConfigCategory clientCategory() {
        return ConfigCategory.createBuilder()
                .name(Component.literal("Client"))
                .option(bool("Map button enabled", "Show the 'Deposits: ON/OFF' button on Xaero's world map.",
                        ClientConfig.MAP_BUTTON_ENABLED))
                .option(enumOpt("Map button anchor", "Screen edge the toggle widget anchors to.",
                        ClientConfig.MAP_BUTTON_ANCHOR, ClientConfig.Anchor.class))
                .option(intOpt("Map button X offset", "Pixels from the anchored edge to the widget.",
                        ClientConfig.MAP_BUTTON_X_OFFSET, 0, 4_000))
                .option(intOpt("Map button Y offset", "Pixels from the top edge of the map screen to the widget.",
                        ClientConfig.MAP_BUTTON_Y_OFFSET, 0, 2_000))
                .option(bool("Log client sync", "Log when the client cache receives a deposit sync packet.",
                        ClientConfig.LOG_CLIENT_SYNC))
                .build();
    }

    /** A one-button category that launches the master/detail deposit editor. */
    private static ConfigCategory depositsLauncher() {
        return ConfigCategory.createBuilder()
                .name(Component.literal("Deposits"))
                .option(ButtonOption.createBuilder()
                        .name(Component.literal("Open deposit editor"))
                        .description(OptionDescription.of(Component.literal(
                                "Add and edit deposit types, written to config/coedeposits/deposits.json. "
                                        + "A config builder — does not touch the running world.")))
                        .text(Component.literal("Open →"))
                        .action((screen, opt) -> DepositEditorScreens.open(screen))
                        .build())
                .build();
    }

    // ── option helpers (bind each YACL Option straight to its ModConfigSpec value)

    /**
     * Binding setter shared by every option helper. Writes the value to the
     * ModConfigSpec AND flushes to disk immediately — so persistence no longer
     * depends on YACL's separate {@code .save()} callback running after the
     * apply phase. The log line confirms the binding actually fires with the
     * edited value (diagnostic for "values don't save").
     */
    private static <T> void persist(ModConfigSpec.ConfigValue<T> v, T val, String name) {
        if (Config.LOG_LIFECYCLE.get()) {
            Coedeposits.LOGGER.info("[coedeposits] config '{}' applied -> {}", name, val);
        }
        v.set(val);
        v.save();
    }

    private static Option<Double> dbl(String name, String desc, ModConfigSpec.DoubleValue v,
                                      double min, double max, int decimals) {
        return Option.<Double>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(v.getDefault(), v::get, val -> persist(v, val, name))
                .controller(opt -> DoubleFieldControllerBuilder.create(opt)
                        .min(min).max(max)
                        // YACL's default double controller shows only 2 decimals,
                        // which collapses small values like core_spawn_probability
                        // (0.0005) to "0.00" and blocks finer edits. Format each
                        // value with the precision it actually needs. Locale.ROOT
                        // forces a '.' separator: YACL's field parser is period-
                        // based, so on a comma-locale client a formatted "0,0005"
                        // fails to parse and reset / edit silently no-ops.
                        .formatValue(d -> Component.literal(
                                String.format(java.util.Locale.ROOT, "%." + decimals + "f", d))))
                .build();
    }

    /**
     * Bind an Integer YACL field to a {@link ModConfigSpec.DoubleValue} through a
     * {@code scale} factor: the user edits whole numbers ({@code value × scale})
     * while the config keeps the fine double. Sidesteps {@link DoubleFieldControllerBuilder}
     * entirely — YACL's number field parses via a default-locale {@link java.text.NumberFormat}
     * capped at 3 fraction digits, so a value like {@code core_spawn_probability=0.0005}
     * (4 decimals) is literally unenterable there. As an integer ({@code 0.0005 × 100000 = 50})
     * it is trivially editable and locale-proof.
     *
     * @param scale  multiplier mapping config double → editable integer (e.g. 100000)
     * @param min    minimum in scaled (integer) units
     * @param max    maximum in scaled (integer) units
     */
    private static Option<Integer> scaledDbl(String name, String desc, ModConfigSpec.DoubleValue v,
                                             double scale, int min, int max) {
        return Option.<Integer>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding((int) Math.round(v.getDefault() * scale),
                        () -> (int) Math.round(v.get() * scale),
                        scaled -> persist(v, scaled / scale, name))
                .controller(opt -> IntegerFieldControllerBuilder.create(opt).min(min).max(max)
                        .formatValue(i -> Component.literal(String.format(java.util.Locale.ROOT, "%d", i))))
                .build();
    }

    private static Option<Integer> intOpt(String name, String desc, ModConfigSpec.IntValue v, int min, int max) {
        return Option.<Integer>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(v.getDefault(), v::get, val -> persist(v, val, name))
                .controller(opt -> IntegerFieldControllerBuilder.create(opt).min(min).max(max)
                        // Plain digits, no locale grouping ("2000" not "2 000"):
                        // a grouped/spaced display can break YACL's field parser on
                        // re-validation and silently revert the edit — same failure
                        // class as the double fields' comma separator.
                        .formatValue(i -> Component.literal(String.format(java.util.Locale.ROOT, "%d", i))))
                .build();
    }

    private static Option<Boolean> bool(String name, String desc, ModConfigSpec.BooleanValue v) {
        return Option.<Boolean>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(v.getDefault(), v::get, val -> persist(v, val, name))
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .build();
    }

    private static <E extends Enum<E> & StringRepresentable> Option<E> enumOpt(
            String name, String desc, ModConfigSpec.EnumValue<E> v, Class<E> cls) {
        return Option.<E>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(desc)))
                .binding(v.getDefault(), v::get, val -> persist(v, val, name))
                .controller(opt -> EnumControllerBuilder.create(opt)
                        .enumClass(cls)
                        .formatValue(e -> Component.literal(e.getSerializedName())))
                .build();
    }

    /** Working copy of enabled_dimensions while its sub-editor is open. */
    private static List<String> workingDims = new ArrayList<>();

    /**
     * Button opening a click-to-edit sub-screen for {@code enabled_dimensions}
     * (dropdown per entry + add/remove), writing back to the ModConfigSpec list.
     * Uses the shared {@link DepositEditorScreens#stringListScreen} so entries
     * are clickable (unlike an inline ListOption dropdown).
     */
    private static Option<?> dimensionsButton() {
        return ButtonOption.createBuilder()
                .name(Component.literal("Enabled dimensions"))
                .description(OptionDescription.of(Component.literal(
                        "Namespaced dimension ids where coedeposits runs managed/COE placement and map tracking.")))
                .text(Component.literal("Edit →"))
                .action((screen, opt) -> {
                    workingDims = new ArrayList<>(Config.ENABLED_DIMENSIONS.get());
                    Minecraft.getInstance().setScreen(DepositEditorScreens.stringListScreen(
                            screen, "Enabled dimensions", workingDims, dimIds(),
                            "Dimension", "A dimension id, e.g. minecraft:overworld.", "minecraft:overworld",
                            () -> {
                                Config.ENABLED_DIMENSIONS.set(workingDims);
                                Config.SPEC.save();
                            }));
                })
                .build();
    }

    /** Known dimension ids for the dropdown (from the active connection, else vanilla). */
    private static List<String> dimIds() {
        try {
            var c = Minecraft.getInstance().getConnection();
            if (c != null) {
                return c.levels().stream().map(k -> k.location().toString()).sorted().toList();
            }
        } catch (Exception ignored) {
            // fall through to defaults
        }
        return List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
    }
}
