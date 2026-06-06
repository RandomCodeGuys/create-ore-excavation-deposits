package uk.niknik.coedeposits.client.config.general;

import java.util.Locale;

import net.minecraft.network.chat.Component;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;

import uk.niknik.coedeposits.ClientConfig;
import uk.niknik.coedeposits.client.config.Bindings;

/** "Client" group of the General tab: Xaero map-button placement + client-side logging. */
public final class ClientGroup {
    private ClientGroup() {}

    public static OptionGroup build() {
        OptionGroup.Builder group = OptionGroup.createBuilder()
                .name(Component.literal("Client"));

        group.option(Bindings.toggle("Map button enabled", "Show the 'Deposits: ON/OFF' button on Xaero's world map.", ClientConfig.MAP_BUTTON_ENABLED));

        Option<ClientConfig.Anchor> anchor = Option.<ClientConfig.Anchor>createBuilder()
                .name(Component.literal("Map button anchor"))
                .description(OptionDescription.of(Component.literal("Screen edge the toggle widget anchors to.")))
                .binding(ClientConfig.MAP_BUTTON_ANCHOR.getDefault(), ClientConfig.MAP_BUTTON_ANCHOR::get, v -> Bindings.persist(ClientConfig.MAP_BUTTON_ANCHOR, v, "Map button anchor"))
                .controller(o -> EnumControllerBuilder.create(o).enumClass(ClientConfig.Anchor.class)
                        .formatValue(e -> Component.literal(e.getSerializedName())))
                .build();
        group.option(anchor);

        Option<Integer> xOffset = Option.<Integer>createBuilder()
                .name(Component.literal("Map button X offset"))
                .description(OptionDescription.of(Component.literal("Pixels from the anchored edge to the widget.")))
                .binding(ClientConfig.MAP_BUTTON_X_OFFSET.getDefault(), ClientConfig.MAP_BUTTON_X_OFFSET::get, v -> Bindings.persist(ClientConfig.MAP_BUTTON_X_OFFSET, v, "Map button X offset"))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(0).max(4_000)
                        .formatValue(i -> Component.literal(String.format(Locale.ROOT, "%d", i))))
                .build();
        group.option(xOffset);

        Option<Integer> yOffset = Option.<Integer>createBuilder()
                .name(Component.literal("Map button Y offset"))
                .description(OptionDescription.of(Component.literal("Pixels from the top edge of the map screen to the widget.")))
                .binding(ClientConfig.MAP_BUTTON_Y_OFFSET.getDefault(), ClientConfig.MAP_BUTTON_Y_OFFSET::get, v -> Bindings.persist(ClientConfig.MAP_BUTTON_Y_OFFSET, v, "Map button Y offset"))
                .controller(o -> IntegerFieldControllerBuilder.create(o).min(0).max(2_000)
                        .formatValue(i -> Component.literal(String.format(Locale.ROOT, "%d", i))))
                .build();
        group.option(yOffset);

        group.option(Bindings.toggle("Log client sync", "Log when the client cache receives a deposit sync packet.", ClientConfig.LOG_CLIENT_SYNC));

        return group.build();
    }
}
