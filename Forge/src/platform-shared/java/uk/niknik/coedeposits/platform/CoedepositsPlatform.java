package uk.niknik.coedeposits.platform;

import java.nio.file.Path;
import java.util.ServiceLoader;

import net.minecraft.world.level.chunk.LevelChunk;

import com.tom.createores.OreDataCapability;

/**
 * Tiny loader-abstraction for the handful of platform-specific lookups the
 * loader-agnostic core needs. Lives in {@code platform-shared}; each loader
 * module ships exactly one implementation, discovered at runtime via
 * {@link ServiceLoader} from {@code META-INF/services/uk.niknik.coedeposits.platform.CoedepositsPlatform}.
 *
 * <ul>
 *   <li>Forge impl ({@code Forge/src/main}) returns {@code FMLPaths.CONFIGDIR.get()}
 *       and reads OreData via Forge Capabilities ({@code chunk.getCapability(ORE_CAP)}).</li>
 *   <li>Fabric impl ({@code Fabric/src/main}) returns
 *       {@code FabricLoader.getInstance().getConfigDir()} and reads OreData via
 *       {@code OreDataCapability.getData(chunk)} (COE Fabric has no Forge Capabilities;
 *       it backs OreData with ChunkStorage instead).</li>
 * </ul>
 *
 * <p>Replaces the two direct {@code FMLPaths.CONFIGDIR} call-sites that used to
 * live in {@link uk.niknik.coedeposits.deposit.DepositTypeLoader} and
 * {@link uk.niknik.coedeposits.pack.BundledRecipePack}, plus the one Forge-only
 * {@code chunk.getCapability(OreDataCapability.ORE_CAP)} call in
 * {@link uk.niknik.coedeposits.gen.CoedepositsPicker#ensureOreData} — the only
 * Forge-only refs in the shared core. Everything else is vanilla MC / the
 * cross-loader COE API and ports unchanged.
 */
public interface CoedepositsPlatform {
    /** The active loader's config directory (e.g. {@code .minecraft/config} or {@code run/config}). */
    Path configDir();

    /**
     * Fetch a chunk's existing {@link OreDataCapability.OreData} for the
     * deterministic-write path in {@code CoedepositsPicker.ensureOreData} —
     * which must NOT depend on COE's installed picker.
     *
     * <p>Forge returns the capability instance directly via
     * {@code chunk.getCapability(ORE_CAP)} (no lazy populate). Fabric returns
     * {@code OreDataCapability.getData(chunk)} — there is no public no-populate
     * accessor on the ChunkStorage-backed Fabric side, but {@code ensureOreData}
     * immediately overwrites the recipe when it differs, so the end state is the
     * same. May return {@code null} (chunk has no provider yet).
     */
    OreDataCapability.OreData oreDataNoPopulate(LevelChunk chunk);

    /**
     * True when a mod with the given id is present. Loader-abstracted: Forge
     * {@code ModList.get().isLoaded} / Fabric {@code FabricLoader.isModLoaded}.
     * Used by {@link uk.niknik.coedeposits.compat.TeamBridge} to gate its
     * reflection-based OPAC / FTB-Teams integrations.
     */
    boolean isModLoaded(String modId);

    /** Lazily-resolved singleton — the first (and only) registered service impl. */
    final class Holder {
        private static volatile CoedepositsPlatform instance;

        private Holder() {}

        static CoedepositsPlatform resolve() {
            CoedepositsPlatform local = instance;
            if (local != null) return local;
            synchronized (Holder.class) {
                if (instance == null) {
                    instance = ServiceLoader.load(CoedepositsPlatform.class)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "No CoedepositsPlatform service registered — "
                                            + "check META-INF/services in the loader module"));
                }
                return instance;
            }
        }
    }

    /** Accessor used by the shared core. */
    static CoedepositsPlatform get() {
        return Holder.resolve();
    }
}
