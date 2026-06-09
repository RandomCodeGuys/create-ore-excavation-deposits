package uk.niknik.coedeposits.client.config.general;

import java.util.Locale;

import net.minecraft.network.chat.Component;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;

import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.client.config.Bindings;

/** "Reveal" group of the General tab: default map-reveal policy and its radii. */
public final class RevealGroup {
    private RevealGroup() {}

    public static OptionGroup build() {
        OptionGroup.Builder group = OptionGroup.createBuilder()
                .name(Component.literal("Reveal"));

        Option<Config.RevealMode> revealMode = Option.<Config.RevealMode>createBuilder()
                .name(Component.literal("Reveal mode"))
                .description(OptionDescription.of(Component.literal("Default map-reveal policy when a type does not override `reveal`.")))
                .binding(Config.REVEAL_MODE.getDefault(), Config.REVEAL_MODE::get, v -> Bindings.persist(Config.REVEAL_MODE, v, "Reveal mode"))
                .controller(o -> EnumControllerBuilder.create(o).enumClass(Config.RevealMode.class)
                        .formatValue(e -> Component.literal(e.getSerializedName())))
                .build();
        group.option(revealMode);

        Option<Integer> proximity = Option.<Integer>createBuilder()
                .name(Component.literal("Proximity reveal blocks"))
                .description(OptionDescription.of(Component.literal("Block radius for ON_PROXIMITY reveal mode.")))
                .binding(Config.PROXIMITY_REVEAL_BLOCKS.getDefault(), Config.PROXIMITY_REVEAL_BLOCKS::get, v -> Bindings.persist(Config.PROXIMITY_REVEAL_BLOCKS, v, "Proximity reveal blocks"))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(16).max(100_000)
                        .formatValue(i -> Component.literal(String.format(Locale.ROOT, "%d", i))))
                .build();
        group.option(proximity);

        Option<Integer> discovery = Option.<Integer>createBuilder()
                .name(Component.literal("Discovery radius blocks"))
                .description(OptionDescription.of(Component.literal("Block radius checked for ON_DISCOVERY reveals each tick.")))
                .binding(Config.DISCOVERY_RADIUS_BLOCKS.getDefault(), Config.DISCOVERY_RADIUS_BLOCKS::get, v -> Bindings.persist(Config.DISCOVERY_RADIUS_BLOCKS, v, "Discovery radius blocks"))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(8).max(1_024)
                        .formatValue(i -> Component.literal(String.format(Locale.ROOT, "%d", i))))
                .build();
        group.option(discovery);

        Option<Boolean> discoveryChat = Option.<Boolean>createBuilder()
                .name(Component.literal("Discovery chat message"))
                .description(OptionDescription.of(Component.literal(
                        "Show a chat line when a deposit is discovered. Off = no chat (the map marker / Xaero waypoint still appear).")))
                .binding(Config.DISCOVERY_CHAT.getDefault(), Config.DISCOVERY_CHAT::get,
                        v -> Bindings.persist(Config.DISCOVERY_CHAT, v, "Discovery chat"))
                .controller(TickBoxControllerBuilder::create)
                .build();
        group.option(discoveryChat);

        Option<String> discoveryFormat = Option.<String>createBuilder()
                .name(Component.literal("Discovery message"))
                .description(OptionDescription.of(Component.literal(
                        "Template for the discovery chat line. Placeholders: %name% %pos% %x% %y% %z% %type% %%. § colour codes work.")))
                .binding(Config.DISCOVERY_MESSAGE_FORMAT.getDefault(), Config.DISCOVERY_MESSAGE_FORMAT::get,
                        v -> Bindings.persist(Config.DISCOVERY_MESSAGE_FORMAT, v, "Discovery message"))
                .controller(StringControllerBuilder::create)
                .build();
        group.option(discoveryFormat);

        return group.build();
    }
}
