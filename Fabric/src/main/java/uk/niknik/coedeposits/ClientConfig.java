package uk.niknik.coedeposits;

import java.util.Locale;

import net.minecraft.util.StringRepresentable;
import net.minecraftforge.common.ForgeConfigSpec;

import com.mojang.serialization.Codec;

/**
 * Client-only configuration. Lives at {@code config/coedeposits-client.toml}.
 * Holds purely cosmetic UI knobs that don't need server sync.
 *
 * <p><b>1.20.1 line:</b> {@code ModConfigSpec} → {@link ForgeConfigSpec}.
 * Registered by {@link CoedepositsClient} (the {@code @Mod} client entry,
 * guarded so it's never loaded on a dedicated server).
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

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ═══════════════════════════════════════════════════════════════════════
    // Map button — placement and visibility of the in-Xaero toggle widget.
    // Always paired with the keybind as a fallback (Options → Controls).
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Whether to draw the {@code Deposits: ON/OFF} button on Xaero's world
     * map. Disable when it conflicts with another mod's button — the
     * Options → Controls keybind ({@code key.coedeposits.toggle_overlay})
     * still works as a fallback.
     */
    public static final ForgeConfigSpec.BooleanValue MAP_BUTTON_ENABLED = BUILDER
            .comment("Show the 'Deposits: ON/OFF' button on Xaero's world map.",
                    "Disable if it overlaps another mod's UI (e.g. Create: Steam 'n' Rails' ",
                    "Train Routes toggle). The keybind still works either way.")
            .define("map_button_enabled", true);

    /**
     * Which screen edge the widget anchors to. Default LEFT keeps it away
     * from Xaero's own top-right controls and from other right-corner UI.
     */
    public static final ForgeConfigSpec.EnumValue<Anchor> MAP_BUTTON_ANCHOR = BUILDER
            .comment("Edge to anchor the widget to. LEFT (default) or RIGHT.",
                    "Combined with map_button_x_offset to position the widget.")
            .defineEnum("map_button_anchor", Anchor.LEFT);

    /** Pixels from the anchored edge to the widget. */
    public static final ForgeConfigSpec.IntValue MAP_BUTTON_X_OFFSET = BUILDER
            .comment("Pixels from the anchored edge to the nearest side of the 16-px-wide widget.",
                    "Default 5 hugs the edge. Combined with map_button_y_offset to position the widget.")
            .defineInRange("map_button_x_offset", 5, 0, 4000);

    /** Pixels from the top edge of the map screen to the widget. */
    public static final ForgeConfigSpec.IntValue MAP_BUTTON_Y_OFFSET = BUILDER
            .comment("Pixels from the top edge of the map screen to the widget's top edge.",
                    "Default 50 sits the widget below Xaero's corner button cluster — every",
                    "Xaero corner has buttons so the mid-edge area is the only reliably-clear strip.")
            .defineInRange("map_button_y_offset", 50, 0, 2000);

    // ═══════════════════════════════════════════════════════════════════════
    // Logging toggles — client-side counterpart to the LOG_* settings in
    // Config.java.
    // ═══════════════════════════════════════════════════════════════════════

    /** Client-cache update events ("cache: +N → total M"). */
    public static final ForgeConfigSpec.BooleanValue LOG_CLIENT_SYNC = BUILDER
            .comment("Log when the client cache receives a deposit sync packet from the server.",
                    "Format: 'cache: +N → total M'. Default off; turn on briefly when debugging packet flow.")
            .define("log_client_sync", false);

    /** Built once at class-init, registered by {@link CoedepositsClient}. */
    public static final ForgeConfigSpec SPEC = BUILDER.build();
}
