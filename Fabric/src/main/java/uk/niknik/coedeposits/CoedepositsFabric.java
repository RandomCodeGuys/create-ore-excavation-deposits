package uk.niknik.coedeposits;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 1.20.1 entry point — <b>SKELETON</b>.
 *
 * <p>This is the gradle/build scaffolding only: the module imports under Loom
 * and Loom generates a {@code runClient} task (the Fabric counterpart to
 * Forge's {@code runClient}). No shared feature code runs yet.
 *
 * <p>The loader-agnostic core lives in {@code ../Forge/src/platform-shared} and
 * is reused by Fabric via {@code srcDir} (see {@code build.gradle}), exactly
 * like COE's Fabric line. It is <b>not</b> wired in yet because
 * {@code platform-shared/network/*} uses Forge-only
 * {@code net.minecraftforge.network.*} (SimpleChannel) APIs — those must first
 * be abstracted behind a {@code *Platform} indirection (Forge keeps
 * SimpleChannel; Fabric gets a {@code ServerPlayNetworking} impl). Once that
 * lands, this initializer registers the config, deposit-type loader, picker
 * install hooks, packets, and commands — mirroring
 * {@code uk.niknik.coedeposits.Coedeposits} on the Forge line.
 */
public final class CoedepositsFabric implements ModInitializer {
    public static final String MODID = "coedeposits";
    public static final Logger LOGGER = LoggerFactory.getLogger("coedeposits");

    @Override
    public void onInitialize() {
        LOGGER.info("[coedeposits] Fabric 1.20.1 skeleton loaded — feature port pending "
                + "(shared core blocked on the SimpleChannel networking abstraction)");
    }
}
