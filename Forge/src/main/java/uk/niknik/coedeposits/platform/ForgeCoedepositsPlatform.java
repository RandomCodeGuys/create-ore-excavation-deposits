package uk.niknik.coedeposits.platform;

import java.nio.file.Path;

import net.minecraft.world.level.chunk.LevelChunk;

import net.minecraftforge.fml.loading.FMLPaths;

import com.tom.createores.OreDataCapability;

/**
 * Forge implementation of {@link CoedepositsPlatform}. Registered via
 * {@code META-INF/services/uk.niknik.coedeposits.platform.CoedepositsPlatform}
 * in the Forge module's resources. Returns Forge's config directory
 * ({@code FMLPaths.CONFIGDIR}) — the same path the two former direct call-sites
 * (DepositTypeLoader / BundledRecipePack) resolved before the abstraction — and
 * reads OreData via Forge Capabilities, exactly as the former inline
 * {@code chunk.getCapability(OreDataCapability.ORE_CAP)} in {@code ensureOreData}.
 */
public final class ForgeCoedepositsPlatform implements CoedepositsPlatform {
    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public OreDataCapability.OreData oreDataNoPopulate(LevelChunk chunk) {
        return chunk.getCapability(OreDataCapability.ORE_CAP).orElse(null);
    }
}
