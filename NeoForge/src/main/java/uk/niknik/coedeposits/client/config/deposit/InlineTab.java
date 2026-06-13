package uk.niknik.coedeposits.client.config.deposit;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.ItemControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;

import uk.niknik.coedeposits.client.DepositAuthoring.Draft;
import uk.niknik.coedeposits.client.FluidPickerScreen;

/**
 * The deposit detail editor's <b>Inline recipe (advanced)</b> tab: build a COE vein /
 * drilling / extracting recipe from scratch instead of referencing one. Most deposits leave
 * this off and just pick a Vein recipe on the Core tab.
 *
 * @param back back-target the Fluid picker returns to (it re-opens this whole detail screen)
 */
public final class InlineTab {
    private InlineTab() {}

    public static ConfigCategory build(Draft d, Screen back) {
        ConfigCategory.Builder cat = ConfigCategory.createBuilder()
                .name(Component.literal("Inline recipe (advanced)"))
                .tooltip(Component.literal("ADVANCED — optional. Most deposits just pick a ready 'Vein recipe' on the previous tab and leave this OFF. Use it only to BUILD a brand-new COE vein/drilling recipe from scratch. Inline recipes only work from the config file, not from datapack deposit types."));

        OptionGroup.Builder vein = OptionGroup.createBuilder().name(Component.literal("Vein (advanced — usually leave OFF)"));
        Option<Boolean> enableVein = Option.<Boolean>createBuilder()
                .name(Component.literal("Enable inline vein"))
                .description(OptionDescription.of(Component.literal("Build a COE vein recipe from the fields below instead of referencing one. Turn ON only if you did NOT set a Vein recipe.")))
                .binding(d.hasVein, () -> d.hasVein, v -> d.hasVein = v)
                .addListener(DepositBindings.persistOnChange(v -> d.hasVein = v))
                .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                .build();
        vein.option(enableVein);
        Option<String> displayName = Option.<String>createBuilder()
                .name(Component.literal("Display name"))
                .description(OptionDescription.of(Component.literal("Label COE's vein finder shows for this ore. Blank = derived.")))
                .binding(d.vein.displayName, () -> d.vein.displayName, v -> d.vein.displayName = v)
                .addListener(DepositBindings.persistOnChange(v -> d.vein.displayName = v))
                .controller(StringControllerBuilder::create)
                .build();
        vein.option(displayName);
        Option<Float> ampMin = Option.<Float>createBuilder()
                .name(Component.literal("Amount mult min"))
                .description(OptionDescription.of(Component.literal("Per-chunk vein size = this x COE's finiteAmountBase (createoreexcavation config, default 1000). So 2 = ~2000 resources at the low end. In MANAGED mode coedeposits sets this from 'Per-chunk units' (Core tab) automatically — only touch it for COE-placed veins.")))
                .binding(d.vein.ampMin, () -> d.vein.ampMin, v -> d.vein.ampMin = v)
                .addListener(DepositBindings.persistOnChange(v -> d.vein.ampMin = v))
                .controller(o -> FloatSliderControllerBuilder.create(o).range(0f, 50f).step(0.5f))
                .build();
        vein.option(ampMin);
        Option<Float> ampMax = Option.<Float>createBuilder()
                .name(Component.literal("Amount mult max"))
                .description(OptionDescription.of(Component.literal("High end of the per-chunk vein size: this x finiteAmountBase (default 1000). Bundled iron uses 10..30 -> 10000..30000 resources per chunk.")))
                .binding(d.vein.ampMax, () -> d.vein.ampMax, v -> d.vein.ampMax = v)
                .addListener(DepositBindings.persistOnChange(v -> d.vein.ampMax = v))
                .controller(o -> FloatSliderControllerBuilder.create(o).range(0f, 50f).step(0.5f))
                .build();
        vein.option(ampMax);
        Consumer<Item> iconSetter = item -> d.vein.icon = DepositBindings.itemToId(item);
        Option<Item> icon = Option.<Item>createBuilder()
                .name(Component.literal("Icon"))
                .description(OptionDescription.of(Component.literal("Item shown as this vein's icon in the finder / JEI. Empty (air) = first drill output.")))
                .binding(DepositBindings.itemFromId(d.vein.icon), () -> DepositBindings.itemFromId(d.vein.icon), iconSetter)
                .addListener(DepositBindings.persistOnChange(iconSetter))
                .controller(ItemControllerBuilder::create)
                .build();
        vein.option(icon);
        Option<Integer> iconCount = Option.<Integer>createBuilder()
                .name(Component.literal("Icon count"))
                .description(OptionDescription.of(Component.literal("Stack size drawn on the icon. Cosmetic.")))
                .binding(d.vein.iconCount, () -> d.vein.iconCount, v -> d.vein.iconCount = v)
                .addListener(DepositBindings.persistOnChange(v -> d.vein.iconCount = v))
                .controller(o -> IntegerSliderControllerBuilder.create(o).range(1, 64).step(1))
                .build();
        vein.option(iconCount);
        Consumer<Boolean> finiteSetter = b -> d.vein.finite = b ? "always" : "never";
        Option<Boolean> finite = Option.<Boolean>createBuilder()
                .name(Component.literal("Finite (depletes)"))
                .description(OptionDescription.of(Component.literal("ON = the vein depletes as you drill it (normal). OFF = an inexhaustible vein.")))
                .binding("always".equals(d.vein.finite), () -> "always".equals(d.vein.finite), finiteSetter)
                .addListener(DepositBindings.persistOnChange(finiteSetter))
                .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                .build();
        vein.option(finite);
        Option<Boolean> hasPlacement = Option.<Boolean>createBuilder()
                .name(Component.literal("Has placement (COE mode)"))
                .description(OptionDescription.of(Component.literal("Only for Placement = COE. Adds COE's grid-scatter settings below. Leave OFF for MANAGED deposits.")))
                .binding(d.vein.hasPlacement, () -> d.vein.hasPlacement, v -> d.vein.hasPlacement = v)
                .addListener(DepositBindings.persistOnChange(v -> d.vein.hasPlacement = v))
                .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                .build();
        vein.option(hasPlacement);
        Option<Integer> salt = Option.<Integer>createBuilder()
                .name(Component.literal("Salt"))
                .description(OptionDescription.of(Component.literal("Random seed offset so different ores don't share chunks. (Only with 'Has placement'.)")))
                .binding(d.vein.salt, () -> d.vein.salt, v -> d.vein.salt = v)
                .addListener(DepositBindings.persistOnChange(v -> d.vein.salt = v))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(Integer.MIN_VALUE).max(Integer.MAX_VALUE))
                .build();
        vein.option(salt);
        Option<Integer> separation = Option.<Integer>createBuilder()
                .name(Component.literal("Separation"))
                .description(OptionDescription.of(Component.literal("Min chunks between two veins of this ore. (Only with 'Has placement'.)")))
                .binding(d.vein.separation, () -> d.vein.separation, v -> d.vein.separation = v)
                .addListener(DepositBindings.persistOnChange(v -> d.vein.separation = v))
                .controller(o -> IntegerSliderControllerBuilder.create(o).range(1, 256).step(1))
                .build();
        vein.option(separation);
        Option<Integer> spacing = Option.<Integer>createBuilder()
                .name(Component.literal("Spacing"))
                .description(OptionDescription.of(Component.literal("Grid cell size in chunks, one vein per cell; >= Separation. (Only with 'Has placement'.)")))
                .binding(d.vein.spacing, () -> d.vein.spacing, v -> d.vein.spacing = v)
                .addListener(DepositBindings.persistOnChange(v -> d.vein.spacing = v))
                .controller(o -> IntegerSliderControllerBuilder.create(o).range(1, 512).step(1))
                .build();
        vein.option(spacing);
        cat.group(vein.build());

