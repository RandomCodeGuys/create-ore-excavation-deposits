package uk.niknik.coedeposits.platform;

import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.chunk.LevelChunk;

import com.tom.createores.OreDataCapability;

/**
 * Fabric implementation of {@link CoedepositsPlatform}. Registered via
 * {@code META-INF/services/uk.niknik.coedeposits.platform.CoedepositsPlatform}
 * in the Fabric module's resources. Returns Fabric's config directory
 * ({@code FabricLoader.getInstance().getConfigDir()}) — the Fabric counterpart
 * to Forge's {@code FMLPaths.CONFIGDIR} — and reads OreData through COE Fabric's
 * {@code OreDataCapability.getData(chunk)} (COE Fabric backs OreData with
 * ChunkStorage, not Forge Capabilities, and exposes no no-populate accessor; the
 * caller {@code ensureOreData} overwrites the recipe when it differs, so the
 * populate is harmless).
 */
public final class FabricCoedepositsPlatform implements CoedepositsPlatform {
    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public OreDataCapability.OreData oreDataNoPopulate(LevelChunk chunk) {
        return OreDataCapability.getData(chunk);
    }
}
