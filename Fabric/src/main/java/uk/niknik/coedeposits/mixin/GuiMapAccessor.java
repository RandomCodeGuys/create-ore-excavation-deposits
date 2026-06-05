package uk.niknik.coedeposits.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import xaero.map.gui.GuiMap;

/**
 * Read-only {@code @Accessor}s for Xaero {@code GuiMap}'s private camera + scale
 * state, consumed by the world-map overlay (registered through Fabric
 * {@code ScreenEvents} in {@link uk.niknik.coedeposits.client.CoedepositsFabricClient}).
 *
 * <p>These are Xaero's OWN fields — not Minecraft members — so they carry no
 * {@code named→intermediary} mapping; the generated accessors read them by their
 * literal Xaero names in every runtime, which is why the {@code "Unable to locate
 * obfuscation mapping"} AP warning on each is expected and harmless. {@code GuiMap}
 * IS a {@code Screen} at runtime, so the overlay callback casts the rendered
 * screen to this interface to pull the same transform Xaero draws its tiles with.
 */
@Mixin(GuiMap.class)
public interface GuiMapAccessor {
    @Accessor("scale")          double coedeposits$scale();
    @Accessor("cameraX")        double coedeposits$cameraX();
    @Accessor("cameraZ")        double coedeposits$cameraZ();
    @Accessor("mouseBlockPosX") int    coedeposits$mouseBlockPosX();
    @Accessor("mouseBlockPosZ") int    coedeposits$mouseBlockPosZ();
}
