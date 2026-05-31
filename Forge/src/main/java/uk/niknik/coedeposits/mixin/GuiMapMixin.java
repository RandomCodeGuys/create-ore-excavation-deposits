package uk.niknik.coedeposits.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import xaero.map.gui.GuiMap;

import uk.niknik.coedeposits.ClientConfig;
import uk.niknik.coedeposits.client.DepositToggleWidget;
import uk.niknik.coedeposits.client.WorldMapDepositRenderer;

/**
 * Injects deposit overlay rendering and a toggle Button into Xaero's World
 * Map GUI. Shadow fields give us the same camera + scale state Xaero uses
 * for its own draws, so our world-space → screen-space transform matches
 * Xaero's tiles.
 */
@Mixin(GuiMap.class)
public abstract class GuiMapMixin extends Screen {
    @Shadow private double scale;
    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private int mouseBlockPosX;
    @Shadow private int mouseBlockPosZ;

    /** Mixin-required super-call; never invoked at runtime — Xaero owns construction. */
    protected GuiMapMixin(Component title) {
        super(title);
    }

    /**
     * Adds the compact {@link DepositToggleWidget} to the map screen.
     * {@link Screen#init} is called by Xaero on open and after every resize,
     * so the widget is added fresh each time — no risk of duplicates
     * accumulating across re-inits.
     *
     * <p>Position and visibility come from {@link ClientConfig}, which lets
     * the player move the widget out of the way of another mod's UI (e.g.
     * Create: Steam 'n' Rails' Train Routes toggle), or disable it entirely
     * in favour of the keybind.
     */
    @Inject(method = "init", at = @At("TAIL"))
    public void coedeposits$addToggleButton(CallbackInfo ci) {
        if (!ClientConfig.MAP_BUTTON_ENABLED.get()) return;
        int xOffset = ClientConfig.MAP_BUTTON_X_OFFSET.get();
        int x = switch (ClientConfig.MAP_BUTTON_ANCHOR.get()) {
            case LEFT  -> xOffset;
            case RIGHT -> this.width - xOffset;
        };
        int y = ClientConfig.MAP_BUTTON_Y_OFFSET.get();
        this.addRenderableWidget(new DepositToggleWidget(x, y));
    }

    /**
     * Runs at the tail of Xaero's {@code GuiMap.render}. Forwards the camera
     * + scale state captured via shadow fields to
     * {@link WorldMapDepositRenderer#render} so our overlay draws over the
     * Xaero base map but under in-game GUI widgets that render later.
     */
    @Inject(method = "render", at = @At("TAIL"))
    public void coedeposits$renderOverlay(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTicks,
            CallbackInfo ci) {
        WorldMapDepositRenderer.render(
                this, graphics, mouseX, mouseY,
                this.scale, this.cameraX, this.cameraZ,
                this.mouseBlockPosX, this.mouseBlockPosZ);
    }
}