        OptionGroup.Builder drilling = OptionGroup.createBuilder().name(Component.literal("Drilling (used when you add outputs below)"));
        Option<Integer> drillTicks = Option.<Integer>createBuilder()
                .name(Component.literal("Ticks"))
                .description(OptionDescription.of(Component.literal("Drill cycle length in ticks (20 = 1 s). Lower = faster mining. Bundled = 100.")))
                .binding(d.drilling.ticks, () -> d.drilling.ticks, v -> d.drilling.ticks = v)
                .addListener(DepositBindings.persistOnChange(v -> d.drilling.ticks = v))
                .controller(o -> IntegerSliderControllerBuilder.create(o).range(1, 1200).step(5))
                .build();
        drilling.option(drillTicks);
        Option<Integer> drillStress = Option.<Integer>createBuilder()
                .name(Component.literal("Stress"))
                .description(OptionDescription.of(Component.literal("Create rotational stress the drill consumes. Bundled = ~256.")))
                .binding(d.drilling.stress, () -> d.drilling.stress, v -> d.drilling.stress = v)
                .addListener(DepositBindings.persistOnChange(v -> d.drilling.stress = v))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(1).max(1_000_000))
                .build();
        drilling.option(drillStress);
        Option<String> drillTag = Option.<String>createBuilder()
                .name(Component.literal("Drill tag"))
                .description(OptionDescription.of(Component.literal("Item tag of drills allowed to mine this. Blank = createoreexcavation:drills.")))
                .binding(d.drilling.drillTag, () -> d.drilling.drillTag, v -> d.drilling.drillTag = v)
                .addListener(DepositBindings.persistOnChange(v -> d.drilling.drillTag = v))
                .controller(o -> DropdownStringControllerBuilder.create(o).values(DepositBindings.itemTagIds).allowAnyValue(true))
                .build();
        drilling.option(drillTag);
        Option<Boolean> drillFluidIn = Option.<Boolean>createBuilder()
                .name(Component.literal("Requires input fluid"))
                .description(OptionDescription.of(Component.literal("Make the drill CONSUME a fluid per cycle (COE drillingFluid) pumped into the machine. Off for normal drills.")))
                .binding(d.drilling.hasFluidInput, () -> d.drilling.hasFluidInput, v -> d.drilling.hasFluidInput = v)
                .addListener(DepositBindings.persistOnChange(v -> d.drilling.hasFluidInput = v))
                .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                .build();
        drilling.option(drillFluidIn);
        ButtonOption drillFluidPick = ButtonOption.createBuilder()
                .name(Component.literal("Input fluid"))
                .description(OptionDescription.of(Component.literal("Fluid consumed per cycle — opens a picker. For a fluid TAG, set it as #namespace:path by hand in deposits.json.")))
                .text(Component.literal(DepositBindings.fluidLabel(d.drilling.fluidInput)))
                .action((s, o) -> Minecraft.getInstance().setScreen(new FluidPickerScreen(
                        s, d.drilling.fluidInput, picked -> {
                            d.drilling.fluidInput = picked;
                            d.drilling.hasFluidInput = true;
                            d.hasDrilling = true;  // ensure the drilling block (with fluid_input) is written
                            DepositBindings.persist();
                            Minecraft.getInstance().setScreen(
                                    DepositDetailScreen.create(d, back, DepositDetailScreen.INLINE_TAB));
                        })))
                .build();
        drilling.option(drillFluidPick);
        Option<Integer> drillFluidAmt = Option.<Integer>createBuilder()
                .name(Component.literal("Input fluid mB/cycle"))
                .description(OptionDescription.of(Component.literal("Millibuckets of the input fluid consumed per drill cycle. Default 1000 (one bucket).")))
                .binding(d.drilling.fluidInputAmount, () -> d.drilling.fluidInputAmount, v -> d.drilling.fluidInputAmount = v)
                .addListener(DepositBindings.persistOnChange(v -> d.drilling.fluidInputAmount = v))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(1).max(1_000_000))
                .build();
        drilling.option(drillFluidAmt);
        cat.group(drilling.build());

        ButtonOption outputs = ButtonOption.createBuilder()
                .name(Component.literal("Drilling outputs"))
                .description(OptionDescription.of(Component.literal("What the drill yields per cycle — each item has a count and a drop chance, rolled independently. Adding any output enables inline drilling. A guaranteed ore is chance 100%; chance-based slag is lower.")))
                .text(Component.literal("Edit →"))
                .action((s, o) -> Minecraft.getInstance().setScreen(DepositPickers.outputListScreen(s, d, DepositBindings::persist)))
                .build();
        cat.option(outputs);

        OptionGroup.Builder fluid = OptionGroup.createBuilder().name(Component.literal("Fluid (extractor — advanced)"));
        Option<Boolean> enableFluid = Option.<Boolean>createBuilder()
                .name(Component.literal("Enable fluid extraction"))
                .description(OptionDescription.of(Component.literal("Make this deposit yield a FLUID, harvested with COE's Extractor instead of the Drill. Synthesises a COE extracting recipe bound to this type's vein — set a Vein above too (placement). Leave OFF for solid ores; a deposit can also have BOTH (drill it for items or pump it for fluid).")))
                .binding(d.hasExtracting, () -> d.hasExtracting, v -> d.hasExtracting = v)
                .addListener(DepositBindings.persistOnChange(v -> d.hasExtracting = v))
                .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                .build();
        fluid.option(enableFluid);
        ButtonOption fluidPick = ButtonOption.createBuilder()
                .name(Component.literal("Fluid"))
                .description(OptionDescription.of(Component.literal("Source fluid produced. Opens a picker that shows fluids by their own texture — click one. Vanilla: water, lava, milk.")))
                .text(Component.literal(DepositBindings.fluidLabel(d.extracting.fluid)))
                .action((s, o) -> Minecraft.getInstance().setScreen(new FluidPickerScreen(
                        s, d.extracting.fluid, picked -> {
                            d.extracting.fluid = picked;
                            d.hasExtracting = true;
                            DepositBindings.persist();
                            Minecraft.getInstance().setScreen(
                                    DepositDetailScreen.create(d, back, DepositDetailScreen.INLINE_TAB));
                        })))
                .build();
        fluid.option(fluidPick);
        Option<Integer> fluidAmount = Option.<Integer>createBuilder()
                .name(Component.literal("Amount (mB / cycle)"))
                .description(OptionDescription.of(Component.literal("Millibuckets yielded per extraction cycle. COE's water extractor uses 500.")))
                .binding(d.extracting.amount, () -> d.extracting.amount, v -> d.extracting.amount = v)
                .addListener(DepositBindings.persistOnChange(v -> d.extracting.amount = v))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(1).max(1_000_000))
                .build();
        fluid.option(fluidAmount);
        Option<Integer> fluidTicks = Option.<Integer>createBuilder()
                .name(Component.literal("Ticks"))
                .description(OptionDescription.of(Component.literal("Extraction cycle length in ticks (20 = 1 s). Lower = faster pumping.")))
                .binding(d.extracting.ticks, () -> d.extracting.ticks, v -> d.extracting.ticks = v)
                .addListener(DepositBindings.persistOnChange(v -> d.extracting.ticks = v))
                .controller(o -> IntegerSliderControllerBuilder.create(o).range(1, 1200).step(5))
                .build();
        fluid.option(fluidTicks);
        Option<Integer> fluidStress = Option.<Integer>createBuilder()
                .name(Component.literal("Stress"))
                .description(OptionDescription.of(Component.literal("Create rotational stress the extractor consumes. COE bundled ≈ 256.")))
                .binding(d.extracting.stress, () -> d.extracting.stress, v -> d.extracting.stress = v)
                .addListener(DepositBindings.persistOnChange(v -> d.extracting.stress = v))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(1).max(1_000_000))
                .build();
        fluid.option(fluidStress);
        Option<String> fluidDrillTag = Option.<String>createBuilder()
                .name(Component.literal("Drill tag"))
                .description(OptionDescription.of(Component.literal("Item tag of drill heads the extractor accepts. Blank = createoreexcavation:drills.")))
                .binding(d.extracting.drillTag, () -> d.extracting.drillTag, v -> d.extracting.drillTag = v)
                .addListener(DepositBindings.persistOnChange(v -> d.extracting.drillTag = v))
                .controller(o -> DropdownStringControllerBuilder.create(o).values(DepositBindings.itemTagIds).allowAnyValue(true))
                .build();
        fluid.option(fluidDrillTag);
        Option<Boolean> exFluidIn = Option.<Boolean>createBuilder()
                .name(Component.literal("Requires input fluid"))
                .description(OptionDescription.of(Component.literal("Make the extractor also CONSUME a fluid per cycle (COE drillingFluid), separate from the fluid it produces. Off for a plain extractor.")))
                .binding(d.extracting.hasFluidInput, () -> d.extracting.hasFluidInput, v -> d.extracting.hasFluidInput = v)
                .addListener(DepositBindings.persistOnChange(v -> d.extracting.hasFluidInput = v))
                .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                .build();
        fluid.option(exFluidIn);
        ButtonOption exFluidPick = ButtonOption.createBuilder()
                .name(Component.literal("Input fluid"))
                .description(OptionDescription.of(Component.literal("Fluid consumed per cycle — opens a picker. For a fluid TAG, set it as #namespace:path by hand in deposits.json.")))
                .text(Component.literal(DepositBindings.fluidLabel(d.extracting.fluidInput)))
                .action((s, o) -> Minecraft.getInstance().setScreen(new FluidPickerScreen(
                        s, d.extracting.fluidInput, picked -> {
                            d.extracting.fluidInput = picked;
                            d.extracting.hasFluidInput = true;
                            d.hasExtracting = true;  // ensure the extracting block (with fluid_input) is written
                            DepositBindings.persist();
                            Minecraft.getInstance().setScreen(
                                    DepositDetailScreen.create(d, back, DepositDetailScreen.INLINE_TAB));
                        })))
                .build();
        fluid.option(exFluidPick);
        Option<Integer> exFluidAmt = Option.<Integer>createBuilder()
                .name(Component.literal("Input fluid mB/cycle"))
                .description(OptionDescription.of(Component.literal("Millibuckets of the input fluid consumed per extraction cycle. Default 1000 (one bucket).")))
                .binding(d.extracting.fluidInputAmount, () -> d.extracting.fluidInputAmount, v -> d.extracting.fluidInputAmount = v)
                .addListener(DepositBindings.persistOnChange(v -> d.extracting.fluidInputAmount = v))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(1).max(1_000_000))
                .build();
        fluid.option(exFluidAmt);
        cat.group(fluid.build());

        return cat.build();
    }
}
