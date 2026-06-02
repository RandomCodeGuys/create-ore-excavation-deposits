package uk.niknik.coedeposits.pack;

import java.util.function.Consumer;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

/**
 * {@link RepositorySource} that injects {@link BundledRecipePack} into the
 * server-data pack repository on each rebuild.
 *
 * <p><b>Fabric 1.20.1 line.</b> Verbatim copy of the Forge module's provider
 * (it is pure vanilla MC — no Forge imports). Registered via Porting-Lib's
 * {@code AddPackFindersEvent} from {@link uk.niknik.coedeposits.CoedepositsFabric}
 * (the Fabric mirror of Forge's {@code AddPackFindersEvent}). Uses
 * {@code Pack.readMetaAndCreate(...)} and the single-method
 * {@code ResourcesSupplier.open(String)}. Required + TOP position so the pack is
 * always on and lets real datapacks override its synthesised recipes.
 */
public final class BundledRecipePackProvider implements RepositorySource {

    @Override
    public void loadPacks(Consumer<Pack> consumer) {
        Pack.ResourcesSupplier supplier = BundledRecipePack::new;
        Pack pack = Pack.readMetaAndCreate(
                BundledRecipePack.PACK_ID,
                Component.literal("Coedeposits Config Recipes"),
                /* required = */ true,
                supplier,
                PackType.SERVER_DATA,
                Pack.Position.TOP,
                PackSource.BUILT_IN);
        if (pack != null) {
            consumer.accept(pack);
        }
    }
}
