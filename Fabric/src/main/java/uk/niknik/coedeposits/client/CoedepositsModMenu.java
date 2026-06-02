package uk.niknik.coedeposits.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration — surfaces the YACL config screen (which also hosts the
 * deposit editor) from ModMenu's mod list. This is the Fabric counterpart to
 * Forge's {@code ConfigScreenHandler.ConfigScreenFactory} extension point
 * registered in {@code CoedepositsClient#registerConfigScreen}.
 *
 * <p>Registered via the {@code "modmenu"} entrypoint in {@code fabric.mod.json}.
 * The screen builder ({@link YaclConfigScreen#create}) is identical to the Forge
 * line — YACL is the primary config UI on Fabric (no native config screen).
 */
public final class CoedepositsModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return YaclConfigScreen::create;
    }
}
