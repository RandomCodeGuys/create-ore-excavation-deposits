package uk.niknik.coedeposits.pack;

import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

/**
 * {@link RepositorySource} that injects our {@link BundledRecipePack} into the
 * server-data pack repository on each rebuild. Registered with NeoForge's
 * {@code AddPackFindersEvent} during mod construction so the pack appears for
 * every datapack reload (server start, {@code /reload}, world load).
 *
 * <p>Pack characteristics:
 * <ul>
 *   <li><b>required = true</b> — always-on, can't be disabled via
 *       {@code /datapack disable}. The mod relies on its generated recipes;
 *       disabling would silently break inline {@code vein:} / {@code drilling:}
 *       blocks.</li>
 *   <li><b>defaultPosition = TOP</b> — loads LAST among server-data packs,
 *       letting bundled-jar / user-datapack recipes override ours. Admins
 *       who want to hand-craft a recipe with the same id can drop a regular
 *       datapack and it'll take precedence.</li>
 *   <li><b>fixedPosition = true</b> — user can't reorder this in the pack
 *       UI; position is determined entirely by the override rule above.</li>
 * </ul>
 */
public final class BundledRecipePackProvider implements RepositorySource {

    @Override
    public void loadPacks(Consumer<Pack> consumer) {
        PackLocationInfo location = new PackLocationInfo(
                BundledRecipePack.PACK_ID,
                Component.literal("Coedeposits Config Recipes"),
                PackSource.BUILT_IN,
                Optional.empty());
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public net.minecraft.server.packs.PackResources openPrimary(PackLocationInfo info) {
                return new BundledRecipePack(info);
            }

            @Override
            public net.minecraft.server.packs.PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                // No overlays — primary is sufficient.
                return new BundledRecipePack(info);
            }
        };
        PackSelectionConfig selectionConfig = new PackSelectionConfig(
                /* required = */ true,
                /* defaultPosition = */ Pack.Position.TOP,
                /* fixedPosition = */ true);
        Pack pack = Pack.readMetaAndCreate(location, supplier, PackType.SERVER_DATA, selectionConfig);
        if (pack != null) {
            consumer.accept(pack);
        }
    }
}
