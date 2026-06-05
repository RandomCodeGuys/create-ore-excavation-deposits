package uk.niknik.coedeposits.mixin;

import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gates Xaero-targeting mixins on Xaero being installed.
 *
 * <p>{@link GuiMapAccessor} targets {@code xaero.map.gui.GuiMap} from Xaero's World
 * Map, which is an OPTIONAL dependency ({@code fabric.mod.json} {@code "suggests"}).
 * Applying a mixin whose target class is absent makes Mixin error on the missing
 * class, so this plugin skips {@code GuiMapAccessor} unless {@code xaeroworldmap}
 * is present. {@link ScreenInvoker} targets vanilla {@code Screen} and always
 * applies. Registered via {@code "plugin"} in {@code coedeposits.mixins.json}.
 */
public final class CoedepositsMixinPlugin implements IMixinConfigPlugin {
    private static final boolean XAERO_PRESENT =
            FabricLoader.getInstance().isModLoaded("xaeroworldmap");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Only the Xaero accessor is conditional; everything else applies normally.
        if (mixinClassName.endsWith(".GuiMapAccessor")) {
            return XAERO_PRESENT;
        }
        return true;
    }

    // ── Remaining IMixinConfigPlugin hooks: defaults (Loom owns the refmap) ──
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
