package uk.niknik.coedeposits.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;

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
 * Injects deposit overlay rendering and a toggle button into Xaero's World Map
 * GUI. Shadow fields give us the same camera + scale state Xaero uses for its
 * own draws, so our world-space → screen-space transform matches Xaero's tiles.
 *
 * <p><b>Fabric 1.20.1 line — does NOT {@code extends Screen}.</b> The Forge copy
 * extends {@code Screen} for {@code addRenderableWidget} / {@code width} /
 * {@code height}. On Fabric the Mixin annotation processor can't walk Xaero's
 * {@code GuiMap → ScreenBase → Screen} chain (Xaero is a compile-only mod whose
 * intermediate {@code ScreenBase} the AP fails to resolve to vanilla
 * {@code Screen}), so {@code extends Screen} makes it abort with "Superclass
 * Screen not found in the hierarchy of target class GuiMap" — the same hierarchy
 * limitation the Forge build documents for its refmap. We therefore target
 * {@code GuiMap} without a Java superclass and reach the {@code Screen} members
 * via a {@code @Shadow} of {@code GuiMap}'s own {@code addRenderableWidget}
 * override plus a {@code (Screen)(Object)this} cast (resolved at runtime — GuiMap
 * IS a Screen). Loom auto-generates the refmap at remap; the Xaero {@code @Shadow}
 * fields are Xaero's own (not MC-mapped) so they need no entry.
 */
@Mixin(GuiMap.class)
public abstract class GuiMapMixin {
    @Shadow private double scale;
    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private int mouseBlockPosX;
    @Shadow private int mouseBlockPosZ;

    /** {@code GuiMap} overrides {@code Screen#addRenderableWidget}, so we shadow it on the target directly. */
    @Shadow
    protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);

    /**
     * Adds the compact {@link DepositToggleWidget} to the map screen. Xaero calls
     * {@code init} on open and after every resize, so the widget is added fresh
     * each time — no duplicates across re-inits. Position/visibility come from
     * {@link ClientConfig}.
     */
    @Inject(method = "init", at = @At("TAIL"))
    public void coedeposits$addToggleButton(CallbackInfo ci) {
        if (!ClientConfig.MAP_BUTTON_ENABLED.get()) return;
        int width = ((Screen) (Object) this).width;
        int xOffset = ClientConfig.MAP_BUTTON_X_OFFSET.get();
        int x = switch (ClientConfig.MAP_BUTTON_ANCHOR.get()) {
            case LEFT  -> xOffset;
            case RIGHT -> width - xOffset;
        };
        int y = ClientConfig.MAP_BUTTON_Y_OFFSET.get();
        this.addRenderableWidget(new DepositToggleWidget(x, y));
    }

    /**
     * Runs at the tail of Xaero's {@code GuiMap.render}. Forwards the camera +
     * scale state captured via shadow fields to
     * {@link WorldMapDepositRenderer#render} so our overlay draws over the Xaero
     * base map but under in-game GUI widgets that render later.
     */
    @Inject(method = "render", at = @At("TAIL"))
    public void coedeposits$renderOverlay(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTicks,
            CallbackInfo ci) {
        WorldMapDepositRenderer.render(
                (Screen) (Object) this, graphics, mouseX, mouseY,
                this.scale, this.cameraX, this.cameraZ,
                this.mouseBlockPosX, this.mouseBlockPosZ);
    }
}
