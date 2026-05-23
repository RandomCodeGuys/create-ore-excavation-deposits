package uk.niknik.coedeposits;

import java.util.Locale;

import net.minecraft.util.StringRepresentable;
import net.neoforged.neoforge.common.ModConfigSpec;

import com.mojang.serialization.Codec;

/**
 * Client-only configuration. Lives at
 * {@code run/config/coedeposits-client.toml} in dev / per-client config dir
 * in production. Holds purely cosmetic UI knobs that don't need to be
 * synced from the server.
 *
 * <p>Registered by {@link CoedepositsClient} (the {@code @Mod(dist=CLIENT)}
 * entry) — never loaded on a dedicated server.
 *
 * <p>Today this only holds map-button positioning so it can be moved or
 * disabled when another mod's UI (e.g. Create: Steam 'n' Rails' Train
 * Routes toggle) lands in the same screen corner. The world-map overlay
 * toggle keybind remains the always-available fallback when the button is
 * disabled.
 */
public final class ClientConfig {
    private ClientConfig() {}

    /**
     * Which screen edge the toggle widget anchors to. {@link #MAP_BUTTON_X_OFFSET}
     * is measured from that edge to the widget's nearest side.
     */
    public enum Anchor implements StringRepresentable {
        LEFT, RIGHT;

        public static final Codec<Anchor> CODEC = StringRepresentable.fromEnum(Anchor::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * Whether to draw the {@code Deposits: ON/OFF} button on Xaero's world
     * map. Disable when it conflicts with another mod's button — the
     * Options → Controls keybind ({@code key.coedeposits.toggle_overlay})
     * still works as a fallback.
     */
    public static final ModConfigSpec.BooleanValue MAP_BUTTON_ENABLED = BUILDER
            .comment("Show the 'Deposits: ON/OFF' button on Xaero's world map.",
                    "Disable if it overlaps another mod's UI (e.g. Create: Steam 'n' Rails' ",
                    "Train Routes toggle). The keybind still works either way.")
            .define("map_button_enabled", true);

    /**
     * Which screen edge the widget anchors to. Default LEFT keeps it away
     * from Xaero's own top-right controls (zoom / mode buttons) and from
     * Create: Steam 'n' Rails' Train Routes toggle, which both sit on the
     * right.
     */
    public static final ModConfigSpec.EnumValue<Anchor> MAP_BUTTON_ANCHOR = BUILDER
            .comment("Edge to anchor the widget to. LEFT (default) or RIGHT.",
                    "Combined with map_button_x_offset to position the widget.")
            .defineEnum("map_button_anchor", Anchor.LEFT);

    /**
     * Pixels from the anchored edge to the widget. Default 35 sits the
     * widget just to the right of Xaero's settings (⚙) icon when anchored
     * LEFT, with breathing room.
     */
    public static final ModConfigSpec.IntValue MAP_BUTTON_X_OFFSET = BUILDER
            .comment("Pixels from the anchored edge to the nearest side of the 16-px-wide widget.",
                    "Default 35 sits the widget just to the right of Xaero's settings (⚙) icon",
                    "when anchored LEFT, with breathing room.")
            .defineInRange("map_button_x_offset", 35, 0, 4000);

    /**
     * Pixels from the top edge of the map screen to the widget's top edge.
     * Increase to push the widget below another mod's UI at the same column.
     */
    public static final ModConfigSpec.IntValue MAP_BUTTON_Y_OFFSET = BUILDER
            .comment("Pixels from the top edge of the map screen to the widget's top edge.",
                    "Increase to push the widget below another mod's UI at the same column",
                    "(e.g. 26 = below a 16-px widget + 10-px gap).")
            .defineInRange("map_button_y_offset", 5, 0, 2000);

    /** Built once at class-init, registered by {@link CoedepositsClient}. */
    public static final ModConfigSpec SPEC = BUILDER.build();
}
